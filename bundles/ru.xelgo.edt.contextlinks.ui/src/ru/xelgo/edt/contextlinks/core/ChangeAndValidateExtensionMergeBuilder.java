package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds a better initial merge result for ChangeAndValidate extension methods.
 */
final class ChangeAndValidateExtensionMergeBuilder
{
    private ChangeAndValidateExtensionMergeBuilder()
    {
        // Utility class.
    }

    static MergeTexts build(String sourceText, String extensionText)
    {
        MarkedText markedExtension = parseMarkedText(extensionText);
        String ancestorText = join(markedExtension.ancestorLines);
        String extensionBaseText = buildExtensionBase(sourceText, extensionText);
        String suggestedText = applyOperations(markedExtension, extensionBaseText);
        return new MergeTexts(ancestorText, suggestedText);
    }

    private static String buildExtensionBase(String sourceText, String extensionText)
    {
        List<Line> sourceLines = splitLines(sourceText);
        List<Line> extensionLines = splitLines(extensionText);
        int sourceSignatureIndex = findSignatureLine(sourceLines);
        int extensionSignatureIndex = findSignatureLine(extensionLines);
        if (sourceSignatureIndex < 0 || extensionSignatureIndex < 0)
            return sourceText;

        String extensionMethodName = extractMethodName(extensionLines.get(extensionSignatureIndex).content);
        if (extensionMethodName == null || extensionMethodName.isBlank())
            return sourceText;

        List<Line> result = new ArrayList<>();
        for (int index = 0; index < extensionSignatureIndex; index++)
            result.add(extensionLines.get(index));

        for (int index = sourceSignatureIndex; index < sourceLines.size(); index++)
        {
            Line line = sourceLines.get(index);
            if (index == sourceSignatureIndex)
                result.add(line.withContent(replaceMethodName(line.content, extensionMethodName)));
            else
                result.add(line);
        }
        return join(result);
    }

    private static String applyOperations(MarkedText markedExtension, String extensionBaseText)
    {
        List<Line> targetLines = new ArrayList<>(splitLines(extensionBaseText));
        if (markedExtension.operations.isEmpty() || targetLines.isEmpty())
            return extensionBaseText;

        int[] mapping = buildLineMapping(markedExtension.ancestorLines, targetLines);
        List<AppliedOperation> appliedOperations = new ArrayList<>();
        for (Operation operation : markedExtension.operations)
            appliedOperations.add(toAppliedOperation(operation, markedExtension.ancestorLines, targetLines, mapping));

        appliedOperations.sort(Comparator.comparingInt((AppliedOperation operation) -> operation.targetIndex)
            .reversed()
            .thenComparingInt(operation -> operation.order));

        for (AppliedOperation operation : appliedOperations)
        {
            if (operation.kind == OperationKind.INSERT)
            {
                targetLines.addAll(clamp(operation.targetIndex, 0, targetLines.size()), copy(operation.blockLines));
            }
            else if (operation.kind == OperationKind.DELETE)
            {
                int start = clamp(operation.targetIndex, 0, targetLines.size());
                int end = clamp(start + operation.deleteLength, start, targetLines.size());
                if (operation.deleteLength > 0 && start < end)
                {
                    targetLines.add(start, operation.beginMarker);
                    targetLines.add(end + 1, operation.endMarker);
                }
                else
                {
                    targetLines.addAll(start, copy(operation.blockLines));
                }
            }
        }

        return join(targetLines);
    }

    private static AppliedOperation toAppliedOperation(Operation operation, List<Line> baseLines, List<Line> targetLines,
        int[] mapping)
    {
        int targetIndex = mapInsertionIndex(operation.baseIndex, baseLines.size(), targetLines.size(), mapping);
        if (operation.kind == OperationKind.DELETE)
        {
            int exactIndex = findSequence(targetLines, operation.deletedLines);
            if (exactIndex >= 0)
            {
                return new AppliedOperation(operation.order, OperationKind.DELETE, exactIndex,
                    operation.deletedLines.size(), operation.blockLines, first(operation.blockLines),
                    last(operation.blockLines));
            }
        }

        return new AppliedOperation(operation.order, operation.kind, targetIndex, 0, operation.blockLines,
            first(operation.blockLines), last(operation.blockLines));
    }

    private static MarkedText parseMarkedText(String source)
    {
        List<Line> lines = splitLines(source);
        List<Line> ancestorLines = new ArrayList<>();
        List<Operation> operations = new ArrayList<>();
        int order = 0;

        for (int index = 0; index < lines.size();)
        {
            Line line = lines.get(index);
            Marker marker = detectLineMarker(line.content);
            if (marker == Marker.BEGIN_INSERT)
            {
                List<Line> block = new ArrayList<>();
                int baseIndex = ancestorLines.size();
                block.add(line);
                index++;
                while (index < lines.size())
                {
                    Line blockLine = lines.get(index);
                    block.add(blockLine);
                    index++;
                    if (detectLineMarker(blockLine.content) == Marker.END_INSERT)
                        break;
                }
                operations.add(new Operation(order++, OperationKind.INSERT, baseIndex, block, List.of()));
            }
            else if (marker == Marker.BEGIN_DELETE)
            {
                List<Line> block = new ArrayList<>();
                List<Line> deletedLines = new ArrayList<>();
                int baseIndex = ancestorLines.size();
                block.add(line);
                index++;
                while (index < lines.size())
                {
                    Line blockLine = lines.get(index);
                    Marker blockMarker = detectLineMarker(blockLine.content);
                    block.add(blockLine);
                    index++;
                    if (blockMarker == Marker.END_DELETE)
                        break;

                    deletedLines.add(blockLine);
                    ancestorLines.add(blockLine);
                }
                operations.add(new Operation(order++, OperationKind.DELETE, baseIndex, block, deletedLines));
            }
            else
            {
                ancestorLines.add(line);
                index++;
            }
        }

        return new MarkedText(ancestorLines, operations);
    }

    private static int[] buildLineMapping(List<Line> left, List<Line> right)
    {
        int[][] lcs = new int[left.size() + 1][right.size() + 1];
        for (int leftIndex = left.size() - 1; leftIndex >= 0; leftIndex--)
        {
            for (int rightIndex = right.size() - 1; rightIndex >= 0; rightIndex--)
            {
                if (sameLine(left.get(leftIndex), right.get(rightIndex)))
                    lcs[leftIndex][rightIndex] = lcs[leftIndex + 1][rightIndex + 1] + 1;
                else
                    lcs[leftIndex][rightIndex] =
                        Math.max(lcs[leftIndex + 1][rightIndex], lcs[leftIndex][rightIndex + 1]);
            }
        }

        int[] mapping = new int[left.size()];
        Arrays.fill(mapping, -1);
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.size() && rightIndex < right.size())
        {
            if (sameLine(left.get(leftIndex), right.get(rightIndex)))
            {
                mapping[leftIndex] = rightIndex;
                leftIndex++;
                rightIndex++;
            }
            else if (lcs[leftIndex + 1][rightIndex] >= lcs[leftIndex][rightIndex + 1])
            {
                leftIndex++;
            }
            else
            {
                rightIndex++;
            }
        }
        return mapping;
    }

    private static int mapInsertionIndex(int baseIndex, int baseSize, int targetSize, int[] mapping)
    {
        if (baseIndex >= 0 && baseIndex < baseSize && mapping[baseIndex] >= 0)
            return mapping[baseIndex];

        for (int index = baseIndex - 1; index >= 0; index--)
        {
            if (mapping[index] >= 0)
                return mapping[index] + 1;
        }

        for (int index = baseIndex; index < baseSize; index++)
        {
            if (mapping[index] >= 0)
                return mapping[index];
        }

        return targetSize;
    }

    private static int findSequence(List<Line> lines, List<Line> sequence)
    {
        if (sequence.isEmpty() || sequence.size() > lines.size())
            return -1;

        for (int index = 0; index <= lines.size() - sequence.size(); index++)
        {
            boolean matches = true;
            for (int sequenceIndex = 0; sequenceIndex < sequence.size(); sequenceIndex++)
            {
                if (!sameLine(lines.get(index + sequenceIndex), sequence.get(sequenceIndex)))
                {
                    matches = false;
                    break;
                }
            }

            if (matches)
                return index;
        }
        return -1;
    }

    private static boolean sameLine(Line left, Line right)
    {
        return normalizeLine(left.content).equals(normalizeLine(right.content));
    }

    private static String normalizeLine(String line)
    {
        return line == null ? "" : line.trim(); //$NON-NLS-1$
    }

    private static List<Line> splitLines(String text)
    {
        List<Line> result = new ArrayList<>();
        if (text == null || text.isEmpty())
            return result;

        int position = 0;
        while (position < text.length())
        {
            int lineEnd = findLineEnd(text, position);
            int nextLineStart = findNextLineStart(text, lineEnd);
            String content = text.substring(position, lineEnd);
            String separator = text.substring(lineEnd, nextLineStart);
            result.add(new Line(content, separator));
            position = nextLineStart;
        }
        return result;
    }

    private static int findLineEnd(String source, int position)
    {
        int index = position;
        while (index < source.length())
        {
            char current = source.charAt(index);
            if (current == '\r' || current == '\n')
                return index;
            index++;
        }
        return source.length();
    }

    private static int findNextLineStart(String source, int lineEnd)
    {
        if (lineEnd >= source.length())
            return source.length();

        char current = source.charAt(lineEnd);
        if (current == '\r' && lineEnd + 1 < source.length() && source.charAt(lineEnd + 1) == '\n')
            return lineEnd + 2;
        return lineEnd + 1;
    }

    private static String join(List<Line> lines)
    {
        StringBuilder result = new StringBuilder();
        for (Line line : lines)
            result.append(line.content).append(line.separator);
        return result.toString();
    }

    private static int findSignatureLine(List<Line> lines)
    {
        for (int index = 0; index < lines.size(); index++)
        {
            if (isSignatureLine(lines.get(index).content))
                return index;
        }
        return -1;
    }

    private static boolean isSignatureLine(String line)
    {
        String normalized = line != null ? line.stripLeading().toLowerCase(Locale.ROOT) : ""; //$NON-NLS-1$
        return normalized.startsWith("\u043f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u0430 ") //$NON-NLS-1$
            || normalized.startsWith("\u0444\u0443\u043d\u043a\u0446\u0438\u044f ") //$NON-NLS-1$
            || normalized.startsWith("procedure ") //$NON-NLS-1$
            || normalized.startsWith("function "); //$NON-NLS-1$
    }

    private static String extractMethodName(String signature)
    {
        NameRange range = findMethodNameRange(signature);
        return range != null ? signature.substring(range.start, range.end) : null;
    }

    private static String replaceMethodName(String signature, String methodName)
    {
        NameRange range = findMethodNameRange(signature);
        if (range == null)
            return signature;

        return signature.substring(0, range.start) + methodName + signature.substring(range.end);
    }

    private static NameRange findMethodNameRange(String signature)
    {
        if (signature == null)
            return null;

        int index = 0;
        while (index < signature.length() && isHorizontalWhitespace(signature.charAt(index)))
            index++;
        while (index < signature.length() && Character.isLetter(signature.charAt(index)))
            index++;
        while (index < signature.length() && isHorizontalWhitespace(signature.charAt(index)))
            index++;

        int start = index;
        while (index < signature.length())
        {
            char current = signature.charAt(index);
            if (current == '(' || isHorizontalWhitespace(current))
                break;
            index++;
        }

        return start < index ? new NameRange(start, index) : null;
    }

    private static Marker detectLineMarker(String line)
    {
        int index = 0;
        int end = line.length();
        while (index < end && isHorizontalWhitespace(line.charAt(index)))
            index++;

        if (index >= end || line.charAt(index) != '#')
            return Marker.NONE;

        index++;
        while (index < end && isHorizontalWhitespace(line.charAt(index)))
            index++;

        int wordStart = index;
        while (index < end && Character.isLetter(line.charAt(index)))
            index++;

        if (wordStart == index)
            return Marker.NONE;

        String keyword = line.substring(wordStart, index).toLowerCase(Locale.ROOT);
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

    private static List<Line> copy(List<Line> lines)
    {
        return new ArrayList<>(lines);
    }

    private static Line first(List<Line> lines)
    {
        return lines.isEmpty() ? new Line("", "") : lines.get(0); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Line last(List<Line> lines)
    {
        return lines.isEmpty() ? new Line("", "") : lines.get(lines.size() - 1); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    static final class MergeTexts
    {
        final String ancestorText;
        final String suggestedText;

        MergeTexts(String ancestorText, String suggestedText)
        {
            this.ancestorText = ancestorText;
            this.suggestedText = suggestedText;
        }
    }

    private static final class MarkedText
    {
        private final List<Line> ancestorLines;
        private final List<Operation> operations;

        MarkedText(List<Line> ancestorLines, List<Operation> operations)
        {
            this.ancestorLines = ancestorLines;
            this.operations = operations;
        }
    }

    private static final class Operation
    {
        private final int order;
        private final OperationKind kind;
        private final int baseIndex;
        private final List<Line> blockLines;
        private final List<Line> deletedLines;

        Operation(int order, OperationKind kind, int baseIndex, List<Line> blockLines, List<Line> deletedLines)
        {
            this.order = order;
            this.kind = kind;
            this.baseIndex = baseIndex;
            this.blockLines = blockLines;
            this.deletedLines = deletedLines;
        }
    }

    private static final class AppliedOperation
    {
        private final int order;
        private final OperationKind kind;
        private final int targetIndex;
        private final int deleteLength;
        private final List<Line> blockLines;
        private final Line beginMarker;
        private final Line endMarker;

        AppliedOperation(int order, OperationKind kind, int targetIndex, int deleteLength, List<Line> blockLines,
            Line beginMarker, Line endMarker)
        {
            this.order = order;
            this.kind = kind;
            this.targetIndex = targetIndex;
            this.deleteLength = deleteLength;
            this.blockLines = blockLines;
            this.beginMarker = beginMarker;
            this.endMarker = endMarker;
        }
    }

    private static final class Line
    {
        private final String content;
        private final String separator;

        Line(String content, String separator)
        {
            this.content = content;
            this.separator = separator;
        }

        Line withContent(String newContent)
        {
            return new Line(newContent, separator);
        }
    }

    private static final class NameRange
    {
        private final int start;
        private final int end;

        NameRange(int start, int end)
        {
            this.start = start;
            this.end = end;
        }
    }

    private enum OperationKind
    {
        INSERT,
        DELETE
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
