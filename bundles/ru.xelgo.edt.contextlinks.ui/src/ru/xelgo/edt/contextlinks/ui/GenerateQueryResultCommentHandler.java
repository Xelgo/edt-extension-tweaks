package ru.xelgo.edt.contextlinks.ui;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

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

    @Override
    public Object execute(ExecutionEvent event)
        throws ExecutionException
    {
        Shell shell = HandlerUtil.getActiveShell(event);
        try
        {
            QueryContext queryContext = resolveQueryContext(event, shell);
            if (queryContext == null)
            {
                Messages.showError(shell, Messages.GenerateQueryResultCommentHandler_Title,
                    Messages.GenerateQueryResultCommentHandler_NoQueryEditor);
                return null;
            }

            String comment = QueryResultSchemaCommentGenerator.generate(queryContext.queryText, queryContext.project);
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
            return new QueryContext(project, queryText);
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

        String queryText = bslQueryText(editor.getDocument(), selection.getOffset());
        if ((queryText == null || queryText.isBlank()) && selection.getLength() > 0)
            queryText = selection.getText();

        return new QueryContext(project, queryText);
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

    private String bslQueryText(IXtextDocument document, int offset)
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
        return text instanceof String ? (String)text : null;
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

        QueryContext(IProject project, String queryText)
        {
            this.project = project;
            this.queryText = queryText;
        }
    }
}
