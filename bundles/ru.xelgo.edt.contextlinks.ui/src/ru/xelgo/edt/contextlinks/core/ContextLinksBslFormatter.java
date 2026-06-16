package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.formatting2.IFormattableDocument;
import org.eclipse.xtext.formatting2.ITextReplacer;
import org.eclipse.xtext.formatting2.ITextReplacerContext;
import org.eclipse.xtext.formatting2.regionaccess.IEObjectRegion;
import org.eclipse.xtext.formatting2.regionaccess.ISemanticRegion;
import org.eclipse.xtext.formatting2.regionaccess.ITextSegment;

import com._1c.g5.v8.dt.bsl.common.Symbols;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.bsl.model.RegionPreprocessor;
import com._1c.g5.v8.dt.bsl.model.RegionPreprocessorType;
import com.e1c.g5.v8.dt.formatter.bsl.BslFormatter2;

/**
 * Formats only extension insertion blocks inside ChangeAndValidate methods.
 */
public class ContextLinksBslFormatter
    extends BslFormatter2
{
    private static final String INDENT_UNIT = "\t"; //$NON-NLS-1$

    @Override
    protected void formatMethod(Method method, IFormattableDocument document)
    {
        if (!hasChangeAndValidatePragma(method))
        {
            super.formatMethod(method, document);
            return;
        }

        List<TextRange> insertRanges = collectInsertContentRanges(method);
        if (insertRanges.isEmpty())
            return;

        IFormattableDocument insertOnlyDocument = document.withReplacerFilter(
            replacer -> shouldKeepNativeInsertReplacer(replacer, insertRanges));

        for (RegionPreprocessor region : EcoreUtil2.getAllContentsOfType(method, RegionPreprocessor.class))
        {
            if (region.computeType() != RegionPreprocessorType.INSERT)
                continue;

            formatRegionPreprocessor(region, insertOnlyDocument);

            addInsertOperatorSpacingReplacers(region, document);
            addInsertIndentReplacers(method, region, document);
        }
    }

    private List<TextRange> collectInsertContentRanges(Method method)
    {
        List<TextRange> ranges = new ArrayList<>();
        for (RegionPreprocessor region : EcoreUtil2.getAllContentsOfType(method, RegionPreprocessor.class))
        {
            if (region.computeType() != RegionPreprocessorType.INSERT)
                continue;

            ISemanticRegion beginInsert = beginInsertSemanticRegion(region);
            ISemanticRegion endInsert = endInsertSemanticRegion(region);
            if (beginInsert == null || endInsert == null)
                continue;

            ranges.add(new TextRange(beginInsert.getEndOffset(), endInsert.getOffset()));
        }

        return ranges;
    }

    private boolean shouldKeepNativeInsertReplacer(ITextReplacer replacer, List<TextRange> insertRanges)
    {
        ITextSegment replacerRegion = replacer.getRegion();
        if (replacerRegion == null)
            return false;

        boolean insideInsert = false;
        for (TextRange insertRange : insertRanges)
        {
            if (insertRange.intersects(replacerRegion))
            {
                insideInsert = true;
                break;
            }
        }
        if (!insideInsert)
            return false;

        boolean lineStructureReplacer = isLineStructureReplacer(replacerRegion);
        boolean operatorSpacingReplacer = isOperatorSpacingReplacer(replacerRegion);
        return !lineStructureReplacer && !operatorSpacingReplacer;
    }

    private boolean isOperatorSpacingReplacer(ITextSegment segment)
    {
        if (!isWhitespaceOnly(segment.getText()))
            return false;

        String documentText = segment.getTextRegionAccess().regionForDocument().getText();
        char left = previousNonWhitespaceChar(documentText, segment.getOffset());
        char right = nextNonWhitespaceChar(documentText, segment.getEndOffset());
        return isBinaryOperatorChar(left) || isBinaryOperatorChar(right);
    }

    private char previousNonWhitespaceChar(String text, int offset)
    {
        int index = Math.max(0, Math.min(offset, text.length())) - 1;
        while (index >= 0)
        {
            char character = text.charAt(index);
            if (character == '\r' || character == '\n')
                return 0;
            if (!Character.isWhitespace(character))
                return character;
            index--;
        }
        return 0;
    }

    private char nextNonWhitespaceChar(String text, int offset)
    {
        int index = Math.max(0, Math.min(offset, text.length()));
        while (index < text.length())
        {
            char character = text.charAt(index);
            if (character == '\r' || character == '\n')
                return 0;
            if (!Character.isWhitespace(character))
                return character;
            index++;
        }
        return 0;
    }

    private boolean isBinaryOperatorChar(char character)
    {
        return character == '=' || character == '<' || character == '>' || character == '+' || character == '*'
            || character == '/';
    }

    private boolean isLineStructureReplacer(ITextSegment segment)
    {
        String text = segment.getText();
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
            return true;

        if (!isWhitespaceOnly(text))
            return false;

        return isAtLinePrefix(segment);
    }

    private boolean isWhitespaceOnly(String text)
    {
        for (int index = 0; index < text.length(); index++)
        {
            if (!Character.isWhitespace(text.charAt(index)))
                return false;
        }
        return true;
    }

    private boolean isAtLinePrefix(ITextSegment segment)
    {
        String documentText = segment.getTextRegionAccess().regionForDocument().getText();
        int offset = Math.min(segment.getOffset(), documentText.length());
        int lineStart = lineStart(documentText, offset);
        for (int index = lineStart; index < offset; index++)
        {
            char character = documentText.charAt(index);
            if (character != ' ' && character != '\t')
                return false;
        }
        return true;
    }

    private boolean hasChangeAndValidatePragma(Method method)
    {
        for (Pragma pragma : method.getPragmas())
        {
            String symbol = pragma.getSymbol();
            if (symbol != null && (isSameSymbol(symbol, Symbols.CHANGE_AND_VALIDATE)
                || isSameSymbol(symbol, Symbols.CHANGE_AND_VALIDATE_RUS)))
                return true;
        }
        return false;
    }

    private boolean isSameSymbol(String actual, String expected)
    {
        return expected.equalsIgnoreCase(actual);
    }

    private void addInsertIndentReplacers(Method method, RegionPreprocessor region, IFormattableDocument document)
    {
        ISemanticRegion beginInsert = beginInsertSemanticRegion(region);
        ISemanticRegion endInsert = endInsertSemanticRegion(region);
        if (beginInsert == null || endInsert == null)
            return;

        String documentText = beginInsert.getTextRegionAccess().regionForDocument().getText();
        String markerIndent = expectedIndentBefore(method, beginInsert.getOffset());

        addLineIndentReplacer(beginInsert, lineStart(documentText, beginInsert.getOffset()), markerIndent, document);

        int bodyLineStart = nextLineStart(documentText, lineEnd(documentText, beginInsert.getEndOffset()));
        int endLineStart = lineStart(documentText, endInsert.getOffset());
        int indentLevel = 0;
        while (bodyLineStart < endLineStart)
        {
            int bodyLineEnd = lineEnd(documentText, bodyLineStart);
            String trimmedLine = documentText.substring(bodyLineStart, bodyLineEnd).trim();
            if (!trimmedLine.isEmpty())
            {
                if (startsWithBlockClosing(trimmedLine))
                    indentLevel = Math.max(0, indentLevel - 1);

                addLineIndentReplacer(beginInsert, bodyLineStart,
                    markerIndent + INDENT_UNIT + repeatIndent(indentLevel), document);

                if (opensBlockAfterLine(trimmedLine))
                    indentLevel++;
            }
            else
            {
                addLineIndentReplacer(beginInsert, bodyLineStart, "", document); //$NON-NLS-1$
            }

            bodyLineStart = nextLineStart(documentText, bodyLineEnd);
        }

        addLineIndentReplacer(beginInsert, endLineStart, markerIndent, document);
    }

    private void addInsertOperatorSpacingReplacers(RegionPreprocessor region, IFormattableDocument document)
    {
        ISemanticRegion beginInsert = beginInsertSemanticRegion(region);
        ISemanticRegion endInsert = endInsertSemanticRegion(region);
        if (beginInsert == null || endInsert == null)
            return;

        String documentText = beginInsert.getTextRegionAccess().regionForDocument().getText();
        int lineStart = nextLineStart(documentText, lineEnd(documentText, beginInsert.getEndOffset()));
        int endLineStart = lineStart(documentText, endInsert.getOffset());
        while (lineStart < endLineStart)
        {
            int lineEnd = lineEnd(documentText, lineStart);
            addOperatorSpacingReplacers(beginInsert, documentText, lineStart, lineEnd, document);
            lineStart = nextLineStart(documentText, lineEnd);
        }
    }

    private void addOperatorSpacingReplacers(ISemanticRegion anchorRegion, String documentText, int lineStart,
        int lineEnd, IFormattableDocument document)
    {
        boolean inString = false;
        int index = lineStart;
        while (index < lineEnd)
        {
            char current = documentText.charAt(index);
            if (current == '"')
            {
                if (inString && index + 1 < lineEnd && documentText.charAt(index + 1) == '"')
                {
                    index += 2;
                    continue;
                }
                inString = !inString;
                index++;
                continue;
            }

            if (!inString && current == '/' && index + 1 < lineEnd && documentText.charAt(index + 1) == '/')
                break;

            if (!inString)
            {
                int operatorLength = operatorLengthAt(documentText, index, lineEnd);
                if (operatorLength > 0)
                {
                    addOperatorSideSpacingReplacers(anchorRegion, documentText, lineStart, lineEnd, index,
                        operatorLength, document);
                    index += operatorLength;
                    continue;
                }
            }
            index++;
        }
    }

    private int operatorLengthAt(String text, int offset, int lineEnd)
    {
        char current = text.charAt(offset);
        if (offset + 1 < lineEnd)
        {
            char next = text.charAt(offset + 1);
            if ((current == '<' && (next == '>' || next == '=')) || (current == '>' && next == '='))
                return 2;
        }

        if (current == '=' || current == '<' || current == '>' || current == '+' || current == '*'
            || current == '/')
            return 1;

        return 0;
    }

    private void addOperatorSideSpacingReplacers(ISemanticRegion anchorRegion, String documentText, int lineStart,
        int lineEnd, int operatorOffset, int operatorLength, IFormattableDocument document)
    {
        int beforeStart = operatorOffset;
        while (beforeStart > lineStart && isHorizontalWhitespace(documentText.charAt(beforeStart - 1)))
            beforeStart--;

        if (beforeStart > lineStart)
            addFixedReplacer(anchorRegion, beforeStart, operatorOffset - beforeStart, " ", document); //$NON-NLS-1$

        int operatorEnd = operatorOffset + operatorLength;
        int afterEnd = operatorEnd;
        while (afterEnd < lineEnd && isHorizontalWhitespace(documentText.charAt(afterEnd)))
            afterEnd++;

        if (afterEnd < lineEnd)
            addFixedReplacer(anchorRegion, operatorEnd, afterEnd - operatorEnd, " ", document); //$NON-NLS-1$
    }

    private boolean isHorizontalWhitespace(char character)
    {
        return character == ' ' || character == '\t';
    }

    private void addLineIndentReplacer(ISemanticRegion anchorRegion, int lineStart, String indent,
        IFormattableDocument document)
    {
        String documentText = anchorRegion.getTextRegionAccess().regionForDocument().getText();
        int whitespaceEnd = leadingWhitespaceEnd(documentText, lineStart);
        addFixedReplacer(anchorRegion, lineStart, whitespaceEnd - lineStart, indent, document);
    }

    private void addFixedReplacer(ISemanticRegion anchorRegion, int offset, int length, String replacement,
        IFormattableDocument document)
    {
        ITextSegment region = anchorRegion.getTextRegionAccess().regionForOffset(offset, length);
        if (!region.getText().equals(replacement))
            document.addReplacer(new FixedTextReplacer(region, replacement));
    }

    private int leadingWhitespaceEnd(String text, int lineStart)
    {
        int lineEnd = lineEnd(text, lineStart);
        int index = lineStart;
        while (index < lineEnd)
        {
            char character = text.charAt(index);
            if (character != ' ' && character != '\t')
                break;
            index++;
        }
        return index;
    }

    private ISemanticRegion beginInsertSemanticRegion(RegionPreprocessor region)
    {
        return findInsertMarker(region, true);
    }

    private ISemanticRegion endInsertSemanticRegion(RegionPreprocessor region)
    {
        return findInsertMarker(region, false);
    }

    private ISemanticRegion findInsertMarker(RegionPreprocessor region, boolean begin)
    {
        IEObjectRegion objectRegion = textRegionExtensions.regionForEObject(region);
        if (objectRegion == null)
            return null;

        for (ISemanticRegion semanticRegion : objectRegion.getAllSemanticRegions())
        {
            String text = semanticRegion.getText();
            if (begin && isBeginInsertMarker(text))
                return semanticRegion;
            if (!begin && isEndInsertMarker(text))
                return semanticRegion;
        }

        return null;
    }

    private boolean isBeginInsertMarker(String text)
    {
        return text != null && ("#Insert".equalsIgnoreCase(text) || "#Вставка".equalsIgnoreCase(text)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private boolean isEndInsertMarker(String text)
    {
        return text != null && ("#EndInsert".equalsIgnoreCase(text) || "#КонецВставки".equalsIgnoreCase(text)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String expectedIndentBefore(Method method, int targetOffset)
    {
        IEObjectRegion methodRegion = textRegionExtensions.regionForEObject(method);
        if (methodRegion == null)
            return ""; //$NON-NLS-1$

        String documentText = methodRegion.getTextRegionAccess().regionForDocument().getText();
        int lineStart = lineStart(documentText, methodRegion.getOffset());
        int targetLineStart = lineStart(documentText, targetOffset);
        int indentLevel = 0;

        while (lineStart < targetLineStart)
        {
            int lineEnd = lineEnd(documentText, lineStart);
            String line = documentText.substring(lineStart, lineEnd).trim();
            if (!line.isEmpty() && !line.startsWith("&")) //$NON-NLS-1$
            {
                if (startsWithBlockClosing(line))
                    indentLevel = Math.max(0, indentLevel - 1);

                if (opensBlockAfterLine(line))
                    indentLevel++;
            }

            lineStart = nextLineStart(documentText, lineEnd);
        }

        return repeatIndent(indentLevel);
    }

    private boolean startsWithBlockClosing(String line)
    {
        String normalized = normalizeCodeLine(line);
        return normalized.startsWith("КОНЕЦЕСЛИ") || normalized.startsWith("КОНЕЦЦИКЛА") //$NON-NLS-1$ //$NON-NLS-2$
            || normalized.startsWith("КОНЕЦПОПЫТКИ") || normalized.startsWith("КОНЕЦФУНКЦИИ") //$NON-NLS-1$ //$NON-NLS-2$
            || normalized.startsWith("КОНЕЦПРОЦЕДУРЫ") || normalized.startsWith("ИНАЧЕ") //$NON-NLS-1$ //$NON-NLS-2$
            || normalized.startsWith("ИНАЧЕЕСЛИ") || normalized.startsWith("ИСКЛЮЧЕНИЕ") //$NON-NLS-1$ //$NON-NLS-2$
            || normalized.startsWith("ENDIF") || normalized.startsWith("ENDDO") //$NON-NLS-1$ //$NON-NLS-2$
            || normalized.startsWith("ENDTRY") || normalized.startsWith("ENDFUNCTION") //$NON-NLS-1$ //$NON-NLS-2$
            || normalized.startsWith("ENDPROCEDURE") || normalized.startsWith("ELSE") //$NON-NLS-1$ //$NON-NLS-2$
            || normalized.startsWith("ELSIF") || normalized.startsWith("EXCEPT"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private boolean opensBlockAfterLine(String line)
    {
        String normalized = normalizeCodeLine(line);
        return normalized.startsWith("ФУНКЦИЯ ") || normalized.startsWith("ПРОЦЕДУРА ") //$NON-NLS-1$ //$NON-NLS-2$
            || normalized.startsWith("FUNCTION ") || normalized.startsWith("PROCEDURE ") //$NON-NLS-1$ //$NON-NLS-2$
            || normalized.endsWith(" ТОГДА") || normalized.endsWith(" ЦИКЛ") //$NON-NLS-1$ //$NON-NLS-2$
            || "ПОПЫТКА".equals(normalized) || normalized.startsWith("ИНАЧЕ") //$NON-NLS-1$ //$NON-NLS-2$
            || normalized.startsWith("ИНАЧЕЕСЛИ") || normalized.startsWith("ИСКЛЮЧЕНИЕ") //$NON-NLS-1$ //$NON-NLS-2$
            || normalized.endsWith(" THEN") || normalized.endsWith(" DO") //$NON-NLS-1$ //$NON-NLS-2$
            || "TRY".equals(normalized) || normalized.startsWith("ELSE") //$NON-NLS-1$ //$NON-NLS-2$
            || normalized.startsWith("ELSIF") || normalized.startsWith("EXCEPT"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String normalizeCodeLine(String line)
    {
        return codeWithoutComment(line).trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String codeWithoutComment(String line)
    {
        boolean inString = false;
        for (int index = 0; index < line.length() - 1; index++)
        {
            char current = line.charAt(index);
            if (current == '"')
            {
                if (inString && index + 1 < line.length() && line.charAt(index + 1) == '"')
                {
                    index++;
                    continue;
                }
                inString = !inString;
            }
            else if (!inString && current == '/' && line.charAt(index + 1) == '/')
            {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private int lineStart(String text, int offset)
    {
        int index = Math.max(0, Math.min(offset, text.length()));
        while (index > 0)
        {
            char previous = text.charAt(index - 1);
            if (previous == '\n' || previous == '\r')
                break;
            index--;
        }
        return index;
    }

    private int lineEnd(String text, int offset)
    {
        int index = Math.max(0, Math.min(offset, text.length()));
        while (index < text.length())
        {
            char character = text.charAt(index);
            if (character == '\n' || character == '\r')
                break;
            index++;
        }
        return index;
    }

    private int nextLineStart(String text, int lineEnd)
    {
        int index = Math.max(0, Math.min(lineEnd, text.length()));
        if (index < text.length() && text.charAt(index) == '\r')
            index++;
        if (index < text.length() && text.charAt(index) == '\n')
            index++;
        return index;
    }

    private String repeatIndent(int count)
    {
        return INDENT_UNIT.repeat(Math.max(0, count));
    }

    private static final class TextRange
    {
        private final int start;
        private final int end;

        private TextRange(int start, int end)
        {
            this.start = start;
            this.end = end;
        }

        private boolean intersects(ITextSegment segment)
        {
            int segmentStart = segment.getOffset();
            int segmentEnd = Math.max(segment.getEndOffset(), segmentStart + 1);
            return segmentStart < end && segmentEnd > start;
        }
    }

    private static final class FixedTextReplacer
        implements ITextReplacer
    {
        private final ITextSegment region;
        private final String replacement;

        private FixedTextReplacer(ITextSegment region, String replacement)
        {
            this.region = region;
            this.replacement = replacement;
        }

        @Override
        public ITextSegment getRegion()
        {
            return region;
        }

        @Override
        public ITextReplacerContext createReplacements(ITextReplacerContext context)
        {
            context.addReplacement(region.replaceWith(replacement));
            return context;
        }
    }
}
