package ru.xelgo.edt.contextlinks.core;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;

/**
 * Stores additional context source project names for an EDT project.
 */
public final class ContextLinks
{
    public static final String PLUGIN_ID = "ru.xelgo.edt.contextlinks.ui"; //$NON-NLS-1$

    private static final QualifiedName CONTEXT_PROJECTS =
        new QualifiedName(PLUGIN_ID, "contextProjects"); //$NON-NLS-1$

    private ContextLinks()
    {
        // Utility class.
    }

    public static Set<String> getContextProjectNames(IProject project)
    {
        if (project == null || !project.isAccessible())
            return Set.of();

        try
        {
            String value = project.getPersistentProperty(CONTEXT_PROJECTS);
            if (value == null || value.isBlank())
                return Set.of();

            return Arrays.stream(value.split("\\R")) //$NON-NLS-1$
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
        if (project == null || !project.isAccessible())
            return;

        String value = names.stream()
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .distinct()
            .collect(Collectors.joining("\n")); //$NON-NLS-1$
        project.setPersistentProperty(CONTEXT_PROJECTS, value.isEmpty() ? null : value);
        logWarning("Saved EDT context links for " + project.getName() + ": " + names); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static String describeWorkspaceSettings()
    {
        return Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
            .filter(IProject::isAccessible)
            .map(project -> project.getName() + "=" + getContextProjectNames(project)) //$NON-NLS-1$
            .collect(Collectors.joining("; ")); //$NON-NLS-1$
    }

    public static void logWarning(String message)
    {
        Platform.getLog(ContextLinks.class)
            .log(new Status(IStatus.WARNING, PLUGIN_ID, message));
    }

    public static void logDebug(String message)
    {
        Platform.getLog(ContextLinks.class)
            .log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }
}
