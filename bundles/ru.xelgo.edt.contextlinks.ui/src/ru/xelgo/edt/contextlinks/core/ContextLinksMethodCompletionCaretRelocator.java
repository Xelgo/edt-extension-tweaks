package ru.xelgo.edt.contextlinks.core;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CaretEvent;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.ExtendedModifyEvent;
import org.eclipse.swt.custom.ExtendedModifyListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Moves the caret into the first empty argument after EDT inserts a method call proposal.
 */
public final class ContextLinksMethodCompletionCaretRelocator
{
    private static final String DATA_KEY = ContextLinks.PLUGIN_ID + ".methodCompletionCaretRelocator"; //$NON-NLS-1$
    private static final int RELOCATION_DELAY_MS = 80;
    private static final int RETRY_SCAN_DELAY_MS = 80;
    private static final int PARAMETER_INFO_DELAY_MS = 180;
    private static final int BURST_RESET_MS = 2000;
    private static final int COMPLETION_WATCH_MS = 8000;
    private static final int MAX_RETRY_SCANS = 60;
    private static final int MIN_COMPLETION_EVENTS = 4;
    private static final int MIN_COMPLETION_CHANGED_CHARS = 6;
    private static final int PREFIX_SCAN_LENGTH = 400;
    private static final int SUFFIX_SCAN_LENGTH = 1200;
    private static final int RELATED_PREFIX_MARGIN = 160;
    private static final int RELATED_SUFFIX_MARGIN = 80;
    private static final int STATEMENT_CONTEXT_SCAN_LENGTH = 1200;

    private static volatile boolean started;
    private static volatile boolean failureLogged;

    private ContextLinksMethodCompletionCaretRelocator()
    {
        // Utility class.
    }

    public static void start()
    {
        if (started)
            return;

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        display.asyncExec(() -> {
            if (started || display.isDisposed())
                return;

            Listener focusListener = ContextLinksMethodCompletionCaretRelocator::handleFocusIn;
            display.addFilter(SWT.FocusIn, focusListener);
            if (display.getFocusControl() instanceof StyledText styledText)
                install(styledText);

            started = true;
        });
    }

    private static void handleFocusIn(Event event)
    {
        if (event.widget instanceof StyledText styledText)
            install(styledText);
    }

    private static void install(StyledText textWidget)
    {
        if (textWidget == null || textWidget.isDisposed())
            return;

        if (textWidget.getData(DATA_KEY) instanceof RelocationState)
            return;

        RelocationState state = new RelocationState(textWidget);
        textWidget.setData(DATA_KEY, state);
        textWidget.addCaretListener(state);
        textWidget.addExtendedModifyListener(state);
        textWidget.addDisposeListener(state);
    }

    private static RelocationCandidate findCandidate(StyledText textWidget, int changedStart, int changedEnd)
    {
        int charCount = textWidget.getCharCount();
        int caretOffset = Math.max(0, Math.min(textWidget.getCaretOffset(), charCount));
        if (charCount == 0)
            return null;

        int normalizedChangedStart = Math.max(0, Math.min(changedStart, charCount));
        int normalizedChangedEnd = Math.max(normalizedChangedStart, Math.min(changedEnd, charCount));
        if (changedStart < 0 || changedEnd < 0)
        {
            normalizedChangedStart = caretOffset;
            normalizedChangedEnd = caretOffset;
        }

        int scanStart = Math.max(0, Math.min(caretOffset, normalizedChangedStart) - PREFIX_SCAN_LENGTH);
        int scanEnd = Math.min(charCount, Math.max(caretOffset, normalizedChangedEnd) + SUFFIX_SCAN_LENGTH);
        String fragment = textWidget.getTextRange(scanStart, scanEnd - scanStart);

        RelocationCandidate bestCandidate = null;
        int bestScore = Integer.MAX_VALUE;
        for (int close = 0; close < fragment.length(); close++)
        {
            if (fragment.charAt(close) != ')')
                continue;

            int open = findMatchingOpenParenthesis(fragment, close);
            if (open < 0)
                continue;

            int absoluteOpen = scanStart + open;
            int absoluteClose = scanStart + close;
            if (!isRelatedToChangedRangeOrCaret(absoluteOpen, absoluteClose, normalizedChangedStart,
                normalizedChangedEnd, caretOffset))
            {
                continue;
            }

            if (!hasInvocationTokenBefore(fragment, open))
                continue;

            String arguments = fragment.substring(open + 1, close);
            if (!isEmptyArgumentTemplate(arguments))
                continue;

            int target = open + 1;
            while (target < close && Character.isWhitespace(fragment.charAt(target)))
                target++;

            if (!isCaretStillAtCompletionTail(textWidget, absoluteClose, caretOffset))
                continue;

            int score = Math.min(Math.abs(caretOffset - (absoluteClose + 1)),
                Math.abs(normalizedChangedEnd - absoluteOpen));
            if (score < bestScore)
            {
                bestScore = score;
                bestCandidate =
                    new RelocationCandidate(absoluteOpen, absoluteClose, scanStart + target, caretOffset);
            }
        }

        if (bestCandidate == null)
            return null;

        return bestCandidate;
    }

    private static boolean isRelatedToChangedRangeOrCaret(int openOffset, int closeOffset, int changedStart,
        int changedEnd, int caretOffset)
    {
        int relatedStart = Math.max(0, openOffset - RELATED_PREFIX_MARGIN);
        int relatedEnd = closeOffset + RELATED_SUFFIX_MARGIN;
        if (changedStart <= relatedEnd && changedEnd >= relatedStart)
        {
            return true;
        }

        return caretOffset >= openOffset - 1 && caretOffset <= relatedEnd;
    }

    private static int findMatchingOpenParenthesis(String text, int closeParenthesisIndex)
    {
        int nested = 0;
        for (int index = closeParenthesisIndex; index >= 0; index--)
        {
            char character = text.charAt(index);
            if (character == ')')
            {
                nested++;
            }
            else if (character == '(')
            {
                nested--;
                if (nested == 0)
                    return index;
            }
            else if (character == ';' && nested <= 1)
            {
                return -1;
            }
        }
        return -1;
    }

    private static boolean hasInvocationTokenBefore(String text, int openParenthesisIndex)
    {
        int index = openParenthesisIndex - 1;
        while (index >= 0 && Character.isWhitespace(text.charAt(index)))
            index--;

        if (index < 0)
            return false;

        char previous = text.charAt(index);
        return Character.isLetterOrDigit(previous) || previous == '_' || previous == ')' || previous == ']';
    }

    private static boolean isEmptyArgumentTemplate(String arguments)
    {
        boolean hasComma = false;
        for (int index = 0; index < arguments.length(); index++)
        {
            char character = arguments.charAt(index);
            if (character == ',')
            {
                hasComma = true;
            }
            else if (!Character.isWhitespace(character))
            {
                return false;
            }
        }
        return hasComma;
    }

    private static boolean isCaretStillAtCompletionTail(StyledText textWidget, int closeOffset, int caretOffset)
    {
        int completionTailOffset = closeOffset + 1;
        if (caretOffset == completionTailOffset)
            return true;

        if (caretOffset < completionTailOffset || caretOffset > completionTailOffset + 3)
            return false;

        String trailingText = textWidget.getTextRange(completionTailOffset, caretOffset - completionTailOffset);
        for (int index = 0; index < trailingText.length(); index++)
        {
            char character = trailingText.charAt(index);
            if (character != ';' && character != ' ' && character != '\t')
                return false;
        }
        return true;
    }

    private static boolean insertStatementSemicolonIfNeeded(StyledText textWidget, RelocationCandidate candidate)
    {
        int insertOffset = candidate.closeOffset + 1;
        int charCount = textWidget.getCharCount();
        if (insertOffset > charCount)
            return false;

        if (hasOpenParenthesisBeforeInvocation(textWidget, candidate.openOffset))
            return false;

        int nextTokenOffset = findNextSameLineTokenOffset(textWidget, insertOffset);
        if (nextTokenOffset >= 0)
        {
            char nextToken = textWidget.getTextRange(nextTokenOffset, 1).charAt(0);
            if (nextToken == ';')
                return false;

            if (!isLineCommentStart(textWidget, nextTokenOffset))
                return false;
        }

        textWidget.replaceTextRange(insertOffset, 0, ";"); //$NON-NLS-1$
        return true;
    }

    private static int findNextSameLineTokenOffset(StyledText textWidget, int offset)
    {
        int charCount = textWidget.getCharCount();
        int currentOffset = Math.max(0, Math.min(offset, charCount));
        while (currentOffset < charCount)
        {
            char character = textWidget.getTextRange(currentOffset, 1).charAt(0);
            if (character == '\r' || character == '\n')
                return -1;

            if (character != ' ' && character != '\t')
                return currentOffset;

            currentOffset++;
        }
        return -1;
    }

    private static boolean isLineCommentStart(StyledText textWidget, int offset)
    {
        return offset + 1 < textWidget.getCharCount() && textWidget.getTextRange(offset, 2).equals("//"); //$NON-NLS-1$
    }

    private static boolean hasOpenParenthesisBeforeInvocation(StyledText textWidget, int openOffset)
    {
        int safeOpenOffset = Math.max(0, Math.min(openOffset, textWidget.getCharCount()));
        int scanStart = Math.max(0, safeOpenOffset - STATEMENT_CONTEXT_SCAN_LENGTH);
        String prefix = textWidget.getTextRange(scanStart, safeOpenOffset - scanStart);
        int parenthesisBalance = 0;
        boolean inString = false;
        boolean inComment = false;

        for (int index = 0; index < prefix.length(); index++)
        {
            char character = prefix.charAt(index);
            char nextCharacter = index + 1 < prefix.length() ? prefix.charAt(index + 1) : '\0';

            if (inComment)
            {
                if (character == '\r' || character == '\n')
                    inComment = false;
                continue;
            }

            if (inString)
            {
                if (character == '"')
                {
                    if (nextCharacter == '"')
                    {
                        index++;
                    }
                    else
                    {
                        inString = false;
                    }
                }
                continue;
            }

            if (character == '/' && nextCharacter == '/')
            {
                inComment = true;
                index++;
                continue;
            }

            if (character == '"')
            {
                inString = true;
                continue;
            }

            if (character == '(')
            {
                parenthesisBalance++;
            }
            else if (character == ')')
            {
                if (parenthesisBalance > 0)
                    parenthesisBalance--;
            }
            else if (character == ';' && parenthesisBalance == 0)
            {
                parenthesisBalance = 0;
            }
        }

        return parenthesisBalance > 0;
    }

    private static void logFailure(RuntimeException e)
    {
        if (!failureLogged)
        {
            failureLogged = true;
            ContextLinks.logError("Failed to relocate caret after EDT method completion", e); //$NON-NLS-1$
        }
    }

    private static final class RelocationState
        implements ExtendedModifyListener, DisposeListener, CaretListener
    {
        private final StyledText textWidget;
        private boolean disposed;
        private int scheduleGeneration;
        private long lastModifyAtMillis;
        private int burstEventCount;
        private int burstInsertedChars;
        private int burstReplacedChars;
        private boolean completionBurstActive;
        private int burstStartOffset = -1;
        private int burstEndOffset = -1;
        private long completionWatchUntilMillis;
        private int retryScanCount;
        private boolean internalEditInProgress;

        RelocationState(StyledText textWidget)
        {
            this.textWidget = textWidget;
        }

        @Override
        public void modifyText(ExtendedModifyEvent event)
        {
            if (internalEditInProgress)
                return;

            if (disposed || textWidget.isDisposed())
                return;

            if (!ContextLinksPreferences.isMethodCompletionCaretToFirstParameterEnabled())
                return;

            try
            {
                String replacedText = event.replacedText == null ? "" : event.replacedText; //$NON-NLS-1$
                int replacedLength = replacedText.length();
                updateModificationBurst(event.start, event.length, replacedLength);
                boolean completionLike = isCompletionLikeModification(event.length, replacedLength);
                if (completionLike)
                    activateCompletionWatch();
                if (isCompletionWatchActive())
                    scheduleScan(RELOCATION_DELAY_MS);
            }
            catch (RuntimeException e)
            {
                logFailure(e);
            }
        }

        @Override
        public void caretMoved(CaretEvent event)
        {
            if (disposed || textWidget.isDisposed() || !ContextLinksPreferences.isMethodCompletionCaretToFirstParameterEnabled())
                return;

            try
            {
                if (!isCompletionWatchActive())
                    return;

                scheduleScan(RETRY_SCAN_DELAY_MS);
            }
            catch (RuntimeException e)
            {
                logFailure(e);
            }
        }

        @Override
        public void widgetDisposed(DisposeEvent event)
        {
            dispose();
        }

        private void updateModificationBurst(int startOffset, int insertedLength, int replacedLength)
        {
            long now = System.currentTimeMillis();
            if (lastModifyAtMillis == 0 || now - lastModifyAtMillis > BURST_RESET_MS)
            {
                resetBurst();
            }

            lastModifyAtMillis = now;
            burstEventCount++;
            burstInsertedChars += Math.max(0, insertedLength);
            burstReplacedChars += Math.max(0, replacedLength);

            int changedStart = Math.max(0, startOffset);
            int changedEnd = Math.max(changedStart, changedStart + Math.max(insertedLength, replacedLength));
            if (burstStartOffset < 0 || changedStart < burstStartOffset)
                burstStartOffset = changedStart;
            if (changedEnd > burstEndOffset)
                burstEndOffset = changedEnd;
        }

        private boolean isCompletionLikeModification(int insertedLength, int replacedLength)
        {
            return insertedLength >= MIN_COMPLETION_CHANGED_CHARS
                || replacedLength >= MIN_COMPLETION_CHANGED_CHARS
                || burstEventCount >= MIN_COMPLETION_EVENTS
                || burstInsertedChars + burstReplacedChars >= MIN_COMPLETION_CHANGED_CHARS;
        }

        private void activateCompletionWatch()
        {
            completionBurstActive = true;
            completionWatchUntilMillis = System.currentTimeMillis() + COMPLETION_WATCH_MS;
            retryScanCount = 0;
        }

        private boolean isCompletionWatchActive()
        {
            if (!completionBurstActive)
                return false;

            if (System.currentTimeMillis() <= completionWatchUntilMillis)
                return true;

            resetBurst();
            return false;
        }

        private void scheduleScan(int delayMillis)
        {
            Display display = textWidget.getDisplay();
            int generation = ++scheduleGeneration;
            int changedStart = burstStartOffset;
            int changedEnd = burstEndOffset;
            display.timerExec(delayMillis, () -> relocateIfCurrent(generation, changedStart, changedEnd));
        }

        private void relocateIfCurrent(int generation, int changedStart, int changedEnd)
        {
            if (disposed || generation != scheduleGeneration || textWidget.isDisposed()
                || !ContextLinksPreferences.isMethodCompletionCaretToFirstParameterEnabled())
                return;

            try
            {
                RelocationCandidate candidate = findCandidate(textWidget, changedStart, changedEnd);
                if (candidate == null)
                {
                    if (isCompletionWatchActive() && retryScanCount < MAX_RETRY_SCANS)
                    {
                        retryScanCount++;
                        scheduleScan(RETRY_SCAN_DELAY_MS);
                    }
                    else
                    {
                        resetBurst();
                    }
                    return;
                }

                if (!isCandidateStillCurrent(candidate))
                {
                    completionBurstActive = false;
                    return;
                }

                int caretOffset = textWidget.getCaretOffset();
                if (!isCaretStillAtCompletionTail(textWidget, candidate.closeOffset, caretOffset))
                {
                    resetBurst();
                    return;
                }

                if (caretOffset != candidate.caretOffset)
                {
                    completionBurstActive = false;
                    return;
                }

                if (caretOffset == candidate.targetOffset)
                {
                    resetBurst();
                    return;
                }

                completionBurstActive = false;
                insertTerminatorBeforeRelocation(candidate);
                textWidget.setSelection(candidate.targetOffset);
                textWidget.showSelection();
                ContextLinksInvocationParameterInfoAutoActivation.executeCurrentInvocationCommandSoon(textWidget,
                    PARAMETER_INFO_DELAY_MS);
                resetBurst();
            }
            catch (RuntimeException e)
            {
                logFailure(e);
            }
        }

        private void insertTerminatorBeforeRelocation(RelocationCandidate candidate)
        {
            internalEditInProgress = true;
            try
            {
                insertStatementSemicolonIfNeeded(textWidget, candidate);
            }
            finally
            {
                internalEditInProgress = false;
            }
        }

        private boolean isCandidateStillCurrent(RelocationCandidate candidate)
        {
            int charCount = textWidget.getCharCount();
            if (candidate.openOffset < 0 || candidate.closeOffset >= charCount
                || candidate.targetOffset > candidate.closeOffset)
            {
                return false;
            }

            if (textWidget.getTextRange(candidate.openOffset, 1).charAt(0) != '('
                || textWidget.getTextRange(candidate.closeOffset, 1).charAt(0) != ')')
            {
                return false;
            }

            String arguments = textWidget.getTextRange(candidate.openOffset + 1,
                candidate.closeOffset - candidate.openOffset - 1);
            return isEmptyArgumentTemplate(arguments);
        }

        private void dispose()
        {
            if (disposed)
                return;

            disposed = true;
            if (!textWidget.isDisposed())
            {
                textWidget.removeCaretListener(this);
                textWidget.removeExtendedModifyListener(this);
                textWidget.removeDisposeListener(this);
                textWidget.setData(DATA_KEY, null);
            }
        }

        private void resetBurst()
        {
            burstEventCount = 0;
            burstInsertedChars = 0;
            burstReplacedChars = 0;
            completionBurstActive = false;
            burstStartOffset = -1;
            burstEndOffset = -1;
            completionWatchUntilMillis = 0;
            retryScanCount = 0;
        }
    }

    private static final class RelocationCandidate
    {
        private final int openOffset;
        private final int closeOffset;
        private final int targetOffset;
        private final int caretOffset;

        RelocationCandidate(int openOffset, int closeOffset, int targetOffset, int caretOffset)
        {
            this.openOffset = openOffset;
            this.closeOffset = closeOffset;
            this.targetOffset = targetOffset;
            this.caretOffset = caretOffset;
        }
    }
}
