package ru.xelgo.edt.contextlinks.core;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.xtext.ParserRule;
import org.eclipse.xtext.RuleCall;
import org.eclipse.xtext.parser.IParseResult;
import org.eclipse.xtext.util.ReplaceRegion;

import com._1c.g5.v8.dt.bsl.parser.antlr.BslParser;

/**
 * Normalizes dirty configurator extension markers at parser entry points.
 */
public class ContextLinksBslParser
    extends BslParser
{
    private static final int LOG_LIMIT = Integer.getInteger(
        ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.parserReparseLogLimit", 20); //$NON-NLS-1$
    private static final AtomicInteger loggedReparseDecisions = new AtomicInteger();

    @Override
    public IParseResult doParse(Reader reader)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return super.doParse(reader != null ? reader : new StringReader("")); //$NON-NLS-1$

        long id = DirtyPreprocessorDiagnostics.nextId();
        long started = System.nanoTime();
        String text = read(reader);
        DirtyPreprocessorNormalizer.NormalizationResult normalizedResult =
            normalizeResult("parser.doParse.reader", text); //$NON-NLS-1$

        String normalized = normalizedResult.getText();
        DirtyPreprocessorDiagnostics.logStart(id, "parser.doParse.reader", details(text, normalized, null)); //$NON-NLS-1$
        IParseResult result = super.doParse(new StringReader(normalized));
        DirtyPreprocessorDiagnostics.logEnd(id, "parser.doParse.reader", started, //$NON-NLS-1$
            details(text, normalized, result));
        return result;
    }

    @Override
    public IParseResult doParse(CharSequence sequence)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return super.doParse(sequence != null ? sequence : ""); //$NON-NLS-1$

        long id = DirtyPreprocessorDiagnostics.nextId();
        long started = System.nanoTime();
        String text = sequence != null ? sequence.toString() : ""; //$NON-NLS-1$
        DirtyPreprocessorNormalizer.NormalizationResult normalizedResult =
            normalizeResult("parser.doParse.sequence", text); //$NON-NLS-1$

        String normalized = normalizedResult.getText();
        DirtyPreprocessorDiagnostics.logStart(id, "parser.doParse.sequence", details(text, normalized, null)); //$NON-NLS-1$
        IParseResult result = super.doParse(normalized);
        DirtyPreprocessorDiagnostics.logEnd(id, "parser.doParse.sequence", started, //$NON-NLS-1$
            details(text, normalized, result));
        return result;
    }

    @Override
    public IParseResult parse(ParserRule rule, Reader reader)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return super.parse(rule, reader != null ? reader : new StringReader("")); //$NON-NLS-1$

        long id = DirtyPreprocessorDiagnostics.nextId();
        long started = System.nanoTime();
        String text = read(reader);
        DirtyPreprocessorNormalizer.NormalizationResult normalizedResult =
            normalizeResult("parser.parse.rule.reader", text); //$NON-NLS-1$

        String normalized = normalizedResult.getText();
        DirtyPreprocessorDiagnostics.logStart(id, "parser.parse.rule.reader", //$NON-NLS-1$
            details(text, normalized, null) + " rule=" + (rule != null ? rule.getName() : "NULL")); //$NON-NLS-1$ //$NON-NLS-2$
        IParseResult result = super.parse(rule, new StringReader(normalized));
        DirtyPreprocessorDiagnostics.logEnd(id, "parser.parse.rule.reader", started, //$NON-NLS-1$
            details(text, normalized, result) + " rule=" + (rule != null ? rule.getName() : "NULL")); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    @Override
    public IParseResult parse(RuleCall ruleCall, Reader reader, int initialLookAhead)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return super.parse(ruleCall, reader != null ? reader : new StringReader(""), initialLookAhead); //$NON-NLS-1$

        long id = DirtyPreprocessorDiagnostics.nextId();
        long started = System.nanoTime();
        String text = read(reader);
        DirtyPreprocessorNormalizer.NormalizationResult normalizedResult =
            normalizeResult("parser.parse.rulecall.reader", text); //$NON-NLS-1$

        String normalized = normalizedResult.getText();
        DirtyPreprocessorDiagnostics.logStart(id, "parser.parse.rulecall.reader", //$NON-NLS-1$
            details(text, normalized, null) + " lookAhead=" + initialLookAhead); //$NON-NLS-1$
        IParseResult result = super.parse(ruleCall, new StringReader(normalized), initialLookAhead);
        DirtyPreprocessorDiagnostics.logEnd(id, "parser.parse.rulecall.reader", started, //$NON-NLS-1$
            details(text, normalized, result) + " lookAhead=" + initialLookAhead); //$NON-NLS-1$
        return result;
    }

    @Override
    protected IParseResult doReparse(IParseResult previousParseResult, ReplaceRegion replaceRegion)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return super.doReparse(previousParseResult, replaceRegion);

        long id = DirtyPreprocessorDiagnostics.nextId();
        long started = System.nanoTime();
        if (previousParseResult == null || previousParseResult.getRootNode() == null
            || replaceRegion == null || replaceRegion.getText() == null)
        {
            DirtyPreprocessorDiagnostics.logStart(id, "parser.doReparse.raw-invalid", replaceDetails(replaceRegion)); //$NON-NLS-1$
            IParseResult parseResult = super.doReparse(previousParseResult, replaceRegion);
            DirtyPreprocessorDiagnostics.logEnd(id, "parser.doReparse.raw-invalid", started, //$NON-NLS-1$
                replaceDetails(replaceRegion) + " result=" + resultDetails(parseResult)); //$NON-NLS-1$
            return parseResult;
        }

        if (replaceRegion.getLength() == 0 && replaceRegion.getText().isEmpty())
        {
            DirtyPreprocessorDiagnostics.logStart(id, "parser.doReparse.noop", replaceDetails(replaceRegion)); //$NON-NLS-1$
            IParseResult parseResult = super.doReparse(previousParseResult, replaceRegion);
            DirtyPreprocessorDiagnostics.logEnd(id, "parser.doReparse.noop", started, //$NON-NLS-1$
                replaceDetails(replaceRegion) + " result=" + resultDetails(parseResult)); //$NON-NLS-1$
            return parseResult;
        }

        DirtyPreprocessorNormalizer.NormalizationResult result =
            normalizeResult("parser.reparse.replace-region", replaceRegion.getText()); //$NON-NLS-1$

        if (!result.isChanged())
        {
            DirtyPreprocessorDiagnostics.logStart(id, "parser.doReparse.raw", replaceDetails(replaceRegion)); //$NON-NLS-1$
            IParseResult parseResult = super.doReparse(previousParseResult, replaceRegion);
            DirtyPreprocessorDiagnostics.logEnd(id, "parser.doReparse.raw", started, //$NON-NLS-1$
                replaceDetails(replaceRegion) + " result=" + resultDetails(parseResult)); //$NON-NLS-1$
            return parseResult;
        }

        String previousText = previousParseResult.getRootNode().getText();
        String rawPreviousText = DirtyPreprocessorSourceCache.getByNormalized(previousText);
        logReparseDecision("incremental-normalized", containsDirtyServiceComments(previousText), true, replaceRegion, //$NON-NLS-1$
            previousText, rawPreviousText);
        DirtyPreprocessorDiagnostics.logStart(id, "parser.doReparse.normalized", //$NON-NLS-1$
            replaceDetails(replaceRegion) + " previousLength=" + previousText.length() //$NON-NLS-1$
                + " rawPrevious=" + (rawPreviousText != null)); //$NON-NLS-1$

        String normalizedUpdatedText =
            replaceText(previousText, replaceRegion.getOffset(), replaceRegion.getLength(), result.getText());
        if (rawPreviousText != null && normalizedUpdatedText != null)
        {
            String rawUpdatedText = replaceText(rawPreviousText, replaceRegion.getOffset(), replaceRegion.getLength(),
                replaceRegion.getText());
            if (rawUpdatedText != null)
                DirtyPreprocessorSourceCache.rememberNormalized(normalizedUpdatedText, rawUpdatedText);
        }

        IParseResult parseResult = super.doReparse(previousParseResult,
            new ReplaceRegion(replaceRegion.getOffset(), replaceRegion.getLength(), result.getText()));
        DirtyPreprocessorDiagnostics.logEnd(id, "parser.doReparse.normalized", started, //$NON-NLS-1$
            replaceDetails(replaceRegion) + " result=" + resultDetails(parseResult)); //$NON-NLS-1$
        return parseResult;
    }

    private static void logReparseDecision(String phase, boolean dirtyModel, boolean replacementChanged,
        ReplaceRegion replaceRegion, String previousText, String rawPreviousText)
    {
        if (!ContextLinks.isDebugLoggingEnabled() || loggedReparseDecisions.getAndIncrement() >= LOG_LIMIT)
            return;

        ContextLinks.logDebug("EDT Extension Tweaks dirty parser reparse phase=" + phase //$NON-NLS-1$
            + " dirtyModel=" + dirtyModel //$NON-NLS-1$
            + " replacementChanged=" + replacementChanged //$NON-NLS-1$
            + " offset=" + replaceRegion.getOffset() //$NON-NLS-1$
            + " length=" + replaceRegion.getLength() //$NON-NLS-1$
            + " replacementLength=" + replaceRegion.getText().length() //$NON-NLS-1$
            + " previousLength=" + previousText.length() //$NON-NLS-1$
            + " rawPrevious=" + (rawPreviousText != null) //$NON-NLS-1$
            + " stack=" + stackTraceSummary()); //$NON-NLS-1$
    }

    private static String stackTraceSummary()
    {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (StackTraceElement frame : stack)
        {
            String className = frame.getClassName();
            if (className.equals(Thread.class.getName()) || className.equals(ContextLinksBslParser.class.getName()))
                continue;

            if (builder.length() > 0)
                builder.append(" <- "); //$NON-NLS-1$
            builder.append(className).append('#').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            count++;
            if (count >= 12)
                break;
        }
        return builder.toString();
    }

    private static boolean containsDirtyServiceComments(String text)
    {
        return text != null && text.indexOf("//ETW") >= 0; //$NON-NLS-1$
    }

    private static String replaceText(String text, int offset, int length, String replacement)
    {
        if (text == null || replacement == null || offset < 0 || length < 0 || offset > text.length()
            || offset + length > text.length())
            return null;

        return text.substring(0, offset) + replacement + text.substring(offset + length);
    }

    private static String details(String text, String normalized, IParseResult result)
    {
        return "length=" + safeLength(text) //$NON-NLS-1$
            + " normalizedLength=" + safeLength(normalized) //$NON-NLS-1$
            + " changed=" + (text != normalized && text != null && !text.equals(normalized)) //$NON-NLS-1$
            + " markerOffset=" + markerOffset(text) //$NON-NLS-1$
            + " result=" + resultDetails(result); //$NON-NLS-1$
    }

    private static String replaceDetails(ReplaceRegion replaceRegion)
    {
        if (replaceRegion == null)
            return "replace=NULL"; //$NON-NLS-1$

        String text = replaceRegion.getText();
        return "offset=" + replaceRegion.getOffset() //$NON-NLS-1$
            + " length=" + replaceRegion.getLength() //$NON-NLS-1$
            + " replacementLength=" + safeLength(text) //$NON-NLS-1$
            + " markerOffset=" + markerOffset(text); //$NON-NLS-1$
    }

    private static String resultDetails(IParseResult result)
    {
        if (result == null)
            return "NULL"; //$NON-NLS-1$

        return "{syntaxErrors=" + result.hasSyntaxErrors() //$NON-NLS-1$
            + ",rootLength=" + (result.getRootNode() != null ? result.getRootNode().getLength() : -1) + '}'; //$NON-NLS-1$
    }

    private static int safeLength(String text)
    {
        return text != null ? text.length() : -1;
    }

    private static int markerOffset(String text)
    {
        return text != null ? text.indexOf('#') : -1;
    }

    private static DirtyPreprocessorNormalizer.NormalizationResult normalizeResult(String stage, String text)
    {
        if (!ContextLinksPreferences.isDirtyPreprocessorEnabled())
            return new DirtyPreprocessorNormalizer.NormalizationResult(text, false, 0, 0, 0);

        DirtyPreprocessorNormalizer.NormalizationResult result = DirtyPreprocessorNormalizer.normalize(text);
        if (result.isChanged())
            DirtyPreprocessorSourceCache.rememberNormalized(result.getText(), text);
        DirtyPreprocessorLogger.logNormalized(stage, "PARSER_TEXT", text, result); //$NON-NLS-1$
        return result;
    }

    private static String read(Reader reader)
    {
        if (reader == null)
            return ""; //$NON-NLS-1$

        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[8192];
        try
        {
            int read;
            while ((read = reader.read(buffer)) >= 0)
                builder.append(buffer, 0, read);
        }
        catch (IOException e)
        {
            ContextLinks.logError("Failed to read BSL parser input for dirty preprocessor normalization", e); //$NON-NLS-1$
        }
        return builder.toString();
    }
}
