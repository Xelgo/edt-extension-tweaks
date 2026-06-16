package ru.xelgo.edt.contextlinks.core;

import java.util.Locale;

/**
 * Builds an offset-preserving BSL text view for dirty configurator extension blocks.
 */
final class DirtyPreprocessorNormalizer
{
    private static final String BEGIN_INSERT_COMMENT = "//ETW+I"; //$NON-NLS-1$
    private static final String END_INSERT_COMMENT = "//ETW-I"; //$NON-NLS-1$
    private static final String BEGIN_DELETE_COMMENT = "//ETW+D"; //$NON-NLS-1$
    private static final String END_DELETE_COMMENT = "//ETW-D"; //$NON-NLS-1$
    private static final String DELETE_BODY_COMMENT = "//ETW~D"; //$NON-NLS-1$

    private DirtyPreprocessorNormalizer()
    {
        // Utility class.
    }

    static NormalizationResult normalize(String source)
    {
        if (source == null || source.indexOf('#') < 0)
            return new NormalizationResult(source, false, 0, 0, 0);

        char[] result = null;
        int insertMarkers = 0;
        int deleteBlocks = 0;
        int maskedCharacters = 0;
        int deleteDepth = 0;
        int position = 0;
        int length = source.length();

        while (position < length)
        {
            int lineEnd = findLineEnd(source, position);
            Marker marker = detectLineMarker(source, position, lineEnd);
            String serviceComment = null;

            if (deleteDepth > 0)
            {
                if (marker == Marker.BEGIN_DELETE)
                {
                    deleteDepth++;
                    serviceComment = BEGIN_DELETE_COMMENT;
                }
                else if (marker == Marker.END_DELETE)
                {
                    deleteDepth--;
                    serviceComment = END_DELETE_COMMENT;
                }
                else
                {
                    serviceComment = DELETE_BODY_COMMENT;
                }
            }
            else if (marker == Marker.BEGIN_INSERT || marker == Marker.END_INSERT)
            {
                insertMarkers++;
                serviceComment = marker == Marker.BEGIN_INSERT ? BEGIN_INSERT_COMMENT : END_INSERT_COMMENT;
            }
            else if (marker == Marker.BEGIN_DELETE)
            {
                deleteBlocks++;
                deleteDepth = 1;
                serviceComment = BEGIN_DELETE_COMMENT;
            }
            else if (marker == Marker.END_DELETE)
            {
                serviceComment = END_DELETE_COMMENT;
            }

            if (serviceComment != null)
            {
                if (result == null)
                    result = source.toCharArray();
                maskedCharacters += replaceWithServiceComment(result, position, lineEnd, serviceComment);
            }

            position = findNextLineStart(source, lineEnd);
        }

        if (result == null)
            return new NormalizationResult(source, false, 0, 0, 0);

        return new NormalizationResult(new String(result), true, insertMarkers, deleteBlocks, maskedCharacters);
    }

    private static int findLineEnd(String source, int position)
    {
        int length = source.length();
        int index = position;
        while (index < length)
        {
            char current = source.charAt(index);
            if (current == '\r' || current == '\n')
                return index;
            index++;
        }
        return length;
    }

    private static int findNextLineStart(String source, int lineEnd)
    {
        int length = source.length();
        if (lineEnd >= length)
            return length;

        char current = source.charAt(lineEnd);
        if (current == '\r' && lineEnd + 1 < length && source.charAt(lineEnd + 1) == '\n')
            return lineEnd + 2;

        return lineEnd + 1;
    }

    private static Marker detectLineMarker(String source, int start, int end)
    {
        int index = start;
        while (index < end && isHorizontalWhitespace(source.charAt(index)))
            index++;

        if (index >= end || source.charAt(index) != '#')
            return Marker.NONE;

        index++;
        while (index < end && isHorizontalWhitespace(source.charAt(index)))
            index++;

        int wordStart = index;
        while (index < end && Character.isLetter(source.charAt(index)))
            index++;

        if (wordStart == index)
            return Marker.NONE;

        String keyword = source.substring(wordStart, index).toLowerCase(Locale.ROOT);
        if ("\u0432\u0441\u0442\u0430\u0432\u043a\u0430".equals(keyword) || "insert".equals(keyword)) //$NON-NLS-1$ //$NON-NLS-2$
            return Marker.BEGIN_INSERT;
        if ("\u043a\u043e\u043d\u0435\u0446\u0432\u0441\u0442\u0430\u0432\u043a\u0438".equals(keyword) //$NON-NLS-1$
            || "endinsert".equals(keyword)) //$NON-NLS-1$
            return Marker.END_INSERT;
        if ("\u0443\u0434\u0430\u043b\u0435\u043d\u0438\u0435".equals(keyword) || "delete".equals(keyword)) //$NON-NLS-1$ //$NON-NLS-2$
            return Marker.BEGIN_DELETE;
        if ("\u043a\u043e\u043d\u0435\u0446\u0443\u0434\u0430\u043b\u0435\u043d\u0438\u044f".equals(keyword) //$NON-NLS-1$
            || "enddelete".equals(keyword)) //$NON-NLS-1$
            return Marker.END_DELETE;

        return Marker.NONE;
    }

    private static boolean isHorizontalWhitespace(char character)
    {
        return character == ' ' || character == '\t' || character == '\u00A0' || character == '\f';
    }

    private static int replaceWithServiceComment(char[] result, int start, int end, String serviceComment)
    {
        int lineLength = end - start;
        if (lineLength <= 0)
            return 0;

        int changedCharacters = 0;
        String replacement = serviceComment.length() <= lineLength ? serviceComment : "//"; //$NON-NLS-1$
        for (int index = start; index < end; index++)
        {
            char character = index - start < replacement.length() ? replacement.charAt(index - start) : ' ';
            if (result[index] != character)
            {
                result[index] = character;
                changedCharacters++;
            }
        }
        return changedCharacters;
    }

    static final class NormalizationResult
    {
        private final String text;
        private final boolean changed;
        private final int insertMarkers;
        private final int deleteBlocks;
        private final int maskedCharacters;

        NormalizationResult(String text, boolean changed, int insertMarkers, int deleteBlocks, int maskedCharacters)
        {
            this.text = text;
            this.changed = changed;
            this.insertMarkers = insertMarkers;
            this.deleteBlocks = deleteBlocks;
            this.maskedCharacters = maskedCharacters;
        }

        String getText()
        {
            return text;
        }

        boolean isChanged()
        {
            return changed;
        }

        int getInsertMarkers()
        {
            return insertMarkers;
        }

        int getDeleteBlocks()
        {
            return deleteBlocks;
        }

        int getMaskedCharacters()
        {
            return maskedCharacters;
        }
    }

    private enum Marker
    {
        NONE,
        BEGIN_INSERT,
        END_INSERT,
        BEGIN_DELETE,
        END_DELETE
    }
}
