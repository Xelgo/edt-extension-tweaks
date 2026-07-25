package ru.xelgo.edt.contextlinks.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

import ru.xelgo.edt.contextlinks.ui.Messages;

/**
 * Collects extension methods whose text diverged from the base method.
 *
 * <p>The marker hands out an EObject detached from any resource. Only {@code extraInfo} is usable:
 * it holds the module URI and the problem offset. Everything is therefore resolved from module
 * text, which also keeps the collector off the BSL scope services that once froze full builds.
 */
public final class ChangeAndValidateBatchCollector
{
    /**
     * Symbolic ids of the checks that report a diverged ChangeAndValidate method.
     *
     * <p>Only these are stable. The id EDT actually stores in {@link Marker#getCheckId()} depends on
     * which engine produced the marker: the legacy BSL validator stores the Xtext issue code, while
     * the e1c check framework stores a <em>short uid</em> ({@code SU232} and the like) that
     * {@link ICheckRepository#getShortUid} hands out at runtime. A short uid is not a constant - it
     * shifts when EDT is updated or checks are added, and then silently starts naming a foreign
     * check - so it is resolved per project instead of being hard-coded.
     */
    private static final List<String> SYMBOLIC_CHECK_IDS = List.of("bsl-legacy-check-module-extension", //$NON-NLS-1$
        "method-text-has-differences-base-method", //$NON-NLS-1$
        "not-use-annotation-form-event-handlers"); //$NON-NLS-1$

    private ChangeAndValidateBatchCollector()
    {
        // Utility class.
    }

    /** Marker check ids that mean "diverged ChangeAndValidate method" in the given project. */
    public static Set<String> checkIds(IProject project)
    {
        Set<String> ids = new LinkedHashSet<>(SYMBOLIC_CHECK_IDS);
        ICheckRepository repository = ServiceAccess.get(ICheckRepository.class);
        if (repository == null || project == null || !project.isAccessible())
            return ids;

        for (String symbolicId : SYMBOLIC_CHECK_IDS)
        {
            try
            {
                Set<CheckUid> uids = repository.getCheckUidForCheckId(symbolicId, project);
                if (uids == null)
                    continue;

                for (CheckUid uid : uids)
                {
                    String shortUid = repository.getShortUid(uid, project);
                    if (shortUid != null && !shortUid.isBlank())
                        ids.add(shortUid);
                }
            }
            catch (RuntimeException e)
            {
                ContextLinks.logError("EDT Extension Tweaks batch merge: failed to resolve check id " //$NON-NLS-1$
                    + symbolicId, e);
            }
        }
        return ids;
    }

    /** Keeps only the markers of our check; ids are resolved per owning project. */
    public static List<Marker> filterSupported(Collection<Marker> markers)
    {
        List<Marker> supported = new ArrayList<>();
        Map<IProject, Set<String>> idsByProject = new HashMap<>();
        for (Marker marker : markers)
        {
            if (marker == null)
                continue;

            Set<String> ids =
                idsByProject.computeIfAbsent(marker.getProject(), ChangeAndValidateBatchCollector::checkIds);
            if (ids.contains(marker.getCheckId()))
                supported.add(marker);
        }
        return supported;
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
        List<Marker> supported = filterSupported(markers);
        SubMonitor progress = SubMonitor.convert(monitor, supported.size());
        Set<String> seen = new LinkedHashSet<>();
        for (Marker marker : supported)
        {
            if (progress.isCanceled())
                throw new OperationCanceledException();

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

        // One query per id: a filter carries a single check id, and the same check is registered by
        // several contributors, each with its own short uid.
        for (String checkId : checkIds(project))
        {
            try
            {
                MarkerFilter filter =
                    MarkerFilter.createProjectFilter(project).combine(MarkerFilter.createCheckFilter(checkId));
                markerManager.markers(filter).forEach(marker -> addItem(marker, items, seen));
            }
            catch (RuntimeException e)
            {
                ContextLinks.logError("EDT Extension Tweaks batch merge: failed to read markers of " //$NON-NLS-1$
                    + project.getName(), e);
            }
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

        // The check also reports signature mismatches and unknown base method names, and a check id
        // that no longer names our check matches foreign markers outright. Only a method that
        // actually carries the directive can be merged against a base method.
        if (!extensionMethod.changeAndValidate)
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
