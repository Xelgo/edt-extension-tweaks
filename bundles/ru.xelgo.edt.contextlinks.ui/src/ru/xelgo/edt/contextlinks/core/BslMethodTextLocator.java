package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.xelgo.edt.contextlinks.ui.Messages;

/**
 * Locates method boundaries in BSL module text without loading the Xtext model.
 *
 * <p>Markers of this check carry only a module URI and a problem offset; the object handed out by
 * {@code Marker.provideObject} is detached from any resource, so the node model is not reachable.
 * Everything below therefore works on plain module text.
 */
final class BslMethodTextLocator
{
    private static final Pattern SIGNATURE = Pattern.compile(
        "^\\s*(?:асинх\\s+|async\\s+)?(процедура|функция|procedure|function)\\s+([\\p{L}_][\\p{L}\\p{N}_]*)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern METHOD_END = Pattern.compile(
        "^\\s*(конецпроцедуры|конецфункции|endprocedure|endfunction)\\s*;?\\s*(?://.*)?$", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** A directive must be a whole word: an identifier glued to it is not a directive. */
    private static final Pattern BEGIN_INSERT = directive("вставка", "insert"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Pattern END_INSERT = directive("конецвставки", "endinsert"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Pattern BEGIN_DELETE = directive("удаление", "delete"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Pattern END_DELETE = directive("конецудаления", "enddelete"); //$NON-NLS-1$ //$NON-NLS-2$

    private static Pattern directive(String russian, String english)
    {
        return Pattern.compile("^\\s*#\\s*(?:" + russian + "|" + english + ")\\s*(?://.*)?$", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static final Pattern CHANGE_AND_VALIDATE = Pattern.compile(
        "^\\s*&\\s*(?:ИзменениеИКонтроль|ChangeAndValidate)\\s*\\(\\s*\"([^\"]*)\"\\s*\\)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** The directive itself, with or without the base method name, is what makes a method ours. */
    private static final Pattern CHANGE_AND_VALIDATE_MARK = Pattern.compile(
        "^\\s*&\\s*(?:ИзменениеИКонтроль|ChangeAndValidate)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern ANNOTATION = Pattern.compile("^\\s*&"); //$NON-NLS-1$
    private static final Pattern COMMENT = Pattern.compile("^\\s*//"); //$NON-NLS-1$

    private BslMethodTextLocator()
    {
        // Utility class.
    }

    /** Method text plus its offset/length in the module, and the base method name from the pragma. */
    static final class MethodText
    {
        final String text;
        final int offset;
        final int length;
        final String methodName;
        final String sourceMethodName;
        /** True when the method carries the ChangeAndValidate directive. */
        final boolean changeAndValidate;

        MethodText(String text, int offset, int length, String methodName, String sourceMethodName,
            boolean changeAndValidate)
        {
            this.text = text;
            this.offset = offset;
            this.length = length;
            this.methodName = methodName;
            this.sourceMethodName = sourceMethodName;
            this.changeAndValidate = changeAndValidate;
        }
    }

    /**
     * Finds the method that contains {@code problemOffset}, including its leading annotations.
     *
     * @return {@code null} when no method encloses the offset
     */
    static MethodText findMethodAt(String moduleText, int problemOffset)
    {
        if (moduleText == null || moduleText.isEmpty() || problemOffset < 0 || problemOffset >= moduleText.length())
            return null;

        List<int[]> lines = splitLineRanges(moduleText);
        int lineIndex = lineIndexOfOffset(lines, problemOffset);
        if (lineIndex < 0)
            return null;

        int signatureLine = findSignatureLineAtOrAbove(moduleText, lines, lineIndex);
        if (signatureLine < 0)
            return null;

        int endLine = findMethodEndLine(moduleText, lines, signatureLine);
        if (endLine < 0)
            return null;

        int startLine = expandToAnnotations(moduleText, lines, signatureLine);

        int start = lines.get(startLine)[0];
        int end = lines.get(endLine)[1];
        String text = moduleText.substring(start, end);

        String methodName = methodName(lineText(moduleText, lines, signatureLine));
        String sourceName = sourceMethodName(moduleText, lines, startLine, signatureLine);
        boolean changeAndValidate = hasChangeAndValidate(moduleText, lines, startLine, signatureLine);
        return new MethodText(text, start, end - start, methodName, sourceName, changeAndValidate);
    }

    /** Finds a method by name (used to locate the base method in the parent configuration). */
    static MethodText findMethodByName(String moduleText, String name)
    {
        if (moduleText == null || name == null || name.isBlank())
            return null;

        List<int[]> lines = splitLineRanges(moduleText);
        for (int index = 0; index < lines.size(); index++)
        {
            String line = lineText(moduleText, lines, index);
            Matcher matcher = SIGNATURE.matcher(line);
            if (!matcher.find() || !name.equalsIgnoreCase(matcher.group(2)))
                continue;

            int endLine = findMethodEndLine(moduleText, lines, index);
            if (endLine < 0)
                return null;

            int startLine = expandToAnnotations(moduleText, lines, index);
            int start = lines.get(startLine)[0];
            int end = lines.get(endLine)[1];
            return new MethodText(moduleText.substring(start, end), start, end - start, matcher.group(2), null,
                hasChangeAndValidate(moduleText, lines, startLine, index));
        }
        return null;
    }

    /** Reports unbalanced insert/delete directive blocks, which make merge results unreliable. */
    static List<String> validateDirectives(String methodText)
    {
        List<String> problems = new ArrayList<>();
        if (methodText == null)
            return problems;

        int insert = 0;
        int delete = 0;
        for (String line : methodText.split("\r\n|\r|\n", -1)) //$NON-NLS-1$
        {
            if (BEGIN_INSERT.matcher(line).find())
                insert++;
            else if (END_INSERT.matcher(line).find())
                insert--;
            else if (BEGIN_DELETE.matcher(line).find())
                delete++;
            else if (END_DELETE.matcher(line).find())
                delete--;

            if (insert < 0 || delete < 0)
            {
                problems.add(Messages.ChangeAndValidateBatch_WarnUnbalancedDirectives);
                return problems;
            }
        }
        if (insert != 0 || delete != 0)
            problems.add(Messages.ChangeAndValidateBatch_WarnUnbalancedDirectives);
        return problems;
    }

    private static int findSignatureLineAtOrAbove(String text, List<int[]> lines, int fromLine)
    {
        for (int index = fromLine; index >= 0; index--)
        {
            if (SIGNATURE.matcher(lineText(text, lines, index)).find())
                return index;
        }
        return -1;
    }

    private static int findMethodEndLine(String text, List<int[]> lines, int signatureLine)
    {
        for (int index = signatureLine + 1; index < lines.size(); index++)
        {
            if (METHOD_END.matcher(lineText(text, lines, index)).find())
                return index;
        }
        return -1;
    }

    private static int expandToAnnotations(String text, List<int[]> lines, int signatureLine)
    {
        int start = signatureLine;
        for (int index = signatureLine - 1; index >= 0; index--)
        {
            String line = lineText(text, lines, index);
            if (line.isBlank())
                break;
            if (ANNOTATION.matcher(line).find() || COMMENT.matcher(line).find())
                start = index;
            else
                break;
        }
        return start;
    }

    private static String sourceMethodName(String text, List<int[]> lines, int startLine, int signatureLine)
    {
        for (int index = startLine; index <= signatureLine; index++)
        {
            Matcher matcher = CHANGE_AND_VALIDATE.matcher(lineText(text, lines, index));
            if (matcher.find())
                return matcher.group(1);
        }
        return null;
    }

    private static boolean hasChangeAndValidate(String text, List<int[]> lines, int startLine, int signatureLine)
    {
        for (int index = startLine; index <= signatureLine; index++)
        {
            if (CHANGE_AND_VALIDATE_MARK.matcher(lineText(text, lines, index)).find())
                return true;
        }
        return false;
    }

    private static String methodName(String signatureLine)
    {
        Matcher matcher = SIGNATURE.matcher(signatureLine);
        return matcher.find() ? matcher.group(2) : null;
    }

    private static List<int[]> splitLineRanges(String text)
    {
        List<int[]> lines = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < text.length())
        {
            char ch = text.charAt(index);
            if (ch == '\n')
            {
                lines.add(new int[] { start, index });
                start = index + 1;
            }
            else if (ch == '\r')
            {
                lines.add(new int[] { start, index });
                if (index + 1 < text.length() && text.charAt(index + 1) == '\n')
                    index++;
                start = index + 1;
            }
            index++;
        }
        lines.add(new int[] { start, text.length() });
        return lines;
    }

    private static int lineIndexOfOffset(List<int[]> lines, int offset)
    {
        for (int index = 0; index < lines.size(); index++)
        {
            if (offset >= lines.get(index)[0] && offset <= lines.get(index)[1])
                return index;
        }
        return -1;
    }

    private static String lineText(String text, List<int[]> lines, int index)
    {
        int[] range = lines.get(index);
        return text.substring(range[0], range[1]);
    }
}
