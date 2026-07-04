package ru.xelgo.edt.contextlinks.ui;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.ui.editor.XtextEditor;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import ru.xelgo.edt.contextlinks.core.ContextLinks;
import ru.xelgo.edt.contextlinks.core.QueryResultSchemaCommentGenerator;
import ru.xelgo.edt.contextlinks.core.QueryResultSchemaCommentGenerator.QueryResultSchemaCommentException;

/**
 * Copies a generated query result schema comment from the active embedded QL editor.
 */
public class GenerateQueryResultCommentHandler
    extends AbstractHandler
{
    private static final String QW_UI_BUNDLE = "com._1c.g5.v8.dt.qw.ui"; //$NON-NLS-1$
    private static final String BSL_QW_BUNDLE = "com._1c.g5.v8.dt.bsl.qw"; //$NON-NLS-1$
    private static final String QL_EDITOR = "com._1c.g5.v8.dt.qw.ui.ql.QlEditor"; //$NON-NLS-1$
    private static final String QUERY_WIZARD = "com._1c.g5.v8.dt.qw.ui.QueryWizard"; //$NON-NLS-1$
    private static final String RESULT_PROCESSING_PARAMS =
        "com._1c.g5.v8.dt.qw.ui.utils.QueryTextResultProcessing$ResultProcessingParams"; //$NON-NLS-1$
    private static final String BSL_QUERY_WIZARD_UTILS =
        "com._1c.g5.v8.dt.bsl.qw.utils.BslQueryWizardUtils"; //$NON-NLS-1$
    private static final int QUERY_WIZARD_MODE_QUERY_BUILDER = 1;
    private static final String BSL_IDENTIFIER = "[\\p{L}_][\\p{L}\\p{N}_]*"; //$NON-NLS-1$
    private static final int QUERY_CONTEXT_LOOKBEHIND = 1500;
    private static final Pattern DIRECT_QUERY_VARIABLE_PATTERN = Pattern.compile(
        "(" + BSL_IDENTIFIER + ")\\s*=\\s*\\u041d\\u043e\\u0432\\u044b\\u0439\\s+" //$NON-NLS-1$ //$NON-NLS-2$
            + "\\u0417\\u0430\\u043f\\u0440\\u043e\\u0441\\s*\\(", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern QUERY_TEXT_PROPERTY_PATTERN = Pattern.compile(
        "(" + BSL_IDENTIFIER + ")\\s*\\.\\s*\\u0422\\u0435\\u043a\\u0441\\u0442\\s*=\\s*$", //$NON-NLS-1$ //$NON-NLS-2$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TEXT_VARIABLE_PATTERN = Pattern.compile(
        "(" + BSL_IDENTIFIER + ")\\s*=\\s*$", //$NON-NLS-1$ //$NON-NLS-2$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    @Override
    public Object execute(ExecutionEvent event)
        throws ExecutionException
    {
        Shell shell = HandlerUtil.getActiveShell(event);
        try
        {
            QueryContext queryContext = resolveQueryContext(event, shell);
            if (queryContext == null || queryContext.queryText == null || queryContext.queryText.isBlank())
            {
                Messages.showError(shell, Messages.GenerateQueryResultCommentHandler_Title,
                    Messages.GenerateQueryResultCommentHandler_NoQueryEditor);
                return null;
            }

            String comment = QueryResultSchemaCommentGenerator.generate(queryContext.queryText, queryContext.project,
                queryContext.queryVariable, queryContext.regionSuffix);
            copyToClipboard(shell, comment);
            Messages.showInfo(shell, Messages.GenerateQueryResultCommentHandler_Title,
                Messages.GenerateQueryResultCommentHandler_Copied);
        }
        catch (QueryResultSchemaCommentException | ReflectiveOperationException | RuntimeException e)
        {
            ContextLinks.logError("EDT Extension Tweaks failed to generate query result comment", e); //$NON-NLS-1$
            Messages.showError(shell, Messages.GenerateQueryResultCommentHandler_Title,
                Messages.GenerateQueryResultCommentHandler_Failed + "\n" + e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    private QueryContext resolveQueryContext(ExecutionEvent event, Shell shell)
        throws ReflectiveOperationException
    {
        Object qlEditor = resolveQlEditor(shell);
        if (qlEditor != null)
        {
            IProject project = (IProject)invoke(qlEditor, "getContextProject"); //$NON-NLS-1$
            String queryText = (String)invoke(qlEditor, "getSelectedQueryText"); //$NON-NLS-1$
            return new QueryContext(project, queryText, null, null);
        }

        return resolveBslQueryContext(event);
    }

    private Object resolveQlEditor(Shell shell)
        throws ReflectiveOperationException
    {
        Class<?> qlEditorClass = loadQwClass(QL_EDITOR);
        Object data = shell != null ? shell.getData() : null;
        if (qlEditorClass.isInstance(data))
            return data;

        if (data instanceof IAdaptable adaptable)
        {
            Object adapted = adaptable.getAdapter(qlEditorClass);
            if (qlEditorClass.isInstance(adapted))
                return adapted;
        }

        Class<?> queryWizardClass = loadQwClass(QUERY_WIZARD);
        if (queryWizardClass.isInstance(data))
        {
            Object qlEditor = invoke(data, "getActiveQlEditor"); //$NON-NLS-1$
            if (qlEditorClass.isInstance(qlEditor))
                return qlEditor;
        }

        Object activeQlEditor = activeQlEditor(queryWizardClass);
        if (qlEditorClass.isInstance(activeQlEditor))
            return activeQlEditor;

        return null;
    }

    private QueryContext resolveBslQueryContext(ExecutionEvent event)
        throws ReflectiveOperationException
    {
        IWorkbenchPart activePart = HandlerUtil.getActivePart(event);
        XtextEditor editor = extractXtextEditor(activePart);
        if (editor == null)
            return null;

        IProject project = project(editor, activePart);
        ITextSelection selection = textSelection(activePart);
        if (project == null || selection == null)
            return null;

        IXtextDocument document = editor.getDocument();
        String moduleText = document.get();
        BslQueryText queryTextInfo = bslQueryText(document, selection.getOffset());
        String queryText = queryTextInfo != null ? queryTextInfo.text : null;
        QueryVariableNames queryVariableNames = queryTextInfo != null
            ? inferQueryVariableNames(moduleText, queryTextInfo.offset, queryTextInfo.endOffset)
            : null;
        if ((queryText == null || queryText.isBlank()) && selection.getLength() > 0)
        {
            queryText = selection.getText();
            queryVariableNames = inferQueryVariableNames(moduleText, selection.getOffset(),
                selection.getOffset() + selection.getLength());
        }

        return new QueryContext(project, queryText,
            queryVariableNames != null ? queryVariableNames.queryVariable : null,
            queryVariableNames != null ? queryVariableNames.regionSuffix : null);
    }

    private XtextEditor extractXtextEditor(IWorkbenchPart part)
    {
        if (part == null)
            return null;

        XtextEditor editor = part.getAdapter(XtextEditor.class);
        if (editor != null)
            return editor;

        return part instanceof XtextEditor ? (XtextEditor)part : null;
    }

    private IProject project(XtextEditor editor, IWorkbenchPart activePart)
    {
        IEditorInput input = editor.getEditorInput();
        if (input instanceof IFileEditorInput fileInput && fileInput.getFile() != null)
            return fileInput.getFile().getProject();

        return activePart != null ? activePart.getAdapter(IProject.class) : null;
    }

    private ITextSelection textSelection(IWorkbenchPart activePart)
    {
        if (activePart == null || activePart.getSite() == null)
            return null;

        ISelectionProvider selectionProvider = activePart.getSite().getSelectionProvider();
        if (selectionProvider == null)
            return null;

        ISelection selection = selectionProvider.getSelection();
        return selection instanceof ITextSelection ? (ITextSelection)selection : null;
    }

    private BslQueryText bslQueryText(IXtextDocument document, int offset)
        throws ReflectiveOperationException
    {
        Class<?> paramsClass = loadClass(QW_UI_BUNDLE, RESULT_PROCESSING_PARAMS);
        Object params = paramsClass.getConstructor().newInstance();
        Class<?> utilsClass = loadClass(BSL_QW_BUNDLE, BSL_QUERY_WIZARD_UTILS);
        Method method = utilsClass.getDeclaredMethod("getQueryTextByOffset", //$NON-NLS-1$
            IXtextDocument.class, int.class, int.class, paramsClass);
        method.setAccessible(true);
        Object queryTextInfo = method.invoke(null, document, Integer.valueOf(offset),
            Integer.valueOf(QUERY_WIZARD_MODE_QUERY_BUILDER), params);
        Object text = queryTextInfo != null ? invoke(queryTextInfo, "getText") : null; //$NON-NLS-1$
        if (!(text instanceof String queryText))
            return null;

        int queryOffset = intValue(invoke(queryTextInfo, "getOffset"), offset); //$NON-NLS-1$
        int queryEndOffset = intValue(invoke(queryTextInfo, "getEndOffset"), queryOffset); //$NON-NLS-1$
        return new BslQueryText(queryText, queryOffset, queryEndOffset);
    }

    private QueryVariableNames inferQueryVariableNames(String moduleText, int queryTextOffset,
        int queryTextEndOffset)
    {
        if (moduleText == null || moduleText.isBlank())
            return null;

        int safeOffset = Math.max(0, Math.min(queryTextOffset, moduleText.length()));
        int safeEndOffset = Math.max(safeOffset, Math.min(queryTextEndOffset, moduleText.length()));
        String prefix = moduleText.substring(Math.max(0, safeOffset - QUERY_CONTEXT_LOOKBEHIND), safeOffset);

        String queryVariable = findDirectQueryVariable(prefix);
        if (queryVariable != null)
            return new QueryVariableNames(queryVariable, queryVariable);

        queryVariable = findQueryTextPropertyVariable(prefix);
        if (queryVariable != null)
            return new QueryVariableNames(queryVariable, queryVariable);

        String textVariable = findTextVariable(prefix);
        if (textVariable == null)
            return null;

        queryVariable = findQueryVariableByTextVariable(moduleText, textVariable, safeEndOffset);
        return new QueryVariableNames(queryVariable, textVariable);
    }

    private String findDirectQueryVariable(String prefix)
    {
        Matcher matcher = DIRECT_QUERY_VARIABLE_PATTERN.matcher(prefix);
        String result = null;
        while (matcher.find())
        {
            if (isOnlyQueryTextOpening(prefix.substring(matcher.end())))
                result = matcher.group(1);
        }
        return result;
    }

    private String findQueryTextPropertyVariable(String prefix)
    {
        Matcher matcher = QUERY_TEXT_PROPERTY_PATTERN.matcher(prefix);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String findTextVariable(String prefix)
    {
        Matcher matcher = TEXT_VARIABLE_PATTERN.matcher(prefix);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String findQueryVariableByTextVariable(String moduleText, String textVariable, int originOffset)
    {
        String variable = Pattern.quote(textVariable);
        Pattern constructorPattern = Pattern.compile(
            "(" + BSL_IDENTIFIER + ")\\s*=\\s*\\u041d\\u043e\\u0432\\u044b\\u0439\\s+" //$NON-NLS-1$ //$NON-NLS-2$
                + "\\u0417\\u0430\\u043f\\u0440\\u043e\\u0441\\s*\\(\\s*" + variable + "\\s*\\)", //$NON-NLS-1$ //$NON-NLS-2$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Pattern propertyPattern = Pattern.compile(
            "(" + BSL_IDENTIFIER + ")\\s*\\.\\s*\\u0422\\u0435\\u043a\\u0441\\u0442\\s*=\\s*" //$NON-NLS-1$ //$NON-NLS-2$
                + variable + "\\b", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

        String constructorVariable = nearestVariable(moduleText, constructorPattern, originOffset);
        String propertyVariable = nearestVariable(moduleText, propertyPattern, originOffset);
        if (constructorVariable == null)
            return propertyVariable;
        if (propertyVariable == null)
            return constructorVariable;

        int constructorDistance = nearestDistance(moduleText, constructorPattern, originOffset);
        int propertyDistance = nearestDistance(moduleText, propertyPattern, originOffset);
        return constructorDistance <= propertyDistance ? constructorVariable : propertyVariable;
    }

    private String nearestVariable(String text, Pattern pattern, int originOffset)
    {
        Matcher matcher = pattern.matcher(text);
        String result = null;
        int bestDistance = Integer.MAX_VALUE;
        while (matcher.find())
        {
            int distance = Math.abs(matcher.start() - originOffset);
            if (distance < bestDistance)
            {
                result = matcher.group(1);
                bestDistance = distance;
            }
        }
        return result;
    }

    private int nearestDistance(String text, Pattern pattern, int originOffset)
    {
        Matcher matcher = pattern.matcher(text);
        int bestDistance = Integer.MAX_VALUE;
        while (matcher.find())
            bestDistance = Math.min(bestDistance, Math.abs(matcher.start() - originOffset));
        return bestDistance;
    }

    private static boolean isOnlyQueryTextOpening(String value)
    {
        if (value == null)
            return true;
        for (int i = 0; i < value.length(); i++)
        {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch) && ch != '"' && ch != '|')
                return false;
        }
        return true;
    }

    private static int intValue(Object value, int fallback)
    {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private Object activeQlEditor(Class<?> queryWizardClass)
        throws ReflectiveOperationException
    {
        try
        {
            Method method = queryWizardClass.getMethod("getActiveQlEditor"); //$NON-NLS-1$
            if (!Modifier.isStatic(method.getModifiers()))
                return null;

            method.setAccessible(true);
            return method.invoke(null);
        }
        catch (NoSuchMethodException e)
        {
            return null;
        }
    }

    private void copyToClipboard(Shell shell, String text)
    {
        Display display = shell != null ? shell.getDisplay() : Display.getDefault();
        Clipboard clipboard = new Clipboard(display);
        try
        {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
    }

    private Object invoke(Object target, String methodName)
        throws ReflectiveOperationException
    {
        try
        {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException)
                throw runtimeException;
            if (cause instanceof Error error)
                throw error;
            throw e;
        }
    }

    private Class<?> loadQwClass(String className)
        throws ClassNotFoundException
    {
        return loadClass(QW_UI_BUNDLE, className);
    }

    private Class<?> loadClass(String bundleSymbolicName, String className)
        throws ClassNotFoundException
    {
        Bundle current = FrameworkUtil.getBundle(GenerateQueryResultCommentHandler.class);
        if (current == null || current.getBundleContext() == null)
            throw new ClassNotFoundException(className);

        for (Bundle bundle : current.getBundleContext().getBundles())
        {
            if (bundleSymbolicName.equals(bundle.getSymbolicName()))
                return bundle.loadClass(className);
        }

        throw new ClassNotFoundException(className);
    }

    private static final class QueryContext
    {
        private final IProject project;
        private final String queryText;
        private final String queryVariable;
        private final String regionSuffix;

        QueryContext(IProject project, String queryText, String queryVariable, String regionSuffix)
        {
            this.project = project;
            this.queryText = queryText;
            this.queryVariable = queryVariable;
            this.regionSuffix = regionSuffix;
        }
    }

    private static final class BslQueryText
    {
        private final String text;
        private final int offset;
        private final int endOffset;

        BslQueryText(String text, int offset, int endOffset)
        {
            this.text = text;
            this.offset = offset;
            this.endOffset = endOffset;
        }
    }

    private static final class QueryVariableNames
    {
        private final String queryVariable;
        private final String regionSuffix;

        QueryVariableNames(String queryVariable, String regionSuffix)
        {
            this.queryVariable = queryVariable;
            this.regionSuffix = regionSuffix;
        }
    }
}
