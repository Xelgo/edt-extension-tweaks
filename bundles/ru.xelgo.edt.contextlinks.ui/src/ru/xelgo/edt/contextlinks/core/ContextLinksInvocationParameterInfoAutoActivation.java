package ru.xelgo.edt.contextlinks.core;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextInputListener;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.custom.CaretEvent;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.xtext.ui.editor.XtextEditor;

/**
 * Lightweight auto trigger for EDT's built-in invocation-parameter hover command.
 */
public final class ContextLinksInvocationParameterInfoAutoActivation
{
    private static final String DATA_KEY = ContextLinks.PLUGIN_ID + ".methodParameterInfoAutoActivation"; //$NON-NLS-1$
    private static final String COMMAND_ID =
        "com._1c.g5.v8.dt.bsl.ui.hover.InvocationParametersHover"; //$NON-NLS-1$
    private static final int DOCUMENT_TRIGGER_DELAY_MS = 120;
    private static final int CARET_TRIGGER_DELAY_MS = 240;
    private static final int WIDGET_COMMAND_RETRY_DELAY_MS = 300;
    private static final int MAX_WIDGET_COMMAND_RETRIES = 10;
    private static final int MAX_SCAN_LENGTH = 1000;
    private static final long REPEAT_SUPPRESSION_MS = 600L;

    private static volatile boolean commandFailureLogged;
    private static volatile boolean globalStarted;
    private static int globalScheduleGeneration;
    private static StyledText lastGlobalTextWidget;
    private static int lastGlobalInvocationOpenOffset = -1;

    private ContextLinksInvocationParameterInfoAutoActivation()
    {
        // Utility class.
    }

    static void install(ISourceViewer sourceViewer)
    {
        if (sourceViewer == null)
            return;

        StyledText textWidget = sourceViewer.getTextWidget();
        if (textWidget == null || textWidget.isDisposed())
            return;

        if (textWidget.getData(DATA_KEY) instanceof AutoActivationState)
            return;

        AutoActivationState state = new AutoActivationState(sourceViewer, textWidget);
        textWidget.setData(DATA_KEY, state);
        textWidget.addCaretListener(state);
        textWidget.addDisposeListener(state);
        sourceViewer.addTextInputListener(state);

        IDocument document = sourceViewer.getDocument();
        if (document != null)
            document.addDocumentListener(state);
    }

    public static void start()
    {
        if (globalStarted)
            return;

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        display.asyncExec(() -> {
            if (globalStarted || display.isDisposed())
                return;

            Listener keyListener = ContextLinksInvocationParameterInfoAutoActivation::handleGlobalKeyEvent;
            Listener mouseListener = event -> scheduleGlobalCommand(CARET_TRIGGER_DELAY_MS);
            display.addFilter(SWT.KeyUp, keyListener);
            display.addFilter(SWT.MouseUp, mouseListener);
            globalStarted = true;
        });
    }

    private static void handleGlobalKeyEvent(Event event)
    {
        if (!ContextLinksPreferences.isMethodParameterInfoAutoShowEnabled())
            return;

        if (event.character == '(' || event.character == ',')
        {
            scheduleGlobalCommand(DOCUMENT_TRIGGER_DELAY_MS);
            return;
        }

        if (event.keyCode == SWT.ARROW_LEFT || event.keyCode == SWT.ARROW_RIGHT || event.keyCode == SWT.ARROW_UP
            || event.keyCode == SWT.ARROW_DOWN || event.keyCode == SWT.HOME || event.keyCode == SWT.END
            || event.keyCode == SWT.PAGE_UP || event.keyCode == SWT.PAGE_DOWN)
        {
            scheduleGlobalCommand(CARET_TRIGGER_DELAY_MS);
        }
    }

    private static void scheduleGlobalCommand(int delayMs)
    {
        if (!ContextLinksPreferences.isMethodParameterInfoAutoShowEnabled())
            return;

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        int generation = ++globalScheduleGeneration;
        display.timerExec(delayMs, () -> executeGlobalCommandIfCurrent(generation));
    }

    private static void executeGlobalCommandIfCurrent(int generation)
    {
        executeGlobalCommandIfCurrent(generation, false);
    }

    static void executeCurrentInvocationCommandSoon(int delayMs)
    {
        if (!ContextLinksPreferences.isMethodParameterInfoAutoShowEnabled())
            return;

        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;

        int generation = ++globalScheduleGeneration;
        display.timerExec(delayMs, () -> executeGlobalCommandIfCurrent(generation, true));
    }

    static void executeCurrentInvocationCommandSoon(StyledText textWidget, int delayMs)
    {
        if (!ContextLinksPreferences.isMethodParameterInfoAutoShowEnabled())
            return;

        if (textWidget == null || textWidget.isDisposed())
            return;

        Display display = textWidget.getDisplay();
        if (display == null || display.isDisposed())
            return;

        int generation = ++globalScheduleGeneration;
        display.timerExec(delayMs, () -> executeWidgetCommandIfCurrent(generation, textWidget, 0));
    }

    private static void executeGlobalCommandIfCurrent(int generation, boolean force)
    {
        if (generation != globalScheduleGeneration)
            return;

        InvocationLocation location = getGlobalInvocationLocation();
        if (location == null)
        {
            lastGlobalTextWidget = null;
            lastGlobalInvocationOpenOffset = -1;
            return;
        }

        if (!force && location.textWidget == lastGlobalTextWidget
            && location.openParenthesisOffset == lastGlobalInvocationOpenOffset)
            return;

        lastGlobalTextWidget = location.textWidget;
        lastGlobalInvocationOpenOffset = location.openParenthesisOffset;
        executeCommand();
    }

    private static void executeWidgetCommandIfCurrent(int generation, StyledText textWidget, int retryAttempt)
    {
        if (generation != globalScheduleGeneration)
            return;

        if (textWidget == null || textWidget.isDisposed())
        {
            lastGlobalTextWidget = null;
            lastGlobalInvocationOpenOffset = -1;
            return;
        }

        InvocationLocation location = getInvocationLocation(textWidget);
        if (location == null)
        {
            lastGlobalTextWidget = null;
            lastGlobalInvocationOpenOffset = -1;
            return;
        }

        boolean focusBefore = textWidget.isFocusControl();
        if (!focusBefore)
            textWidget.setFocus();

        int caretOffset = safeCaretOffset(textWidget);
        lastGlobalTextWidget = location.textWidget;
        lastGlobalInvocationOpenOffset = location.openParenthesisOffset;
        synchronizeActiveTextEditorSelection(caretOffset);
        long parameterInfoRevision = ContextLinksBslDocumentationPatches.getInvocationParameterInfoRevision();
        executeCommand();
        scheduleWidgetCommandRetryIfNeeded(generation, textWidget, location.openParenthesisOffset, caretOffset,
            parameterInfoRevision, retryAttempt);
    }

    private static void scheduleWidgetCommandRetryIfNeeded(int generation, StyledText textWidget, int openOffset,
        int caretOffset, long parameterInfoRevision, int retryAttempt)
    {
        if (retryAttempt >= MAX_WIDGET_COMMAND_RETRIES)
            return;

        if (textWidget == null || textWidget.isDisposed())
            return;

        Display display = textWidget.getDisplay();
        if (display == null || display.isDisposed())
            return;

        int nextRetryAttempt = retryAttempt + 1;
        display.timerExec(WIDGET_COMMAND_RETRY_DELAY_MS,
            () -> retryWidgetCommandIfStillNeeded(generation, textWidget, openOffset, caretOffset,
                parameterInfoRevision, nextRetryAttempt));
    }

    private static void retryWidgetCommandIfStillNeeded(int generation, StyledText textWidget, int openOffset,
        int caretOffset, long parameterInfoRevision, int retryAttempt)
    {
        if (generation != globalScheduleGeneration)
            return;

        if (ContextLinksBslDocumentationPatches.hasAvailableInvocationParameterInfoSince(parameterInfoRevision))
            return;

        if (textWidget == null || textWidget.isDisposed())
            return;

        InvocationLocation location = getInvocationLocation(textWidget);
        int currentCaretOffset = safeCaretOffset(textWidget);
        if (location == null || location.openParenthesisOffset != openOffset || currentCaretOffset != caretOffset)
            return;

        executeWidgetCommandIfCurrent(generation, textWidget, retryAttempt);
    }

    private static InvocationLocation getGlobalInvocationLocation()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || !(display.getFocusControl() instanceof StyledText styledText)
            || styledText.isDisposed())
        {
            return null;
        }

        return getInvocationLocation(styledText);
    }

    private static InvocationLocation getInvocationLocation(StyledText styledText)
    {
        int caretOffset = styledText.getCaretOffset();
        if (caretOffset <= 0)
            return null;

        int safeOffset = Math.min(caretOffset, styledText.getCharCount());
        int scanStart = Math.max(0, safeOffset - MAX_SCAN_LENGTH);
        try
        {
            String prefix = styledText.getTextRange(scanStart, safeOffset - scanStart);
            int relativeOpenOffset = findInvocationOpenParenthesis(prefix);
            if (relativeOpenOffset < 0)
                return null;

            return new InvocationLocation(styledText, scanStart + relativeOpenOffset);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private static void executeCommand()
    {
        if (!PlatformUI.isWorkbenchRunning())
            return;

        try
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return;

            IHandlerService handlerService = window.getService(IHandlerService.class);
            if (handlerService != null)
            {
                handlerService.executeCommand(COMMAND_ID, null);
            }
        }
        catch (Exception e)
        {
            if (!commandFailureLogged)
            {
                commandFailureLogged = true;
                ContextLinks.logError("Failed to auto-show EDT method parameter info", e); //$NON-NLS-1$
            }
        }
    }

    private static void synchronizeActiveTextEditorSelection(int offset)
    {
        if (offset < 0 || !PlatformUI.isWorkbenchRunning())
            return;

        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null)
            return;

        Object activePart = window.getActivePage().getActivePart();
        if (synchronizeEditorSelection(activePart, offset, "activePart")) //$NON-NLS-1$
            return;

        IEditorPart activeEditor = window.getActivePage().getActiveEditor();
        if (activeEditor != activePart && synchronizeEditorSelection(activeEditor, offset, "activeEditor")) //$NON-NLS-1$
            return;
    }

    private static boolean synchronizeEditorSelection(Object part, int offset, String source)
    {
        ITextEditor textEditor = extractTextEditor(part);
        if (textEditor == null)
            return false;

        try
        {
            textEditor.selectAndReveal(offset, 0);

            Object target = textEditor.getAdapter(ITextOperationTarget.class);
            if (target instanceof ITextViewer textViewer)
            {
                textViewer.revealRange(offset, 0);
                textViewer.setSelectedRange(offset, 0);
            }
            return true;
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    private static ITextEditor extractTextEditor(Object part)
    {
        if (part instanceof ITextEditor textEditor)
            return textEditor;

        if (!(part instanceof org.eclipse.ui.IWorkbenchPart workbenchPart))
            return null;

        Object adaptedTextEditor = workbenchPart.getAdapter(ITextEditor.class);
        if (adaptedTextEditor instanceof ITextEditor textEditor)
            return textEditor;

        Object adaptedXtextEditor = workbenchPart.getAdapter(XtextEditor.class);
        if (adaptedXtextEditor instanceof ITextEditor textEditor)
            return textEditor;

        return null;
    }

    private static int safeCaretOffset(StyledText textWidget)
    {
        try
        {
            return textWidget != null && !textWidget.isDisposed() ? textWidget.getCaretOffset() : -1;
        }
        catch (RuntimeException e)
        {
            return -1;
        }
    }

    private static boolean containsOpenInvocationParenthesis(String prefix)
    {
        return findInvocationOpenParenthesis(prefix) >= 0;
    }

    private static int findInvocationOpenParenthesis(String prefix)
    {
        int nestedClosedParentheses = 0;
        for (int index = prefix.length() - 1; index >= 0; index--)
        {
            char character = prefix.charAt(index);
            if (character == ')')
            {
                nestedClosedParentheses++;
            }
            else if (character == '(')
            {
                if (nestedClosedParentheses == 0)
                    return hasInvocationTokenBefore(prefix, index) ? index : -1;
                nestedClosedParentheses--;
            }
            else if (character == ';' && nestedClosedParentheses == 0)
            {
                return -1;
            }
        }
        return -1;
    }

    private static boolean hasInvocationTokenBefore(String prefix, int openParenthesisIndex)
    {
        int index = openParenthesisIndex - 1;
        while (index >= 0 && Character.isWhitespace(prefix.charAt(index)))
            index--;

        if (index < 0)
            return false;

        char previous = prefix.charAt(index);
        return Character.isLetterOrDigit(previous) || previous == '_' || previous == ')' || previous == ']';
    }

    private static final class InvocationLocation
    {
        private final StyledText textWidget;
        private final int openParenthesisOffset;

        InvocationLocation(StyledText textWidget, int openParenthesisOffset)
        {
            this.textWidget = textWidget;
            this.openParenthesisOffset = openParenthesisOffset;
        }
    }

    private static final class AutoActivationState
        implements IDocumentListener, ITextInputListener, CaretListener, DisposeListener
    {
        private final ISourceViewer sourceViewer;
        private final StyledText textWidget;
        private boolean disposed;
        private int scheduleGeneration;
        private int lastCommandOffset = -1;
        private long lastCommandTimestamp;

        AutoActivationState(ISourceViewer sourceViewer, StyledText textWidget)
        {
            this.sourceViewer = sourceViewer;
            this.textWidget = textWidget;
        }

        @Override
        public void documentAboutToBeChanged(DocumentEvent event)
        {
            // Nothing to do before a change.
        }

        @Override
        public void documentChanged(DocumentEvent event)
        {
            if (!ContextLinksPreferences.isMethodParameterInfoAutoShowEnabled())
                return;

            String text = event.getText();
            if (text != null && (text.indexOf('(') >= 0 || text.indexOf(',') >= 0))
                schedule(DOCUMENT_TRIGGER_DELAY_MS);
        }

        @Override
        public void inputDocumentAboutToBeChanged(IDocument oldInput, IDocument newInput)
        {
            if (oldInput != null)
                oldInput.removeDocumentListener(this);
        }

        @Override
        public void inputDocumentChanged(IDocument oldInput, IDocument newInput)
        {
            if (newInput != null)
                newInput.addDocumentListener(this);
        }

        @Override
        public void caretMoved(CaretEvent event)
        {
            if (!ContextLinksPreferences.isMethodParameterInfoAutoShowEnabled())
                return;

            if (looksLikeInvocationArguments())
                schedule(CARET_TRIGGER_DELAY_MS);
        }

        @Override
        public void widgetDisposed(DisposeEvent event)
        {
            dispose();
        }

        private void schedule(int delayMs)
        {
            if (disposed || textWidget.isDisposed())
                return;

            Display display = textWidget.getDisplay();
            int generation = ++scheduleGeneration;
            display.timerExec(delayMs, () -> executeIfCurrent(generation));
        }

        private void executeIfCurrent(int generation)
        {
            if (disposed || generation != scheduleGeneration || textWidget.isDisposed())
                return;

            if (!ContextLinksPreferences.isMethodParameterInfoAutoShowEnabled())
                return;

            if (!looksLikeInvocationArguments())
                return;

            int offset = getCurrentModelOffset();
            long now = System.currentTimeMillis();
            if (offset == lastCommandOffset && now - lastCommandTimestamp < REPEAT_SUPPRESSION_MS)
                return;

            lastCommandOffset = offset;
            lastCommandTimestamp = now;
            ContextLinksInvocationParameterInfoAutoActivation.executeCommand();
        }

        private boolean looksLikeInvocationArguments()
        {
            IDocument document = sourceViewer.getDocument();
            int offset = getCurrentModelOffset();
            if (document == null || offset <= 0)
                return false;

            try
            {
                int safeOffset = Math.min(offset, document.getLength());
                int scanStart = Math.max(0, safeOffset - MAX_SCAN_LENGTH);
                String prefix = document.get(scanStart, safeOffset - scanStart);
                return containsOpenInvocationParenthesis(prefix);
            }
            catch (BadLocationException e)
            {
                return false;
            }
        }

        private int getCurrentModelOffset()
        {
            Point selectedRange = sourceViewer.getSelectedRange();
            return selectedRange != null ? selectedRange.x : -1;
        }

        private void dispose()
        {
            if (disposed)
                return;

            disposed = true;
            sourceViewer.removeTextInputListener(this);
            IDocument document = sourceViewer.getDocument();
            if (document != null)
                document.removeDocumentListener(this);

            if (!textWidget.isDisposed())
            {
                textWidget.removeCaretListener(this);
                textWidget.removeDisposeListener(this);
                textWidget.setData(DATA_KEY, null);
            }
        }
    }
}
