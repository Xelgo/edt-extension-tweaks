package ru.xelgo.edt.contextlinks.core;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
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
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Stores additional context source project names for an EDT project.
 */
public final class ContextLinks
{
    public static final String PLUGIN_ID = "ru.xelgo.edt.contextlinks.ui"; //$NON-NLS-1$
    private static final boolean DEBUG_LOG_ENABLED = Boolean.getBoolean(PLUGIN_ID + ".debug"); //$NON-NLS-1$

    private static final QualifiedName CONTEXT_PROJECTS =
        new QualifiedName(PLUGIN_ID, "contextProjects"); //$NON-NLS-1$

    private ContextLinks()
    {
        // Utility class.
    }

    public static Set<String> getContextProjectNames(IProject project)
    {
        if (project == null)
        {
            logDebug("EDT Context Links DEBUG [settings.read.skip] project=NULL"); //$NON-NLS-1$
            return Set.of();
        }

        if (!project.isAccessible())
        {
            logDebug("EDT Context Links DEBUG [settings.read.skip] project=" + project.getName() //$NON-NLS-1$
                + " reason=not-accessible"); //$NON-NLS-1$
            return Set.of();
        }

        try
        {
            String value = project.getPersistentProperty(CONTEXT_PROJECTS);
            if (value == null || value.isBlank())
            {
                logDebug("EDT Context Links DEBUG [settings.read] project=" + project.getName() //$NON-NLS-1$
                    + " links=[] raw=NULL_OR_BLANK"); //$NON-NLS-1$
                return Set.of();
            }

            Set<String> result = Arrays.stream(value.split("\\R")) //$NON-NLS-1$
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
            logDebug("EDT Context Links DEBUG [settings.read] project=" + project.getName() //$NON-NLS-1$
                + " links=" + result + " raw=" + value.replace('\n', '|')); //$NON-NLS-1$ //$NON-NLS-2$
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
            logDebug("EDT Context Links DEBUG [settings.save.skip] project=NULL names=" + names); //$NON-NLS-1$
            return;
        }

        if (!project.isAccessible())
        {
            logDebug("EDT Context Links DEBUG [settings.save.skip] project=" + project.getName() //$NON-NLS-1$
                + " reason=not-accessible names=" + names); //$NON-NLS-1$
            return;
        }

        String value = names.stream()
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .distinct()
            .collect(Collectors.joining("\n")); //$NON-NLS-1$
        project.setPersistentProperty(CONTEXT_PROJECTS, value.isEmpty() ? null : value);
        logDebug("Saved EDT context links for " + project.getName() + ": " + names); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static String describeWorkspaceSettings()
    {
        return Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
            .filter(IProject::isAccessible)
            .map(project -> project.getName() + "=" + getContextProjectNames(project)) //$NON-NLS-1$
            .collect(Collectors.joining("; ")); //$NON-NLS-1$
    }

    public static boolean isExtensionProject(IProject project)
    {
        if (project == null || !project.isAccessible())
            return false;

        IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
        if (projectManager == null)
        {
            logDebug("EDT Context Links DEBUG [project.kind] project=" + project.getName() //$NON-NLS-1$
                + " result=false reason=project-manager-null"); //$NON-NLS-1$
            return false;
        }

        IV8Project v8Project = projectManager.getProject(project);
        boolean result = v8Project instanceof IExtensionProject;
        logDebug("EDT Context Links DEBUG [project.kind] project=" + project.getName() //$NON-NLS-1$
            + " v8Project=" + (v8Project != null ? v8Project.getClass().getName() : "NULL") //$NON-NLS-1$ //$NON-NLS-2$
            + " extension=" + result); //$NON-NLS-1$
        return result;
    }

    public static IProject getProject(URI uri)
    {
        if (uri == null)
        {
            logDebug("EDT Context Links DEBUG [project.from.uri] uri=NULL result=NULL"); //$NON-NLS-1$
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
            logDebug("EDT Context Links DEBUG [project.from.uri] uri=" + uri //$NON-NLS-1$
                + " bmProject=" + (project != null ? project.getName() : "NULL")); //$NON-NLS-1$ //$NON-NLS-2$
            return project;
        }

        if (!uri.isPlatformResource())
        {
            logDebug("EDT Context Links DEBUG [project.from.uri] uri=" + uri //$NON-NLS-1$
                + " result=NULL reason=not-platform-resource"); //$NON-NLS-1$
            return null;
        }

        String platformPath = uri.toPlatformString(true);
        if (platformPath == null || platformPath.isEmpty())
        {
            logDebug("EDT Context Links DEBUG [project.from.uri] uri=" + uri //$NON-NLS-1$
                + " result=NULL reason=empty-platform-path"); //$NON-NLS-1$
            return null;
        }

        IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(IPath.fromOSString(platformPath));
        if (resource != null)
        {
            IProject project = resource.getProject();
            logDebug("EDT Context Links DEBUG [project.from.uri] uri=" + uri //$NON-NLS-1$
                + " resource=" + resource.getFullPath() + " project=" //$NON-NLS-1$ //$NON-NLS-2$
                + (project != null ? project.getName() : "NULL")); //$NON-NLS-1$
            return project;
        }

        String[] segments = platformPath.split("/"); //$NON-NLS-1$
        IProject project = segments.length > 0 && !segments[0].isEmpty()
            ? ResourcesPlugin.getWorkspace().getRoot().getProject(segments[0])
            : null;
        logDebug("EDT Context Links DEBUG [project.from.uri] uri=" + uri + " fallbackProject=" //$NON-NLS-1$ //$NON-NLS-2$
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
}
