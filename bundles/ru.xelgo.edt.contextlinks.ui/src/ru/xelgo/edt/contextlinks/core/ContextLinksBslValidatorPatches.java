package ru.xelgo.edt.contextlinks.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.common.IModuleExtensionService;
import com._1c.g5.v8.dt.bsl.common.IModuleExtensionServiceProvider;
import com._1c.g5.v8.dt.bsl.common.Symbols;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.bsl.util.BslUtil;

/**
 * Helper methods used by the custom BSL validator.
 */
public final class ContextLinksBslValidatorPatches
{
    private static final int LOG_LIMIT = Integer.getInteger(
        ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.validatorLogLimit", 20); //$NON-NLS-1$
    private static final int MAX_CACHE_ENTRIES = Integer.getInteger(
        ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.validatorSourceCacheEntries", 256); //$NON-NLS-1$
    private static final long SLOW_COMPARISON_LOG_MILLIS = Long.getLong(
        ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.validatorSlowComparisonMillis", 250L); //$NON-NLS-1$
    private static final ConcurrentHashMap<String, CachedSource> sourceCache = new ConcurrentHashMap<>();
    private static final AtomicInteger loggedComparisons = new AtomicInteger();
    private static final AtomicInteger loggedDirtyMethods = new AtomicInteger();
    private static final AtomicInteger loggedFailures = new AtomicInteger();
    private static final AtomicInteger loggedValidationEntries = new AtomicInteger();
    private static final AtomicInteger loggedMethodContentCalls = new AtomicInteger();
    private static final AtomicInteger loggedSlowComparisons = new AtomicInteger();
    private static final AtomicInteger loggedSourceMethodCalls = new AtomicInteger();

    private ContextLinksBslValidatorPatches()
    {
        // Utility class.
    }

    public static Collection<String> getMethodContent(Method method)
    {
        if (method == null)
            return Collections.emptyList();

        INode signatureLastNode = BslUtil.getMethodSignatureLastNode(method);
        if (signatureLastNode == null)
            return Collections.emptyList();

        ICompositeNode methodNode = NodeModelUtils.findActualNodeFor(method);
        if (methodNode == null)
            return Collections.emptyList();

        String methodText = methodNode.getText();
        String rawMethodText = getRawDirtyMethodText(method, methodNode, methodText.length());
        logMethodContentCall(method, rawMethodText != null);
        if (rawMethodText != null)
        {
            methodText = rawMethodText;
            logDirtyMethod(method);
        }

        int bodyOffset = signatureLastNode.getTotalEndOffset() - methodNode.getTotalOffset();
        if (bodyOffset < 0 || bodyOffset > methodText.length())
            return Collections.emptyList();

        return toContentLines(methodText.substring(bodyOffset));
    }

    public static boolean hasDirtyPreprocessor(Method method)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return false;

        if (method == null)
            return false;

        ICompositeNode methodNode = NodeModelUtils.findActualNodeFor(method);
        if (methodNode == null)
            return false;

        return getRawDirtyMethodText(method, methodNode, methodNode.getText().length()) != null;
    }

    public static boolean mayHaveDirtyPreprocessor(Method method)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return false;

        if (method == null)
            return false;

        ICompositeNode methodNode = NodeModelUtils.findActualNodeFor(method);
        if (methodNode == null)
            return false;

        String methodText = methodNode.getText();
        return methodText.indexOf("//ETW") >= 0 || methodText.indexOf('#') >= 0; //$NON-NLS-1$
    }

    public static boolean hasOnlyDirtyPreprocessorDifference(Method method)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return false;

        ComparisonResult comparison = compareDirtyPreprocessorMethod(method);
        return comparison == ComparisonResult.DIRTY_ONLY;
    }

    public static boolean hasDirtyPreprocessorMeaningfulDifference(Method method)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return false;

        ComparisonResult comparison = compareDirtyPreprocessorMethod(method);
        return comparison == ComparisonResult.MEANINGFUL_DIFFERENCE;
    }

    public static void logDirtyValidationProbe(Method method, String phase)
    {
        logValidationEntry(method, phase);
    }

    private static ComparisonResult compareDirtyPreprocessorMethod(Method method)
    {
        if (!hasDirtyPreprocessor(method))
        {
            logComparison(method, false, "no-raw-dirty-method", Collections.emptyList(), Collections.emptyList()); //$NON-NLS-1$
            return ComparisonResult.NOT_APPLICABLE;
        }

        logValidationEntry(method, "compare.start"); //$NON-NLS-1$
        long started = System.nanoTime();
        ComparisonResult result = compareDirtyPreprocessorMethodUncached(method);
        logComparisonElapsed(method, result, started);
        return result;
    }

    private static ComparisonResult compareDirtyPreprocessorMethodUncached(Method method)
    {
        IModuleExtensionService service =
            IModuleExtensionServiceProvider.INSTANCE.getModuleExtensionService();
        if (service == null)
        {
            logComparison(method, false, "no-module-extension-service", Collections.emptyList(), Collections.emptyList()); //$NON-NLS-1$
            return ComparisonResult.NOT_APPLICABLE;
        }

        try
        {
            Collection<String> extensionContent = getChangeAndValidateComparableContent(method);
            if (!hasMethodEnd(extensionContent))
            {
                logComparison(method, false, "incomplete-extension-method-content", extensionContent, //$NON-NLS-1$
                    Collections.emptyList());
                return ComparisonResult.NOT_APPLICABLE;
            }

            long sourceMethodStarted = System.nanoTime();
            Map<Pragma, Method> sourceMethods = service.getSourceMethod(method);
            logSourceMethodCall(method, sourceMethodStarted, sourceMethods);
            if (sourceMethods == null || sourceMethods.isEmpty())
            {
                logComparison(method, false, "no-source-methods", extensionContent, Collections.emptyList()); //$NON-NLS-1$
                return ComparisonResult.NOT_APPLICABLE;
            }

            boolean sawChangeAndValidateSource = false;
            Collection<String> firstSourceContent = Collections.emptyList();
            for (Map.Entry<Pragma, Method> entry : sourceMethods.entrySet())
            {
                if (!isChangeAndValidatePragma(entry.getKey()) || entry.getValue() == null)
                    continue;

                sawChangeAndValidateSource = true;
                Collection<String> sourceContent = getMethodContent(entry.getValue());
                if (firstSourceContent.isEmpty())
                    firstSourceContent = sourceContent;
                if (extensionContent.equals(sourceContent))
                {
                    logComparison(method, true, "dirty-only", extensionContent, sourceContent); //$NON-NLS-1$
                    return ComparisonResult.DIRTY_ONLY;
                }

                logComparison(method, false, "content-diff", extensionContent, sourceContent); //$NON-NLS-1$
            }

            if (!sawChangeAndValidateSource)
            {
                logComparison(method, false, "no-change-and-validate-source", extensionContent, Collections.emptyList()); //$NON-NLS-1$
                return ComparisonResult.NOT_APPLICABLE;
            }

            logComparison(method, false, "meaningful-diff", extensionContent, firstSourceContent); //$NON-NLS-1$
            return ComparisonResult.MEANINGFUL_DIFFERENCE;
        }
        catch (RuntimeException e)
        {
            if (loggedFailures.getAndIncrement() < LOG_LIMIT)
            {
                ContextLinks.logWarning("EDT Extension Tweaks dirty preprocessor validator comparison failed: " //$NON-NLS-1$
                    + e.getMessage());
            }
        }

        return ComparisonResult.NOT_APPLICABLE;
    }

    private static void logComparisonElapsed(Method method, ComparisonResult result, long startedNanos)
    {
        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (elapsedMillis < SLOW_COMPARISON_LOG_MILLIS || loggedSlowComparisons.getAndIncrement() >= LOG_LIMIT)
            return;

        ContextLinks.logDebug("EDT Extension Tweaks slow dirty ChangeAndValidate comparison method=" //$NON-NLS-1$
            + methodName(method) + " result=" + result + " elapsedMillis=" + elapsedMillis //$NON-NLS-1$ //$NON-NLS-2$
            + " details=" + describeMethod(method) + " stack=" + stackTraceSummary()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void logSourceMethodCall(Method method, long startedNanos, Map<Pragma, Method> sourceMethods)
    {
        if (!ContextLinks.isDebugLoggingEnabled() || loggedSourceMethodCalls.getAndIncrement() >= LOG_LIMIT)
            return;

        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
        int count = sourceMethods != null ? sourceMethods.size() : -1;
        ContextLinks.logDebug("EDT Extension Tweaks dirty sourceMethod lookup method=" + methodName(method) //$NON-NLS-1$
            + " elapsedMillis=" + elapsedMillis + " resultCount=" + count //$NON-NLS-1$ //$NON-NLS-2$
            + " details=" + describeMethod(method)); //$NON-NLS-1$
    }

    private static void logValidationEntry(Method method, String phase)
    {
        if (!ContextLinks.isDebugLoggingEnabled() || loggedValidationEntries.getAndIncrement() >= LOG_LIMIT)
            return;

        ContextLinks.logDebug("EDT Extension Tweaks dirty validator probe phase=" + phase //$NON-NLS-1$
            + " details=" + describeMethod(method) + " stack=" + stackTraceSummary()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Collection<String> getChangeAndValidateComparableContent(Method method)
    {
        Collection<String> content = new ArrayList<>(getMethodContent(method));
        removeConfiguratorDirtyBlocks(content);
        return content;
    }

    private static void removeConfiguratorDirtyBlocks(Collection<String> content)
    {
        boolean insideInsert = false;
        for (Iterator<String> iterator = content.iterator(); iterator.hasNext();)
        {
            String line = iterator.next();
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            boolean beginInsert = isBeginInsert(lower);
            boolean endInsert = isEndInsert(lower);

            if (beginInsert || insideInsert || isDeleteMarker(lower))
                iterator.remove();

            if (beginInsert)
                insideInsert = true;
            else if (endInsert)
                insideInsert = false;
        }
    }

    private static boolean isChangeAndValidatePragma(Pragma pragma)
    {
        if (pragma == null || pragma.getSymbol() == null)
            return false;

        return Symbols.CHANGE_AND_VALIDATE.equalsIgnoreCase(pragma.getSymbol())
            || Symbols.CHANGE_AND_VALIDATE_RUS.equalsIgnoreCase(pragma.getSymbol());
    }

    private static boolean isBeginInsert(String lower)
    {
        return "#\u0432\u0441\u0442\u0430\u0432\u043a\u0430".equals(lower) || "#insert".equals(lower) //$NON-NLS-1$ //$NON-NLS-2$
            || "//etw+i".equals(lower); //$NON-NLS-1$
    }

    private static boolean isEndInsert(String lower)
    {
        return "#\u043a\u043e\u043d\u0435\u0446\u0432\u0441\u0442\u0430\u0432\u043a\u0438".equals(lower) //$NON-NLS-1$
            || "#endinsert".equals(lower) //$NON-NLS-1$
            || "//etw-i".equals(lower); //$NON-NLS-1$
    }

    private static boolean isDeleteMarker(String lower)
    {
        return "#\u0443\u0434\u0430\u043b\u0435\u043d\u0438\u0435".equals(lower) //$NON-NLS-1$
            || "#\u043a\u043e\u043d\u0435\u0446\u0443\u0434\u0430\u043b\u0435\u043d\u0438\u044f".equals(lower) //$NON-NLS-1$
            || "#delete".equals(lower) || "#enddelete".equals(lower) //$NON-NLS-1$ //$NON-NLS-2$
            || "//etw+d".equals(lower) || "//etw-d".equals(lower); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean hasMethodEnd(Collection<String> content)
    {
        for (String line : content)
        {
            if (isMethodEnd(line))
                return true;
        }
        return false;
    }

    private static boolean isMethodEnd(String lower)
    {
        return "\u043a\u043e\u043d\u0435\u0446\u043f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u044b".equals(lower) //$NON-NLS-1$
            || "\u043a\u043e\u043d\u0435\u0446\u0444\u0443\u043d\u043a\u0446\u0438\u0438".equals(lower) //$NON-NLS-1$
            || "endprocedure".equals(lower) //$NON-NLS-1$
            || "endfunction".equals(lower); //$NON-NLS-1$
    }

    private static String getRawDirtyMethodText(Method method, ICompositeNode methodNode, int methodLength)
    {
        Resource resource = method.eResource();
        String rawMethodText = getRawDirtyMethodText(getRawSourceByNormalizedModel(methodNode), methodNode, methodLength);
        if (rawMethodText != null)
            return rawMethodText;

        rawMethodText = getRawDirtyMethodText(DirtyPreprocessorSourceCache.get(resource), methodNode, methodLength);
        if (rawMethodText != null)
            return rawMethodText;

        Path path = toLocalPath(resource);
        if (path == null)
            return null;

        try
        {
            CachedSource source = readCached(path);
            return getRawDirtyMethodText(source.text, methodNode, methodLength);
        }
        catch (IOException | RuntimeException e)
        {
            if (loggedFailures.getAndIncrement() < LOG_LIMIT)
            {
                ContextLinks.logWarning("EDT Extension Tweaks dirty preprocessor validator raw text read failed: " //$NON-NLS-1$
                    + e.getMessage());
            }
            return null;
        }
    }

    private static String getRawSourceByNormalizedModel(ICompositeNode methodNode)
    {
        if (methodNode == null || methodNode.getRootNode() == null)
            return null;

        return DirtyPreprocessorSourceCache.getByNormalized(methodNode.getRootNode().getText());
    }

    private static String getRawDirtyMethodText(String sourceText, ICompositeNode methodNode, int methodLength)
    {
        if (sourceText == null)
            return null;

        int offset = methodNode.getTotalOffset();
        if (offset < 0 || methodLength < 0 || offset >= sourceText.length())
            return null;

        String rawMethodText = extractMethodText(sourceText, offset, methodLength);
        if (rawMethodText == null)
            return null;

        if (rawMethodText.indexOf('#') < 0)
            return null;

        DirtyPreprocessorNormalizer.NormalizationResult normalization =
            DirtyPreprocessorNormalizer.normalize(rawMethodText);
        return normalization.isChanged() ? rawMethodText : null;
    }

    private static String extractMethodText(String sourceText, int offset, int methodLength)
    {
        int end = findMethodEnd(sourceText, offset);
        if (end > offset)
            return sourceText.substring(offset, end);

        if (offset + methodLength <= sourceText.length())
            return sourceText.substring(offset, offset + methodLength);

        return null;
    }

    private static int findMethodEnd(String sourceText, int offset)
    {
        int position = offset;
        int length = sourceText.length();
        while (position < length)
        {
            int lineEnd = findLineEnd(sourceText, position);
            String line = sourceText.substring(position, lineEnd).trim().toLowerCase(Locale.ROOT);
            if (isMethodEnd(line))
                return findNextLineStart(sourceText, lineEnd);

            position = findNextLineStart(sourceText, lineEnd);
        }
        return -1;
    }

    private static int findLineEnd(String sourceText, int position)
    {
        int length = sourceText.length();
        int index = position;
        while (index < length)
        {
            char current = sourceText.charAt(index);
            if (current == '\r' || current == '\n')
                return index;
            index++;
        }
        return length;
    }

    private static int findNextLineStart(String sourceText, int lineEnd)
    {
        int length = sourceText.length();
        if (lineEnd >= length)
            return length;

        char current = sourceText.charAt(lineEnd);
        if (current == '\r' && lineEnd + 1 < length && sourceText.charAt(lineEnd + 1) == '\n')
            return lineEnd + 2;

        return lineEnd + 1;
    }

    private static Path toLocalPath(Resource resource)
    {
        if (resource == null)
            return null;

        URI uri = resource.getURI();
        if (uri == null)
            return null;

        if (uri.isFile())
            return Path.of(uri.toFileString());

        if (uri.isPlatformResource())
        {
            String platformPath = uri.toPlatformString(true);
            if (platformPath == null)
                return null;

            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new org.eclipse.core.runtime.Path(platformPath));
            IPath location = file.getLocation();
            return location != null ? location.toFile().toPath() : null;
        }

        return null;
    }

    private static CachedSource readCached(Path path)
        throws IOException
    {
        String key = path.toAbsolutePath().normalize().toString();
        long lastModified = Files.getLastModifiedTime(path).toMillis();
        long size = Files.size(path);

        CachedSource cached = sourceCache.get(key);
        if (cached != null && cached.lastModified == lastModified && cached.size == size)
            return cached;

        CachedSource fresh = new CachedSource(Files.readString(path, StandardCharsets.UTF_8), lastModified, size);
        if (sourceCache.size() > MAX_CACHE_ENTRIES)
            sourceCache.clear();
        sourceCache.put(key, fresh);
        return fresh;
    }

    private static Collection<String> toContentLines(String text)
    {
        String[] lines = text.split("\\n"); //$NON-NLS-1$
        ArrayList<String> result = new ArrayList<>(lines.length);
        for (String line : lines)
        {
            String trimmed = line.trim();
            if (!trimmed.isEmpty())
                result.add(trimmed.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static void logDirtyMethod(Method method)
    {
        if (loggedDirtyMethods.getAndIncrement() >= LOG_LIMIT)
            return;

        ContextLinks.logDebug("EDT Extension Tweaks dirty preprocessor validator uses raw method content method=" //$NON-NLS-1$
            + methodName(method) + " details=" + describeMethod(method)); //$NON-NLS-1$
    }

    private static void logMethodContentCall(Method method, boolean raw)
    {
        if (loggedMethodContentCalls.getAndIncrement() >= LOG_LIMIT)
            return;

        ContextLinks.logDebug("EDT Extension Tweaks BSL validator getMethodContent method=" + methodName(method) //$NON-NLS-1$
            + " raw=" + raw //$NON-NLS-1$
            + " details=" + describeMethod(method)); //$NON-NLS-1$
    }

    private static String methodName(Method method)
    {
        return method != null && method.getName() != null ? method.getName() : "UNKNOWN"; //$NON-NLS-1$
    }

    private static String describeMethod(Method method)
    {
        Resource resource = method != null ? method.eResource() : null;
        URI uri = resource != null ? resource.getURI() : null;
        ICompositeNode methodNode = method != null ? NodeModelUtils.findActualNodeFor(method) : null;
        IResource workspaceResource = toWorkspaceResource(uri);
        String workspacePath = workspaceResource != null ? workspaceResource.getFullPath().toString() : "NONE"; //$NON-NLS-1$
        String location = workspaceResource != null && workspaceResource.getLocation() != null
            ? workspaceResource.getLocation().toOSString()
            : "NONE"; //$NON-NLS-1$
        return "{method=" + methodName(method) //$NON-NLS-1$
            + ",uri=" + (uri != null ? uri : "NO_URI") //$NON-NLS-1$ //$NON-NLS-2$
            + ",resourceClass=" + (resource != null ? resource.getClass().getName() : "NO_RESOURCE") //$NON-NLS-1$ //$NON-NLS-2$
            + ",workspacePath=" + workspacePath //$NON-NLS-1$
            + ",location=" + location //$NON-NLS-1$
            + ",nodeOffset=" + (methodNode != null ? methodNode.getTotalOffset() : -1) //$NON-NLS-1$
            + ",nodeLength=" + (methodNode != null ? methodNode.getLength() : -1) //$NON-NLS-1$
            + ",totalLength=" + (methodNode != null ? methodNode.getTotalLength() : -1) + '}'; //$NON-NLS-1$
    }

    private static IResource toWorkspaceResource(URI uri)
    {
        if (uri == null || !uri.isPlatformResource())
            return null;

        String platformPath = uri.toPlatformString(true);
        if (platformPath == null || platformPath.isBlank())
            return null;

        return ResourcesPlugin.getWorkspace().getRoot().findMember(IPath.fromOSString(platformPath));
    }

    private static String stackTraceSummary()
    {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        ArrayList<String> frames = new ArrayList<>();
        for (StackTraceElement frame : stack)
        {
            String className = frame.getClassName();
            if (className.equals(Thread.class.getName()) || className.equals(ContextLinksBslValidatorPatches.class.getName()))
                continue;

            frames.add(className + '#' + frame.getMethodName() + ':' + frame.getLineNumber());
            if (frames.size() >= 14)
                break;
        }
        return frames.toString();
    }

    private static void logComparison(Method method, boolean suppressed, String reason, Collection<String> extensionContent,
        Collection<String> sourceContent)
    {
        if (loggedComparisons.getAndIncrement() >= LOG_LIMIT)
            return;

        String methodName = method != null && method.getName() != null ? method.getName() : "UNKNOWN"; //$NON-NLS-1$
        ContextLinks.logDebug("EDT Extension Tweaks dirty ChangeAndValidate comparison method=" + methodName //$NON-NLS-1$
            + " suppressed=" + suppressed //$NON-NLS-1$
            + " reason=" + reason //$NON-NLS-1$
            + " extensionLines=" + extensionContent.size() //$NON-NLS-1$
            + " sourceLines=" + sourceContent.size() //$NON-NLS-1$
            + " diff=" + firstDiff(extensionContent, sourceContent) //$NON-NLS-1$
            + " extensionTail=" + tail(extensionContent) //$NON-NLS-1$
            + " sourceTail=" + tail(sourceContent)); //$NON-NLS-1$
    }

    private static String firstDiff(Collection<String> extensionContent, Collection<String> sourceContent)
    {
        ArrayList<String> extensionLines = new ArrayList<>(extensionContent);
        ArrayList<String> sourceLines = new ArrayList<>(sourceContent);
        int max = Math.max(extensionLines.size(), sourceLines.size());
        for (int index = 0; index < max; index++)
        {
            String extensionLine = index < extensionLines.size() ? extensionLines.get(index) : "<missing>"; //$NON-NLS-1$
            String sourceLine = index < sourceLines.size() ? sourceLines.get(index) : "<missing>"; //$NON-NLS-1$
            if (!extensionLine.equals(sourceLine))
            {
                return "line=" + (index + 1) //$NON-NLS-1$
                    + " extension=\"" + abbreviate(extensionLine) //$NON-NLS-1$
                    + "\" source=\"" + abbreviate(sourceLine) + "\""; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return "none"; //$NON-NLS-1$
    }

    private static String tail(Collection<String> content)
    {
        ArrayList<String> lines = new ArrayList<>(content);
        if (lines.isEmpty())
            return "[]"; //$NON-NLS-1$

        int from = Math.max(0, lines.size() - 4);
        ArrayList<String> tailLines = new ArrayList<>(lines.size() - from);
        for (int index = from; index < lines.size(); index++)
            tailLines.add(abbreviate(lines.get(index)));
        return tailLines.toString();
    }

    private static String abbreviate(String line)
    {
        if (line == null)
            return "NULL"; //$NON-NLS-1$
        String clean = line.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return clean.length() > 160 ? clean.substring(0, 160) + "..." : clean; //$NON-NLS-1$
    }

    private static final class CachedSource
    {
        private final String text;
        private final long lastModified;
        private final long size;

        CachedSource(String text, long lastModified, long size)
        {
            this.text = text;
            this.lastModified = lastModified;
            this.size = size;
        }
    }

    private enum ComparisonResult
    {
        NOT_APPLICABLE,
        DIRTY_ONLY,
        MEANINGFUL_DIFFERENCE
    }
}
