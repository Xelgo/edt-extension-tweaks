package ru.xelgo.edt.contextlinks.ui;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;

import ru.xelgo.edt.contextlinks.core.ContextLinks;

/**
 * Matches navigator elements against current uncommitted Git changes.
 */
final class ContextLinksGitChangedResources
{
    static final String FILTER_ID = "ru.xelgo.edt.contextlinks.ui.filters.gitChanged"; //$NON-NLS-1$

    private static final long CACHE_TTL_MILLIS = 1500L;
    private static volatile Snapshot cachedSnapshot = Snapshot.expired();

    private ContextLinksGitChangedResources()
    {
        // Utility class.
    }

    static void invalidate()
    {
        cachedSnapshot = Snapshot.expired();
    }

    static boolean isChangedElement(Object parentElement, Object element)
    {
        if (element instanceof IProject)
            return isChangedResource((IProject)element);

        Boolean collectionResult = isChangedNavigatorCollection(parentElement, element);
        if (collectionResult != null)
            return collectionResult.booleanValue();

        IResource resource = ProjectSelection.getResource(element);
        if (resource != null)
            return isChangedMetadataResource(resource);

        IProject project = ProjectSelection.getProject(element);
        if (project != null)
            return isChangedResource(project);

        return true;
    }

    static boolean isChangedElement(Object element)
    {
        return isChangedElement(null, element);
    }

    private static boolean isChangedMetadataResource(IResource resource)
    {
        if (isChangedResource(resource))
            return true;

        if (resource instanceof IFile && "mdo".equalsIgnoreCase(resource.getFileExtension())) //$NON-NLS-1$
        {
            IContainer parent = resource.getParent();
            return parent != null && isChangedResource(parent);
        }

        return false;
    }

    private static Boolean isChangedNavigatorCollection(Object parentElement, Object element)
    {
        if (element == null)
            return null;

        String className = element.getClass().getName();
        String modelObjectName = getModelObjectName(element);
        boolean navigatorAdapter = className.contains("NavigatorAdapter") || modelObjectName != null; //$NON-NLS-1$
        if (!navigatorAdapter)
            return null;

        IProject project = ProjectSelection.getProject(element);
        if (project == null)
            project = ProjectSelection.getProject(parentElement);
        if (project == null || !project.isAccessible())
            return Boolean.FALSE;

        EObject parentModel = ProjectSelection.getModelObject(element);
        if (parentModel != null && modelObjectName != null)
        {
            if (isConfiguration(parentModel))
                return Boolean.valueOf(hasChangedTopLevelCollection(project, modelObjectName));

            return Boolean.valueOf(hasChangedNestedCollection(parentModel, modelObjectName));
        }

        if (className.endsWith("CommonNavigatorAdapter")) //$NON-NLS-1$
            return Boolean.valueOf(hasChangedAnyProjectPath(project, COMMON_SOURCE_FOLDERS));

        String metadataType = extractMetadataTypeFromNavigatorClass(className);
        if (metadataType != null)
        {
            String sourceFolder = METADATA_TYPE_SOURCE_FOLDERS.getOrDefault(metadataType, metadataType + "s"); //$NON-NLS-1$
            return Boolean.valueOf(hasChangedProjectPath(project, "src/" + sourceFolder)); //$NON-NLS-1$
        }

        if (modelObjectName != null)
            return Boolean.valueOf(hasChangedProjectPath(project, "src/" + toSourceFolderName(modelObjectName))); //$NON-NLS-1$

        return Boolean.FALSE;
    }

    private static boolean hasChangedNestedCollection(EObject parentModel, String modelObjectName)
    {
        IResource parentResource = ProjectSelection.getResource(parentModel);
        if (parentResource == null)
            return false;

        IContainer base = parentResource instanceof IContainer ? (IContainer)parentResource : parentResource.getParent();
        if (base == null)
            return false;

        String sourceFolder = NESTED_SOURCE_FOLDERS.getOrDefault(modelObjectName, toSourceFolderName(modelObjectName));
        return isChangedResource(base.getFolder(new Path(sourceFolder)));
    }

    private static boolean hasChangedTopLevelCollection(IProject project, String modelObjectName)
    {
        String sourceFolder = METADATA_TYPE_SOURCE_FOLDERS.getOrDefault(modelObjectName, toSourceFolderName(modelObjectName));
        return hasChangedProjectPath(project, "src/" + sourceFolder); //$NON-NLS-1$
    }

    private static boolean isConfiguration(EObject modelObject)
    {
        return modelObject != null && "Configuration".equals(modelObject.eClass().getName()); //$NON-NLS-1$
    }

    private static boolean hasChangedAnyProjectPath(IProject project, Set<String> relativePaths)
    {
        for (String relativePath : relativePaths)
        {
            if (hasChangedProjectPath(project, "src/" + relativePath)) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    private static boolean hasChangedProjectPath(IProject project, String relativePath)
    {
        return isChangedResource(project.getFolder(new Path(relativePath)));
    }

    private static String getModelObjectName(Object element)
    {
        Object value = ProjectSelection.invokeNoArg(element, "getModelObjectName"); //$NON-NLS-1$
        return value != null ? value.toString() : null;
    }

    private static String extractMetadataTypeFromNavigatorClass(String className)
    {
        int index = className.lastIndexOf('.');
        String simpleName = index >= 0 ? className.substring(index + 1) : className;
        int adapterIndex = simpleName.indexOf("NavigatorAdapter"); //$NON-NLS-1$
        if (adapterIndex <= 0)
            return null;

        String metadataType = simpleName.substring(0, adapterIndex);
        return "Common".equals(metadataType) ? null : metadataType; //$NON-NLS-1$
    }

    private static String toSourceFolderName(String modelObjectName)
    {
        if (modelObjectName == null || modelObjectName.isBlank())
            return modelObjectName;

        if (modelObjectName.endsWith("y")) //$NON-NLS-1$
            return modelObjectName.substring(0, modelObjectName.length() - 1) + "ies"; //$NON-NLS-1$

        return modelObjectName + "s"; //$NON-NLS-1$
    }

    private static boolean isChangedResource(IResource resource)
    {
        if (resource != null)
            return isChangedResourceInternal(resource);

        return false;
    }

    private static final Map<String, String> METADATA_TYPE_SOURCE_FOLDERS = Map.ofEntries(
        Map.entry("AccountingRegister", "AccountingRegisters"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("AccumulationRegister", "AccumulationRegisters"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("BusinessProcess", "BusinessProcesses"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Bot", "Bots"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("CalculationRegister", "CalculationRegisters"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Catalog", "Catalogs"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("ChartOfAccounts", "ChartsOfAccounts"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("ChartOfCalculationTypes", "ChartsOfCalculationTypes"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("ChartOfCharacteristicTypes", "ChartsOfCharacteristicTypes"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("CommonAttribute", "CommonAttributes"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("CommonCommand", "CommonCommands"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("CommonForm", "CommonForms"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("CommonModule", "CommonModules"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("CommonPicture", "CommonPictures"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("CommonTemplate", "CommonTemplates"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Constant", "Constants"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("DefinedType", "DefinedTypes"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Document", "Documents"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("DocumentJournal", "DocumentJournals"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Enum", "Enums"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("EventSubscription", "EventSubscriptions"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("ExchangePlan", "ExchangePlans"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("ExternalDataProcessor", "ExternalDataProcessors"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("ExternalReport", "ExternalReports"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("FilterCriterion", "FilterCriteria"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("FunctionalOption", "FunctionalOptions"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("FunctionalOptionsParameter", "FunctionalOptionsParameters"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("HTTPService", "HTTPServices"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("InformationRegister", "InformationRegisters"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("IntegrationService", "IntegrationServices"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Language", "Languages"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Report", "Reports"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Role", "Roles"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("ScheduledJob", "ScheduledJobs"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("SessionParameter", "SessionParameters"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("SettingsStorage", "SettingsStorages"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Style", "Styles"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Subsystem", "Subsystems"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Task", "Tasks"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("WebService", "WebServices"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("WebSocketClient", "WebSocketClients"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("WSReference", "WSReferences"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("XDTOPackage", "XDTOPackages")); //$NON-NLS-1$ //$NON-NLS-2$

    private static final Map<String, String> NESTED_SOURCE_FOLDERS = Map.ofEntries(
        Map.entry("Attribute", "Attributes"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Command", "Commands"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Dimension", "Dimensions"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("EnumValue", "EnumValues"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Form", "Forms"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Resource", "Resources"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("TabularSection", "TabularSections"), //$NON-NLS-1$ //$NON-NLS-2$
        Map.entry("Template", "Templates")); //$NON-NLS-1$ //$NON-NLS-2$

    private static final Set<String> COMMON_SOURCE_FOLDERS = Set.of(
        "Bots", //$NON-NLS-1$
        "CommandGroups", //$NON-NLS-1$
        "CommonAttributes", //$NON-NLS-1$
        "CommonCommands", //$NON-NLS-1$
        "CommonForms", //$NON-NLS-1$
        "CommonModules", //$NON-NLS-1$
        "CommonPictures", //$NON-NLS-1$
        "CommonTemplates", //$NON-NLS-1$
        "DefinedTypes", //$NON-NLS-1$
        "EventSubscriptions", //$NON-NLS-1$
        "ExchangePlans", //$NON-NLS-1$
        "FilterCriteria", //$NON-NLS-1$
        "FunctionalOptions", //$NON-NLS-1$
        "FunctionalOptionsParameters", //$NON-NLS-1$
        "HTTPServices", //$NON-NLS-1$
        "IntegrationServices", //$NON-NLS-1$
        "Languages", //$NON-NLS-1$
        "Roles", //$NON-NLS-1$
        "ScheduledJobs", //$NON-NLS-1$
        "SessionParameters", //$NON-NLS-1$
        "SettingsStorages", //$NON-NLS-1$
        "Styles", //$NON-NLS-1$
        "Subsystems", //$NON-NLS-1$
        "WebServices", //$NON-NLS-1$
        "WebSocketClients", //$NON-NLS-1$
        "WSReferences", //$NON-NLS-1$
        "XDTOPackages"); //$NON-NLS-1$

    private static boolean isChangedResourceInternal(IResource resource)
    {
        if (resource == null)
            return false;

        IProject project = resource instanceof IProject ? (IProject)resource : resource.getProject();
        if (project == null || !project.isAccessible())
            return false;

        RepositoryMapping mapping = getMapping(resource, project);
        if (mapping == null)
            return false;

        RepositoryChanges changes = snapshot().getChanges(mapping);
        if (changes.isEmpty())
            return false;

        String repoPath = getRepoRelativePath(mapping, resource);
        return changes.contains(repoPath);
    }

    private static RepositoryMapping getMapping(IResource resource, IProject project)
    {
        try
        {
            RepositoryMapping mapping = RepositoryMapping.getMapping(resource);
            return mapping != null ? mapping : RepositoryMapping.getMapping(project);
        }
        catch (RuntimeException e)
        {
            ContextLinks.logWarning("Failed to resolve Git repository mapping: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private static Snapshot snapshot()
    {
        long now = System.currentTimeMillis();
        Snapshot current = cachedSnapshot;
        if (current.isFresh(now))
            return current;

        synchronized (ContextLinksGitChangedResources.class)
        {
            current = cachedSnapshot;
            if (current.isFresh(now))
                return current;

            current = new Snapshot(now);
            cachedSnapshot = current;
            return current;
        }
    }

    private static String getRepoRelativePath(RepositoryMapping mapping, IResource resource)
    {
        try
        {
            String repoRelativePath = mapping.getRepoRelativePath(resource);
            if (repoRelativePath != null)
                return normalize(repoRelativePath);
        }
        catch (RuntimeException e)
        {
            // Fall back to workspace-relative path mapping below.
        }

        IPath containerPath = mapping.getContainerPath();
        IPath resourcePath = resource.getFullPath();
        if (containerPath != null && resourcePath != null && containerPath.isPrefixOf(resourcePath))
            return normalize(resourcePath.makeRelativeTo(containerPath).toString());

        return null;
    }

    private static RepositoryChanges readChanges(RepositoryMapping mapping)
    {
        Repository repository = mapping.getRepository();
        if (repository == null)
            return RepositoryChanges.EMPTY;

        try
        {
            Status status = Git.wrap(repository).status().call();
            Set<String> paths = new LinkedHashSet<>();
            addAll(paths, status.getUncommittedChanges());
            addAll(paths, status.getUntracked());
            addAll(paths, status.getUntrackedFolders());
            addAll(paths, status.getConflicting());
            return paths.isEmpty() ? RepositoryChanges.EMPTY : new RepositoryChanges(paths);
        }
        catch (GitAPIException | RuntimeException e)
        {
            ContextLinks.logWarning("Failed to read Git status for " + describeRepository(mapping) + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return RepositoryChanges.EMPTY;
        }
    }

    private static void addAll(Set<String> target, Set<String> paths)
    {
        for (String path : paths)
        {
            String normalized = normalize(path);
            if (normalized != null)
                target.add(normalized);
        }
    }

    private static String normalize(String path)
    {
        if (path == null)
            return null;

        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) //$NON-NLS-1$
            normalized = normalized.substring(1);
        while (normalized.endsWith("/")) //$NON-NLS-1$
            normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String repositoryKey(RepositoryMapping mapping)
    {
        Repository repository = mapping.getRepository();
        if (repository != null && repository.getDirectory() != null)
            return repository.getDirectory().getAbsolutePath();

        File workTree = mapping.getWorkTree();
        return workTree != null ? workTree.getAbsolutePath() : mapping.toString();
    }

    private static String describeRepository(RepositoryMapping mapping)
    {
        File workTree = mapping.getWorkTree();
        return workTree != null ? workTree.getAbsolutePath() : repositoryKey(mapping);
    }

    private static final class Snapshot
    {
        private final long createdAt;
        private final Map<String, RepositoryChanges> repositories = new ConcurrentHashMap<>();

        private Snapshot(long createdAt)
        {
            this.createdAt = createdAt;
        }

        static Snapshot expired()
        {
            return new Snapshot(0L);
        }

        boolean isFresh(long now)
        {
            return now - createdAt < CACHE_TTL_MILLIS;
        }

        RepositoryChanges getChanges(RepositoryMapping mapping)
        {
            return repositories.computeIfAbsent(repositoryKey(mapping), key -> readChanges(mapping));
        }
    }

    private static final class RepositoryChanges
    {
        static final RepositoryChanges EMPTY = new RepositoryChanges(Set.of());

        private final Set<String> paths;

        private RepositoryChanges(Set<String> paths)
        {
            this.paths = paths;
        }

        boolean isEmpty()
        {
            return paths.isEmpty();
        }

        boolean contains(String repoPath)
        {
            String normalized = normalize(repoPath);
            if (normalized == null || normalized.isEmpty())
                return !paths.isEmpty();

            String childPrefix = normalized + '/';
            for (String path : paths)
            {
                if (path.equals(normalized) || path.startsWith(childPrefix))
                    return true;

                if (normalized.startsWith(path + '/'))
                    return true;
            }
            return false;
        }
    }
}
