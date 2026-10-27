package ru.xelgo.edt.contextlinks.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.egit.core.project.GitProjectData;
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

    private final Snapshot snapshot;

    ContextLinksGitChangedResources(Snapshot snapshot)
    {
        this.snapshot = snapshot;
    }

    boolean isChangedElement(Object parentElement, Object element)
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

    boolean isChangedElement(Object element)
    {
        return isChangedElement(null, element);
    }

    private boolean isChangedMetadataResource(IResource resource)
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

    private Boolean isChangedNavigatorCollection(Object parentElement, Object element)
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

    private boolean hasChangedNestedCollection(EObject parentModel, String modelObjectName)
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

    private boolean hasChangedTopLevelCollection(IProject project, String modelObjectName)
    {
        String sourceFolder = METADATA_TYPE_SOURCE_FOLDERS.getOrDefault(modelObjectName, toSourceFolderName(modelObjectName));
        return hasChangedProjectPath(project, "src/" + sourceFolder); //$NON-NLS-1$
    }

    private static boolean isConfiguration(EObject modelObject)
    {
        return modelObject != null && "Configuration".equals(modelObject.eClass().getName()); //$NON-NLS-1$
    }

    private boolean hasChangedAnyProjectPath(IProject project, Set<String> relativePaths)
    {
        for (String relativePath : relativePaths)
        {
            if (hasChangedProjectPath(project, "src/" + relativePath)) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    private boolean hasChangedProjectPath(IProject project, String relativePath)
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

    private boolean isChangedResource(IResource resource)
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

    private boolean isChangedResourceInternal(IResource resource)
    {
        if (resource == null)
            return false;

        IProject project = resource instanceof IProject ? (IProject)resource : resource.getProject();
        if (project == null || !project.isAccessible())
            return false;

        return snapshot.contains(resource.getLocation());
    }

    static Snapshot readWorkspaceSnapshot(IProgressMonitor monitor)
    {
        List<Repository> repositories = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
        {
            checkCanceled(monitor);
            if (!project.isAccessible())
                continue;
            GitProjectData data = GitProjectData.get(project);
            if (data != null)
            {
                for (RepositoryMapping mapping : data.getRepositoryMappings().values())
                {
                    Repository repository = mapping.getRepository();
                    if (repository != null)
                        repositories.add(repository);
                }
            }
        }
        return Snapshot.read(repositories, monitor, ContextLinksGitChangedResources::readChanges);
    }

    static RepositoryChanges readChanges(Repository repository, IProgressMonitor monitor)
    {
        checkCanceled(monitor);
        try
        {
            Status status = Git.wrap(repository).status().setProgressMonitor(new GitProgress(monitor)).call();
            checkCanceled(monitor);
            Set<String> paths = new LinkedHashSet<>();
            addAll(paths, status.getUncommittedChanges());
            addAll(paths, status.getUntracked());
            addAll(paths, status.getUntrackedFolders());
            addAll(paths, status.getConflicting());
            return paths.isEmpty() ? RepositoryChanges.EMPTY : new RepositoryChanges(paths);
        }
        catch (GitAPIException | RuntimeException e)
        {
            checkCanceled(monitor);
            ContextLinks.logWarning("Failed to read Git status for " + repository.getDirectory() + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage());
            return RepositoryChanges.UNKNOWN;
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

        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) //$NON-NLS-1$
            normalized = normalized.substring(1);
        while (normalized.endsWith("/")) //$NON-NLS-1$
            normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static void checkCanceled(IProgressMonitor monitor)
    {
        if (monitor.isCanceled())
            throw new OperationCanceledException();
    }

    @FunctionalInterface
    interface StatusReader
    {
        RepositoryChanges read(Repository repository, IProgressMonitor monitor);
    }

    static final class Snapshot
    {
        private final Map<IPath, RepositoryChanges> workTrees;

        Snapshot(Map<IPath, RepositoryChanges> workTrees)
        {
            this.workTrees = Map.copyOf(workTrees);
        }

        static Snapshot read(Iterable<Repository> repositories, IProgressMonitor monitor, StatusReader reader)
        {
            Map<String, RepositoryChanges> changes = new LinkedHashMap<>();
            Map<IPath, RepositoryChanges> workTrees = new LinkedHashMap<>();
            for (Repository repository : repositories)
            {
                checkCanceled(monitor);
                if (repository.isBare())
                    continue;
                String key = repository.getDirectory().getAbsolutePath();
                RepositoryChanges status = changes.get(key);
                if (status == null)
                {
                    repository.incrementOpen();
                    try
                    {
                        status = reader.read(repository, monitor);
                        checkCanceled(monitor);
                        changes.put(key, status);
                    }
                    finally
                    {
                        repository.close();
                    }
                }
                workTrees.put(Path.fromOSString(repository.getWorkTree().getAbsolutePath()), status);
            }
            return new Snapshot(workTrees);
        }

        boolean contains(IPath location)
        {
            if (location == null)
                return true;
            IPath closest = null;
            for (IPath root : workTrees.keySet())
            {
                if (isLocationPrefix(root, location)
                    && (closest == null || root.segmentCount() > closest.segmentCount()))
                    closest = root;
            }
            if (closest != null && workTrees.get(closest).contains(
                location.removeFirstSegments(closest.segmentCount()).setDevice(null).makeRelative().toPortableString()))
                return true;
            for (Map.Entry<IPath, RepositoryChanges> entry : workTrees.entrySet())
            {
                if (isLocationPrefix(location, entry.getKey()) && !entry.getValue().isEmpty())
                    return true;
            }
            return false;
        }

        private boolean isLocationPrefix(IPath prefix, IPath location)
        {
            if (File.separatorChar != '\\')
                return prefix.isPrefixOf(location);
            if (prefix.segmentCount() > location.segmentCount())
                return false;
            String device = prefix.getDevice();
            if (device == null ? location.getDevice() != null : !device.equalsIgnoreCase(location.getDevice()))
                return false;
            for (int i = 0; i < prefix.segmentCount(); i++)
            {
                if (!prefix.segment(i).equalsIgnoreCase(location.segment(i)))
                    return false;
            }
            return true;
        }

        boolean containsRepository(Repository repository)
        {
            return repository != null && !repository.isBare()
                && workTrees.containsKey(Path.fromOSString(repository.getWorkTree().getAbsolutePath()));
        }
    }

    static final class RepositoryChanges
    {
        static final RepositoryChanges EMPTY = new RepositoryChanges(Set.of());
        static final RepositoryChanges UNKNOWN = new RepositoryChanges(Set.of(), true);

        private final Set<String> paths;
        private final Set<String> ancestors;
        private final boolean unknown;

        RepositoryChanges(Set<String> paths)
        {
            this(paths, false);
        }

        private RepositoryChanges(Set<String> paths, boolean unknown)
        {
            Set<String> normalized = new LinkedHashSet<>();
            addAll(normalized, paths);
            this.paths = Collections.unmodifiableSet(normalized);
            Set<String> index = new LinkedHashSet<>(normalized);
            for (String path : normalized)
            {
                for (int slash = path.lastIndexOf('/'); slash >= 0; slash = path.lastIndexOf('/', slash - 1))
                    index.add(path.substring(0, slash));
            }
            this.ancestors = Collections.unmodifiableSet(index);
            this.unknown = unknown;
        }

        boolean isEmpty()
        {
            return !unknown && paths.isEmpty();
        }

        boolean contains(String repoPath)
        {
            String normalized = normalize(repoPath);
            if (unknown)
                return true;
            if (normalized == null || normalized.isEmpty())
                return !paths.isEmpty();

            if (ancestors.contains(normalized))
                return true;
            for (int slash = normalized.lastIndexOf('/'); slash >= 0; slash = normalized.lastIndexOf('/', slash - 1))
            {
                if (paths.contains(normalized.substring(0, slash)))
                    return true;
            }
            return false;
        }
    }

    private static final class GitProgress implements org.eclipse.jgit.lib.ProgressMonitor
    {
        private final IProgressMonitor monitor;

        GitProgress(IProgressMonitor monitor)
        {
            this.monitor = monitor;
        }

        @Override
        public void start(int totalTasks)
        {
            // The surrounding Eclipse Job owns the progress lifecycle.
        }

        @Override
        public void beginTask(String title, int totalWork)
        {
            monitor.subTask(title);
        }

        @Override
        public void update(int completed)
        {
            // Cancellation remains available even for repositories without a known work total.
        }

        @Override
        public void endTask()
        {
            // The surrounding Eclipse Job owns the progress lifecycle.
        }

        @Override
        public boolean isCancelled()
        {
            return monitor.isCanceled();
        }

        @Override
        public void showDuration(boolean enabled)
        {
            // EDT displays job progress.
        }
    }
}
