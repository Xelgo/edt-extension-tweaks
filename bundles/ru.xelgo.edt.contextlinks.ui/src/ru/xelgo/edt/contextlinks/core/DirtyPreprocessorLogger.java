package ru.xelgo.edt.contextlinks.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Debug logging for dirty preprocessor normalization.
 */
final class DirtyPreprocessorLogger
{
    private static final int PREVIEW_LIMIT = Integer.getInteger(
        ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.previewLimit", 5); //$NON-NLS-1$
    private static final AtomicInteger previewCounter = new AtomicInteger();

    private DirtyPreprocessorLogger()
    {
        // Utility class.
    }

    static void logNormalized(String stage, String sourceName, String original,
        DirtyPreprocessorNormalizer.NormalizationResult result)
    {
        if (!ContextLinks.isDebugLoggingEnabled() || result == null || !result.isChanged())
            return;

        String threadName = Thread.currentThread().getName();
        ContextLinks.logDebug("EDT Extension Tweaks dirty preprocessor normalized stage=" + stage //$NON-NLS-1$
            + " source=" + sourceName //$NON-NLS-1$
            + " thread=" + threadName //$NON-NLS-1$
            + " length=" + safeLength(original) //$NON-NLS-1$
            + " insertMarkers=" + result.getInsertMarkers() //$NON-NLS-1$
            + " deleteBlocks=" + result.getDeleteBlocks() //$NON-NLS-1$
            + " maskedChars=" + result.getMaskedCharacters()); //$NON-NLS-1$

        if (previewCounter.getAndIncrement() < PREVIEW_LIMIT)
        {
            ContextLinks.logDebug("EDT Extension Tweaks dirty preprocessor preview stage=" + stage //$NON-NLS-1$
                + " source=" + sourceName //$NON-NLS-1$
                + " " + buildPreview(original, result.getText())); //$NON-NLS-1$
        }
    }

    private static int safeLength(String text)
    {
        return text != null ? text.length() : -1;
    }

    private static String buildPreview(String original, String normalized)
    {
        if (original == null || normalized == null)
            return "original-or-normalized-null"; //$NON-NLS-1$

        int markerOffset = original.indexOf('#');
        int firstLine = Math.max(1, lineOf(original, markerOffset) - 2);
        int lastLine = firstLine + 14;
        StringBuilder builder = new StringBuilder("lines="); //$NON-NLS-1$
        for (int line = firstLine; line <= lastLine; line++)
        {
            String originalLine = line(original, line);
            String normalizedLine = line(normalized, line);
            if (originalLine == null && normalizedLine == null)
                break;

            if (line > firstLine)
                builder.append(" | "); //$NON-NLS-1$
            builder.append(line)
                .append(": raw=\"") //$NON-NLS-1$
                .append(escape(originalLine))
                .append("\" norm=\"") //$NON-NLS-1$
                .append(escape(normalizedLine))
                .append('"');
        }
        return builder.toString();
    }

    private static int lineOf(String text, int offset)
    {
        if (offset < 0)
            return 1;

        int line = 1;
        for (int index = 0; index < offset && index < text.length(); index++)
        {
            char character = text.charAt(index);
            if (character == '\n')
                line++;
        }
        return line;
    }

    private static String line(String text, int lineNumber)
    {
        int currentLine = 1;
        int start = 0;
        int length = text.length();
        for (int index = 0; index <= length; index++)
        {
            if (index == length || text.charAt(index) == '\n')
            {
                if (currentLine == lineNumber)
                {
                    int end = index;
                    if (end > start && text.charAt(end - 1) == '\r')
                        end--;
                    return text.substring(start, end);
                }
                currentLine++;
                start = index + 1;
            }
        }
        return null;
    }

    private static String escape(String text)
    {
        if (text == null)
            return "NULL"; //$NON-NLS-1$

        return text.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\t", "\\t") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
