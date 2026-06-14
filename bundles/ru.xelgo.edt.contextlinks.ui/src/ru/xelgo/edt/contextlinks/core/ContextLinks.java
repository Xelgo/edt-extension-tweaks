package ru.xelgo.edt.contextlinks.core;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.common.util.URI;

import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Stores additional context source project names for an EDT project.
 */
public final class ContextLinks
{
    public static final String PLUGIN_ID = "ru.xelgo.edt.contextlinks.ui"; //$NON-NLS-1$
    private static final String V8_EXTERNAL_OBJECTS_NATURE = "com._1c.g5.v8.dt.core.V8ExternalObjectsNature"; //$NON-NLS-1$
    private static final boolean DEBUG_LOG_ENABLED = Boolean.getBoolean(PLUGIN_ID + ".debug"); //$NON-NLS-1$
    private static final boolean DISABLE_CONTEXT_DURING_BUILD = Boolean.parseBoolean(
        System.getProperty(PLUGIN_ID + ".disableDuringBuild", "true")); //$NON-NLS-1$ //$NON-NLS-2$

    private static final QualifiedName CONTEXT_PROJECTS =
        new QualifiedName(PLUGIN_ID, "contextProjects"); //$NON-NLS-1$
    private static final QualifiedName DISABLED_APPLICATION_UPDATE_PROJECTS =
        new QualifiedName(PLUGIN_ID, "disabledApplicationUpdateProjects"); //$NON-NLS-1$
    private static final ConcurrentHashMap<String, Set<String>> contextProjectNamesCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> recentBslAssistProjects = new ConcurrentHashMap<>();
    private static final Set<String> loggedBuildSkipKeys = ConcurrentHashMap.newKeySet();
    private static final Set<String> loggedInfoKeys = ConcurrentHashMap.newKeySet();
    private static final long BSL_ASSIST_WINDOW_MILLIS = 15_000L;
    private static volatile long recentBslAssistTimestamp;

    private ContextLinks()
    {
        // Utility class.
    }

    public static Set<String> getContextProjectNames(IProject project)
    {
        if (project == null)
        {
            logDebug("EDT Extension Tweaks DEBUG [settings.read.skip] project=NULL"); //$NON-NLS-1$
            return Set.of();
        }

        if (!project.isAccessible())
        {
            logDebug("EDT Extension Tweaks DEBUG [settings.read.skip] project=" + project.getName() //$NON-NLS-1$
                + " reason=not-accessible"); //$NON-NLS-1$
            return Set.of();
        }

        Set<String> cached = contextProjectNamesCache.get(project.getName());
        if (cached != null)
            return cached;

        try
        {
            String value = project.getPersistentProperty(CONTEXT_PROJECTS);
            Set<String> result = normalizeProjectNames(value);
            logDebug("EDT Extension Tweaks DEBUG [settings.read] project=" + project.getName() //$NON-NLS-1$
                + " links=" + result + " raw=" //$NON-NLS-1$ //$NON-NLS-2$
                + (value == null || value.isBlank() ? "NULL_OR_BLANK" : value.replace('\n', '|'))); //$NON-NLS-1$
            contextProjectNamesCache.put(project.getName(), result);
            return result;
        }
        catch (CoreException e)
        {
            logWarning("Failed to read EDT context links for " + project.getName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return Set.of();
        }
    }

    public static void setContextProjectNames(IProject project, Set<String> names)
        throws CoreException
    {
        if (project == null)
        {
            logDebug("EDT Extension Tweaks DEBUG [settings.save.skip] project=NULL names=" + names); //$NON-NLS-1$
            return;
        }

        if (!project.isAccessible())
        {
            logDebug("EDT Extension Tweaks DEBUG [settings.save.skip] project=" + project.getName() //$NON-NLS-1$
                + " reason=not-accessible names=" + names); //$NON-NLS-1$
            return;
        }

        Set<String> normalizedNames = normalizeProjectNames(names);
        String value = normalizedNames.stream().collect(Collectors.joining("\n")); //$NON-NLS-1$
        project.setPersistentProperty(CONTEXT_PROJECTS, value.isEmpty() ? null : value);
        if (normalizedNames.isEmpty())
            contextProjectNamesCache.remove(project.getName());
        else
            contextProjectNamesCache.put(project.getName(), normalizedNames);
        logDebug("Saved EDT context links for " + project.getName() + ": " + normalizedNames); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static Set<String> getDisabledApplicationUpdateProjectNames(IProject applicationProject)
    {
        return getProjectNameSet(applicationProject, DISABLED_APPLICATION_UPDATE_PROJECTS,
            "application.update.disabled.read"); //$NON-NLS-1$
    }

    public static void setDisabledApplicationUpdateProjectNames(IProject applicationProject, Set<String> names)
        throws CoreException
    {
        setProjectNameSet(applicationProject, DISABLED_APPLICATION_UPDATE_PROJECTS, names,
            "application.update.disabled.save"); //$NON-NLS-1$
    }

    public static boolean isApplicationUpdateDisabled(IProject applicationProject, IProject updateProject)
    {
        if (applicationProject == null || updateProject == null)
            return false;
        return getDisabledApplicationUpdateProjectNames(applicationProject).contains(updateProject.getName());
    }

    public static boolean isApplicationUpdateDisabled(IProject updateProject)
    {
        IProject applicationProject = getApplicationProject(updateProject);
        return isApplicationUpdateDisabled(applicationProject, updateProject);
    }

    public static IProject getApplicationProject(IProject updateProject)
    {
        if (updateProject == null || !updateProject.isAccessible())
            return updateProject;

        IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
        if (projectManager == null)
            return updateProject;

        IV8Project v8Project = projectManager.getProject(updateProject);
        if (v8Project instanceof IDependentProject)
        {
            IProject parentProject = ((IDependentProject)v8Project).getParentProject();
            return parentProject != null ? parentProject : updateProject;
        }
        return updateProject;
    }

    public static IProject[] getApplicationUpdateCandidateProjects(IProject applicationProject)
    {
        if (applicationProject == null || !applicationProject.isAccessible())
            return new IProject[0];

        IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
        if (projectManager == null)
            return new IProject[] { applicationProject };

        LinkedHashSet<IProject> result = new LinkedHashSet<>();
        if (projectManager.getProject(applicationProject) != null)
            result.add(applicationProject);

        Collection<IExtensionProject> extensions = projectManager.getProjects(IExtensionProject.class);
        IDependentProject.getDependent(applicationProject, extensions).stream()
            .filter(project -> project != null && project.isAccessible())
            .forEach(result::add);

        return result.toArray(IProject[]::new);
    }

    public static boolean shouldSkipContextExtensionDuringBuild(String feature)
    {
        if (!DISABLE_CONTEXT_DURING_BUILD)
            return false;

        Thread thread = Thread.currentThread();
        if (isBslBuildSensitiveFeature(feature) && isBackgroundThreadName(thread.getName()))
        {
            logBuildSkip(feature, new BuildStackMatch(thread.getName(), "background-thread")); //$NON-NLS-1$
            return true;
        }

        BuildStackMatch match = findBuildStackMatch();
        if (match == null)
            return false;

        logBuildSkip(feature, match);
        return true;
    }

    public static boolean shouldSkipBslContextExtension(String feature)
    {
        return shouldSkipBslContextExtension(feature, null);
    }

    public static boolean shouldSkipBslContextExtension(String feature, IProject project)
    {
        if (isInteractiveBslAssistRequest())
        {
            rememberBslAssistProject(project);
            return false;
        }

        if (isRecentBslAssistContinuation(project))
            return false;

        if (shouldSkipContextExtensionDuringBuild(feature))
            return true;

        Thread thread = Thread.currentThread();
        logBuildSkip(feature, new BuildStackMatch(thread.getName(), "non-interactive-bsl")); //$NON-NLS-1$
        return true;
    }

    private static void rememberBslAssistProject(IProject project)
    {
        recentBslAssistTimestamp = System.currentTimeMillis();
        if (project != null && project.isAccessible())
            recentBslAssistProjects.put(project.getName(), recentBslAssistTimestamp);
    }

    private static boolean isRecentBslAssistContinuation(IProject project)
    {
        String threadName = Thread.currentThread().getName();
        if (!isAssistContinuationThread(threadName))
            return false;

        if (project == null || !project.isAccessible())
            return isRecentGlobalBslAssistContinuation();

        Long timestamp = recentBslAssistProjects.get(project.getName());
        if (timestamp == null)
            return isRecentGlobalBslAssistContinuation();

        long age = System.currentTimeMillis() - timestamp.longValue();
        if (age >= 0 && age <= BSL_ASSIST_WINDOW_MILLIS)
            return true;

        recentBslAssistProjects.remove(project.getName(), timestamp);
        return isRecentGlobalBslAssistContinuation();
    }

    private static boolean isRecentGlobalBslAssistContinuation()
    {
        long timestamp = recentBslAssistTimestamp;
        if (timestamp == 0)
            return false;

        long age = System.currentTimeMillis() - timestamp;
        return age >= 0 && age <= BSL_ASSIST_WINDOW_MILLIS;
    }

    private static boolean isAssistContinuationThread(String threadName)
    {
        if (threadName == null)
            return false;

        return threadName.startsWith("ForkJoinPool-") //$NON-NLS-1$
            || threadName.startsWith("ForkJoinPool.commonPool-"); //$NON-NLS-1$
    }

    private static void logBuildSkip(String feature, BuildStackMatch match)
    {
        String key = feature + "|" + match.threadName + "|" + match.frame; //$NON-NLS-1$ //$NON-NLS-2$
        if (loggedBuildSkipKeys.add(key))
        {
            logInfo("EDT Extension Tweaks [build.skip] feature=" + feature //$NON-NLS-1$
                + " thread=" + match.threadName + " frame=" + match.frame //$NON-NLS-1$ //$NON-NLS-2$
                + " memory=" + memorySummary()); //$NON-NLS-1$
        }
    }

    public static void logScopeExtension(String feature, IProject project, Object details)
    {
        String projectName = project != null ? project.getName() : "NULL"; //$NON-NLS-1$
        String threadName = Thread.currentThread().getName();
        String key = "scope.extend|" + feature + "|" + projectName + "|" + threadName; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        logInfoOnce(key, "EDT Extension Tweaks [scope.extend] feature=" + feature //$NON-NLS-1$
            + " project=" + projectName //$NON-NLS-1$
            + " thread=" + threadName //$NON-NLS-1$
            + " details=" + details //$NON-NLS-1$
            + " memory=" + memorySummary()); //$NON-NLS-1$
    }

    public static String describeWorkspaceSettings()
    {
        return Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
            .filter(IProject::isAccessible)
            .map(project -> project.getName() + "=" + getContextProjectNames(project)) //$NON-NLS-1$
            .collect(Collectors.joining("; ")); //$NON-NLS-1$
    }

    private static BuildStackMatch findBuildStackMatch()
    {
        Thread thread = Thread.currentThread();
        String threadName = thread.getName();
        if (isBuildThreadName(threadName))
            return new BuildStackMatch(threadName, "thread-name"); //$NON-NLS-1$

        for (StackTraceElement element : thread.getStackTrace())
        {
            String className = element.getClassName();
            if (isBuildStackClass(className))
                return new BuildStackMatch(threadName, className + "." + element.getMethodName()); //$NON-NLS-1$
        }
        return null;
    }

    private static boolean isBuildThreadName(String threadName)
    {
        if (threadName == null)
            return false;

        return threadName.startsWith("LCBuilderState") //$NON-NLS-1$
            || threadName.contains("Builder") //$NON-NLS-1$
            || threadName.contains("build"); //$NON-NLS-1$
    }

    private static boolean isBackgroundThreadName(String threadName)
    {
        if (threadName == null)
            return false;

        return threadName.startsWith("Worker-") //$NON-NLS-1$
            || threadName.startsWith("ForkJoinPool-") //$NON-NLS-1$
            || threadName.startsWith("derived_data_executor_") //$NON-NLS-1$
            || threadName.startsWith("LCBuilderState-") //$NON-NLS-1$
            || threadName.contains("Xtext") //$NON-NLS-1$
            || threadName.contains("Проверка Xтекст") //$NON-NLS-1$
            || threadName.startsWith("AEF 2.0 Thread-"); //$NON-NLS-1$
    }

    private static boolean isBslBuildSensitiveFeature(String feature)
    {
        return feature != null && (feature.startsWith("bsl-") || feature.startsWith("module-context-")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isInteractiveBslAssistRequest()
    {
        String threadName = Thread.currentThread().getName();
        if (containsAssistMarker(threadName))
            return true;

        for (StackTraceElement element : Thread.currentThread().getStackTrace())
        {
            String className = element.getClassName();
            if (containsAssistMarker(className))
                return true;
        }
        return false;
    }

    private static boolean containsAssistMarker(String value)
    {
        if (value == null)
            return false;

        String lowerValue = value.toLowerCase();
        return lowerValue.contains("contentassist") //$NON-NLS-1$
            || lowerValue.contains("proposal") //$NON-NLS-1$
            || lowerValue.contains("completion"); //$NON-NLS-1$
    }

    private static boolean isBuildStackClass(String className)
    {
        if (className == null)
            return false;

        return className.startsWith("org.eclipse.core.internal.events.BuildManager") //$NON-NLS-1$
            || className.startsWith("org.eclipse.core.resources.IncrementalProjectBuilder") //$NON-NLS-1$
            || className.startsWith("org.eclipse.xtext.builder.") //$NON-NLS-1$
            || className.contains(".builder.") //$NON-NLS-1$
            || className.contains(".build.") //$NON-NLS-1$
            || className.contains("LCBuilder") //$NON-NLS-1$
            || className.contains("ResourceDescription") //$NON-NLS-1$
            || className.equals("com._1c.g5.v8.dt.bsl.typesystem.ExportMethodTypeProvider"); //$NON-NLS-1$
    }

    private static Set<String> getProjectNameSet(IProject project, QualifiedName property, String tag)
    {
        if (project == null || !project.isAccessible())
            return Set.of();

        try
        {
            String value = project.getPersistentProperty(property);
            if (value == null || value.isBlank())
            {
                logDebug("EDT Extension Tweaks DEBUG [" + tag + "] project=" + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + " names=[] raw=NULL_OR_BLANK"); //$NON-NLS-1$
                return Set.of();
            }

            Set<String> result = Arrays.stream(value.split("\\R")) //$NON-NLS-1$
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
            logDebug("EDT Extension Tweaks DEBUG [" + tag + "] project=" + project.getName() //$NON-NLS-1$ //$NON-NLS-2$
                + " names=" + result + " raw=" + value.replace('\n', '|')); //$NON-NLS-1$ //$NON-NLS-2$
            return result;
        }
        catch (CoreException e)
        {
            logWarning("Failed to read EDT Extension Tweaks project setting for " + project.getName() //$NON-NLS-1$
                + ": " + e.getMessage()); //$NON-NLS-1$
            return Set.of();
        }
    }

    private static Set<String> normalizeProjectNames(String value)
    {
        if (value == null || value.isBlank())
            return Set.of();

        Set<String> result = Arrays.stream(value.split("\\R")) //$NON-NLS-1$
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> normalizeProjectNames(Set<String> names)
    {
        if (names == null || names.isEmpty())
            return Set.of();

        Set<String> result = names.stream()
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return result.isEmpty() ? Set.of() : Collections.unmodifiableSet(result);
    }

    private static void setProjectNameSet(IProject project, QualifiedName property, Set<String> names, String tag)
        throws CoreException
    {
        if (project == null || !project.isAccessible())
            return;

        String value = names.stream()
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .distinct()
            .collect(Collectors.joining("\n")); //$NON-NLS-1$
        project.setPersistentProperty(property, value.isEmpty() ? null : value);
        logDebug("EDT Extension Tweaks DEBUG [" + tag + "] project=" + project.getName() + " names=" + names); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    public static boolean isExtensionProject(IProject project)
    {
        if (project == null || !project.isAccessible())
            return false;

        IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
        if (projectManager == null)
        {
            logDebug("EDT Extension Tweaks DEBUG [project.kind] project=" + project.getName() //$NON-NLS-1$
                + " result=false reason=project-manager-null"); //$NON-NLS-1$
            return false;
        }

        IV8Project v8Project = projectManager.getProject(project);
        boolean result = v8Project instanceof IExtensionProject;
        logDebug("EDT Extension Tweaks DEBUG [project.kind] project=" + project.getName() //$NON-NLS-1$
            + " v8Project=" + (v8Project != null ? v8Project.getClass().getName() : "NULL") //$NON-NLS-1$ //$NON-NLS-2$
            + " extension=" + result); //$NON-NLS-1$
        return result;
    }

    public static boolean isExternalObjectsProject(IProject project)
    {
        if (project == null || !project.isAccessible())
            return false;

        try
        {
            boolean result = project.hasNature(V8_EXTERNAL_OBJECTS_NATURE);
            logDebug("EDT Extension Tweaks DEBUG [project.kind.external] project=" + project.getName() //$NON-NLS-1$
                + " externalObjects=" + result); //$NON-NLS-1$
            return result;
        }
        catch (CoreException e)
        {
            logWarning("Failed to detect EDT external objects project " + project.getName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    public static boolean isContextConfigurableProject(IProject project)
    {
        boolean extension = isExtensionProject(project);
        boolean externalObjects = !extension && isExternalObjectsProject(project);
        boolean result = extension || externalObjects;
        logDebug("EDT Extension Tweaks DEBUG [project.kind.configurable] project=" //$NON-NLS-1$
            + (project != null ? project.getName() : "NULL") //$NON-NLS-1$
            + " extension=" + extension + " externalObjects=" + externalObjects + " result=" + result); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return result;
    }

    public static IProject getProject(URI uri)
    {
        if (uri == null)
        {
            logDebug("EDT Extension Tweaks DEBUG [project.from.uri] uri=NULL result=NULL"); //$NON-NLS-1$
            return null;
        }

        if ("bm".equals(uri.scheme())) //$NON-NLS-1$
        {
            String projectName = uri.authority();
            if (projectName == null || projectName.isBlank())
                projectName = uri.segmentCount() > 0 ? uri.segment(0) : null;

            IProject project = projectName != null && !projectName.isBlank()
                ? ResourcesPlugin.getWorkspace().getRoot().getProject(projectName)
                : null;
            logDebug("EDT Extension Tweaks DEBUG [project.from.uri] uri=" + uri //$NON-NLS-1$
                + " bmProject=" + (project != null ? project.getName() : "NULL")); //$NON-NLS-1$ //$NON-NLS-2$
            return project;
        }

        if (!uri.isPlatformResource())
        {
            logDebug("EDT Extension Tweaks DEBUG [project.from.uri] uri=" + uri //$NON-NLS-1$
                + " result=NULL reason=not-platform-resource"); //$NON-NLS-1$
            return null;
        }

        String platformPath = uri.toPlatformString(true);
        if (platformPath == null || platformPath.isEmpty())
        {
            logDebug("EDT Extension Tweaks DEBUG [project.from.uri] uri=" + uri //$NON-NLS-1$
                + " result=NULL reason=empty-platform-path"); //$NON-NLS-1$
            return null;
        }

        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(IPath.fromOSString(platformPath));
        if (resource != null)
        {
            IProject project = resource.getProject();
            logDebug("EDT Extension Tweaks DEBUG [project.from.uri] uri=" + uri //$NON-NLS-1$
                + " resource=" + resource.getFullPath() + " project=" //$NON-NLS-1$ //$NON-NLS-2$
                + (project != null ? project.getName() : "NULL")); //$NON-NLS-1$
            return project;
        }

        String projectName = Arrays.stream(platformPath.split("/")) //$NON-NLS-1$
            .filter(segment -> !segment.isEmpty())
            .findFirst()
            .orElse(null);
        IProject project = projectName != null
            ? ResourcesPlugin.getWorkspace().getRoot().getProject(projectName)
            : null;
        logDebug("EDT Extension Tweaks DEBUG [project.from.uri] uri=" + uri + " fallbackProject=" //$NON-NLS-1$ //$NON-NLS-2$
            + (project != null ? project.getName() : "NULL")); //$NON-NLS-1$
        return project;
    }

    public static void logError(String message, Throwable throwable)
    {
        Platform.getLog(ContextLinks.class)
            .log(new Status(IStatus.ERROR, PLUGIN_ID, message, throwable));
    }

    public static void logWarning(String message)
    {
        Platform.getLog(ContextLinks.class)
            .log(new Status(IStatus.WARNING, PLUGIN_ID, message));
    }

    public static void logInfo(String message)
    {
        Platform.getLog(ContextLinks.class)
            .log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }

    public static void logInfoOnce(String key, String message)
    {
        if (loggedInfoKeys.add(key))
            logInfo(message);
    }

    public static boolean isDebugLoggingEnabled()
    {
        return DEBUG_LOG_ENABLED;
    }

    public static void logDebug(String message)
    {
        if (!DEBUG_LOG_ENABLED)
            return;

        Platform.getLog(ContextLinks.class)
            .log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }

    private static String memorySummary()
    {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        return used + "/" + runtime.maxMemory(); //$NON-NLS-1$
    }

    private static final class BuildStackMatch
    {
        private final String threadName;
        private final String frame;

        BuildStackMatch(String threadName, String frame)
        {
            this.threadName = threadName;
            this.frame = frame;
        }
    }
}
