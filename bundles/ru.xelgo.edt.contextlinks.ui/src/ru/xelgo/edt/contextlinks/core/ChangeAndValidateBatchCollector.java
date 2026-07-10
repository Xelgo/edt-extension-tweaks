package ru.xelgo.edt.contextlinks.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.emf.common.util.URI;

import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;
import com._1c.g5.wiring.ServiceAccess;

import ru.xelgo.edt.contextlinks.ui.Messages;

/**
 * Collects extension methods whose text diverged from the base method.
 *
 * <p>The marker for this check carries {@code SU232} as its check id - not the
 * {@code bsl-legacy-check-module-extension} code used by the Xtext issue - and hands out an EObject
 * detached from any resource. Only {@code extraInfo} is usable: it holds the module URI and the
 * problem offset. Everything is therefore resolved from module text, which also keeps the collector
 * off the BSL scope services that once froze full builds.
 */
public final class ChangeAndValidateBatchCollector
{
    /** Check id under which EDT stores "method text differs from the base method". */
    public static final String CHECK_ID = "SU232"; //$NON-NLS-1$

    private ChangeAndValidateBatchCollector()
    {
        // Utility class.
    }

    /** Collects items for the given projects. Never throws on a single bad marker. */
    public static List<ChangeAndValidateBatchItem> collect(Collection<IProject> projects, IProgressMonitor monitor)
    {
        List<ChangeAndValidateBatchItem> items = new ArrayList<>();
        IMarkerManager markerManager = ServiceAccess.get(IMarkerManager.class);
        if (markerManager == null)
        {
            ContextLinks.logWarning("EDT Extension Tweaks batch merge: IMarkerManager is not available"); //$NON-NLS-1$
            return items;
        }

        SubMonitor progress = SubMonitor.convert(monitor, projects.size());
        Set<String> seen = new LinkedHashSet<>();
        for (IProject project : projects)
        {
            if (progress.isCanceled())
                throw new OperationCanceledException();

            progress.subTask(project.getName());
            collectProject(markerManager, project, items, seen);
            progress.worked(1);
        }
        return items;
    }

    /** Collects items for an explicit marker selection (from the problem view). */
    public static List<ChangeAndValidateBatchItem> collectFromMarkers(Collection<Marker> markers,
        IProgressMonitor monitor)
    {
        List<ChangeAndValidateBatchItem> items = new ArrayList<>();
        SubMonitor progress = SubMonitor.convert(monitor, markers.size());
        Set<String> seen = new LinkedHashSet<>();
        for (Marker marker : markers)
        {
            if (progress.isCanceled())
                throw new OperationCanceledException();

            if (CHECK_ID.equals(marker.getCheckId()))
                addItem(marker, items, seen);
            progress.worked(1);
        }
        return items;
    }

    private static void collectProject(IMarkerManager markerManager, IProject project,
        List<ChangeAndValidateBatchItem> items, Set<String> seen)
    {
        if (!project.isAccessible())
            return;

        try
        {
            MarkerFilter filter =
                MarkerFilter.createProjectFilter(project).combine(MarkerFilter.createCheckFilter(CHECK_ID));
            markerManager.markers(filter).forEach(marker -> addItem(marker, items, seen));
        }
        catch (RuntimeException e)
        {
            ContextLinks.logError("EDT Extension Tweaks batch merge: failed to read markers of " //$NON-NLS-1$
                + project.getName(), e);
        }
    }

    /** A bad marker never breaks the whole collection: it is skipped and logged. */
    private static void addItem(Marker marker, List<ChangeAndValidateBatchItem> items, Set<String> seen)
    {
        try
        {
            ChangeAndValidateBatchItem item = createItem(marker);
            if (item == null)
                return;

            // Two markers may point at the same method.
            String key = item.getExtensionFile().getFullPath() + "#" + item.getMethodOffset(); //$NON-NLS-1$
            if (seen.add(key))
                items.add(item);
        }
        catch (Exception e)
        {
            ContextLinks.logError("EDT Extension Tweaks batch merge: skipped marker " + marker.getLocation(), e); //$NON-NLS-1$
        }
    }

    private static ChangeAndValidateBatchItem createItem(Marker marker)
        throws CoreException, IOException
    {
        IExtraInfoMap extra = marker.getExtraInfo();
        if (extra == null)
            return null;

        String uriText = extra.get(StandardExtraInfo.TEXT_URI_TO_PROBLEM);
        int problemOffset = extra.getIntOrDefault(StandardExtraInfo.TEXT_OFFSET, -1);
        if (uriText == null || problemOffset < 0)
            return null;

        IFile extensionFile = toFile(uriText);
        if (extensionFile == null || !extensionFile.exists())
            return null;

        String extensionModuleText = read(extensionFile);
        BslMethodTextLocator.MethodText extensionMethod =
            BslMethodTextLocator.findMethodAt(extensionModuleText, problemOffset);
        if (extensionMethod == null)
            return null;

        List<String> warnings = new ArrayList<>(BslMethodTextLocator.validateDirectives(extensionMethod.text));

        String sourceText = readSourceMethodText(extensionFile, extensionMethod, warnings);
        if (sourceText == null)
            return null;

        ChangeAndValidateExtensionMergeBuilder.MergeTexts texts =
            ChangeAndValidateExtensionMergeBuilder.build(sourceText, extensionMethod.text);

        if (texts.suggestedText == null || texts.suggestedText.isBlank())
            warnings.add(Messages.ChangeAndValidateBatch_WarnEmptyResult);

        return new ChangeAndValidateBatchItem(extensionFile, extensionFile.getProject().getName(),
            modulePresentation(extensionFile), extensionMethod.methodName, sourceText, extensionMethod.text,
            texts.ancestorText, texts.suggestedText, extensionMethod.offset, extensionMethod.length, warnings);
    }

    /** Base method lives in the parent configuration, in a module with the same workspace-relative path. */
    private static String readSourceMethodText(IFile extensionFile, BslMethodTextLocator.MethodText extensionMethod,
        List<String> warnings)
        throws CoreException, IOException
    {
        IProject parentProject = parentProject(extensionFile.getProject());
        if (parentProject == null || !parentProject.isAccessible())
        {
            warnings.add(Messages.ChangeAndValidateBatch_WarnNoBaseProject);
            return null;
        }

        IPath relative = extensionFile.getProjectRelativePath();
        IFile sourceFile = parentProject.getFile(relative);
        if (!sourceFile.exists())
        {
            warnings.add(Messages.ChangeAndValidateBatch_WarnNoBaseModule);
            return null;
        }

        String sourceName = extensionMethod.sourceMethodName != null ? extensionMethod.sourceMethodName
            : extensionMethod.methodName;
        BslMethodTextLocator.MethodText sourceMethod = BslMethodTextLocator.findMethodByName(read(sourceFile),
            sourceName);
        if (sourceMethod == null)
        {
            warnings.add(Messages.ChangeAndValidateBatch_WarnNoBaseMethod);
            return null;
        }
        return sourceMethod.text;
    }

    private static IProject parentProject(IProject project)
    {
        IV8ProjectManager manager = ServiceAccess.get(IV8ProjectManager.class);
        if (manager == null)
            return null;

        IV8Project v8Project = manager.getProject(project);
        if (v8Project instanceof IDependentProject dependent)
            return dependent.getParentProject();
        return null;
    }

    private static IFile toFile(String uriText)
    {
        URI uri = URI.createURI(uriText);
        String platformPath = uri.trimFragment().toPlatformString(true);
        if (platformPath == null)
            return null;
        return ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(platformPath));
    }

    /** Presentation for the tree, e.g. {@code CommonModules/SomeModule}. */
    private static String modulePresentation(IFile file)
    {
        IPath path = file.getProjectRelativePath();
        // src/CommonModules/<name>/Module.bsl -> CommonModules/<name>
        if (path.segmentCount() >= 4 && "src".equals(path.segment(0))) //$NON-NLS-1$
            return path.segment(1) + "/" + path.segment(2); //$NON-NLS-1$
        return path.toString();
    }

    static String read(IFile file)
        throws CoreException, IOException
    {
        Charset charset = charsetOf(file);
        try (InputStream stream = file.getContents(true))
        {
            return new String(stream.readAllBytes(), charset);
        }
    }

    static Charset charsetOf(IFile file)
    {
        try
        {
            String name = file.getCharset();
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        }
        catch (CoreException | RuntimeException e)
        {
            return StandardCharsets.UTF_8;
        }
    }
}
