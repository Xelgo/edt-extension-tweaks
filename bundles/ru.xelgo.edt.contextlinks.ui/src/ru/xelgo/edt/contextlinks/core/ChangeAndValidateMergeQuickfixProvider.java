package ru.xelgo.edt.contextlinks.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.services.IServiceLocator;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.ui.editor.model.edit.IModificationContext;
import org.eclipse.xtext.ui.editor.quickfix.Fix;
import org.eclipse.xtext.ui.editor.quickfix.IssueResolutionAcceptor;
import org.eclipse.xtext.validation.Issue;

import com._1c.g5.v8.dt.bsl.common.IModuleExtensionService;
import com._1c.g5.v8.dt.bsl.common.IModuleExtensionServiceProvider;
import com._1c.g5.v8.dt.bsl.common.Symbols;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.Pragma;
import com._1c.g5.v8.dt.bsl.ui.quickfix.AbstractExternalQuickfixProvider;
import com._1c.g5.v8.dt.compare.ui.StringBasedTypedElement;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.ThreeSideTextMergeDialog;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.ThreeSideTextMergeEditorInput;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.ThreeSideTextMergeInput;

import ru.xelgo.edt.contextlinks.ui.Messages;

/**
 * Opens a three-side merge for extension methods marked with ChangeAndValidate.
 */
public final class ChangeAndValidateMergeQuickfixProvider
    extends AbstractExternalQuickfixProvider
{
    private static final String WRONG_EXTENSION_SIGNATURE_CODE = "not-use-annotation-form-event-handlers"; //$NON-NLS-1$
    private static final String METHOD_TEXT_DIFF_CODE = "method-text-has-differences-base-method"; //$NON-NLS-1$
    private static final String LEGACY_MODULE_EXTENSION_CODE = "bsl-legacy-check-module-extension"; //$NON-NLS-1$
    private static final String CHANGE_AND_VALIDATE_TEXT_DIFF_CODE = "SU310"; //$NON-NLS-1$
    private static final String BSL_TYPE = "bsl"; //$NON-NLS-1$

    @Fix(WRONG_EXTENSION_SIGNATURE_CODE)
    public void compareAndApplyChangeAndValidateMethodSignature(Issue issue, IssueResolutionAcceptor acceptor)
    {
        acceptMergeQuickfix(issue, acceptor);
    }

    @Fix(METHOD_TEXT_DIFF_CODE)
    public void compareAndApplyChangeAndValidateMethodText(Issue issue, IssueResolutionAcceptor acceptor)
    {
        acceptMergeQuickfix(issue, acceptor);
    }

    @Fix(LEGACY_MODULE_EXTENSION_CODE)
    public void compareAndApplyLegacyModuleExtensionIssue(Issue issue, IssueResolutionAcceptor acceptor)
    {
        acceptMergeQuickfix(issue, acceptor);
    }

    @Fix(CHANGE_AND_VALIDATE_TEXT_DIFF_CODE)
    public void compareAndApplyChangeAndValidateTextDiffIssue(Issue issue, IssueResolutionAcceptor acceptor)
    {
        acceptMergeQuickfix(issue, acceptor);
    }

    private static void acceptMergeQuickfix(Issue issue, IssueResolutionAcceptor acceptor)
    {
        if (!isSupportedChangeAndValidateIssue(issue))
            return;

        acceptor.accept(issue, Messages.ChangeAndValidateMergeQuickfix_Label,
            Messages.ChangeAndValidateMergeQuickfix_Description, null,
            context -> applyMergeResult(issue, context));
    }

    private static boolean isSupportedChangeAndValidateIssue(Issue issue)
    {
        if (issue == null)
            return false;

        String code = issue.getCode();
        if (WRONG_EXTENSION_SIGNATURE_CODE.equals(code) || METHOD_TEXT_DIFF_CODE.equals(code)
            || LEGACY_MODULE_EXTENSION_CODE.equals(code) || CHANGE_AND_VALIDATE_TEXT_DIFF_CODE.equals(code))
            return true;

        return false;
    }

    private static void applyMergeResult(Issue issue, IModificationContext context)
        throws Exception
    {
        IXtextDocument document = context.getXtextDocument();
        String documentText = document.get();
        MergeData mergeData = document.readOnly(resource -> createMergeData(resource, documentText, issue));
        String mergedText = openMergeDialog(mergeData);
        if (mergedText == null)
            return;

        document.replace(mergeData.methodOffset, mergeData.methodLength,
            normalizeLineSeparators(mergedText, mergeData.extensionMethodText));
    }

    private static MergeData createMergeData(XtextResource resource, String documentText, Issue issue)
    {
        Method extensionMethod = findMethod(resource, issue);
        if (extensionMethod == null)
            throw new IllegalStateException(Messages.ChangeAndValidateMergeQuickfix_NoExtensionMethod);

        IModuleExtensionService service = IModuleExtensionServiceProvider.INSTANCE.getModuleExtensionService();
        if (service == null)
            throw new IllegalStateException(Messages.ChangeAndValidateMergeQuickfix_NoModuleExtensionService);

        Module extensionModule = EcoreUtil2.getContainerOfType(extensionMethod, Module.class);
        if (extensionModule == null || !service.isExtensionModule(extensionModule) || !hasChangeAndValidate(extensionMethod))
            throw new IllegalStateException(Messages.ChangeAndValidateMergeQuickfix_NotChangeAndValidateMethod);

        Method sourceMethod = findSourceMethod(service, extensionMethod);
        if (sourceMethod == null)
            throw new IllegalStateException(Messages.ChangeAndValidateMergeQuickfix_NoSourceMethod);

        MethodRange extensionRange = getMethodRange(extensionMethod, documentText);
        String extensionText = documentText.substring(extensionRange.offset, extensionRange.offset + extensionRange.length);
        String sourceText = getMethodText(sourceMethod);
        ChangeAndValidateExtensionMergeBuilder.MergeTexts mergeTexts =
            ChangeAndValidateExtensionMergeBuilder.build(sourceText, extensionText);

        return new MergeData(sourceText, extensionText, mergeTexts.ancestorText, mergeTexts.suggestedText,
            extensionRange.offset, extensionRange.length, extensionMethod.getName());
    }

    private static Method findMethod(XtextResource resource, Issue issue)
    {
        if (resource == null || resource.getParseResult() == null || resource.getContents().isEmpty())
            return null;

        Integer offset = issue != null ? issue.getOffset() : null;
        if (offset != null && offset >= 0)
        {
            INode rootNode = resource.getParseResult().getRootNode();
            ILeafNode leafNode = NodeModelUtils.findLeafNodeAtOffset(rootNode, offset);
            EObject semantic = leafNode != null ? NodeModelUtils.findActualSemanticObjectFor(leafNode) : null;
            Method method = EcoreUtil2.getContainerOfType(semantic, Method.class);
            if (method != null)
                return method;

            method = findMethodByOffset(resource, offset);
            if (method != null)
                return method;
        }

        return EcoreUtil2.getContainerOfType(resource.getContents().get(0), Method.class);
    }

    private static Method findMethodByOffset(XtextResource resource, int offset)
    {
        Module module = EcoreUtil2.getContainerOfType(resource.getContents().get(0), Module.class);
        if (module == null)
            return null;

        for (Method method : module.getMethods())
        {
            ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
            if (node == null)
                continue;

            int methodOffset = node.getTotalOffset();
            int methodEnd = methodOffset + node.getTotalLength();
            if (offset >= methodOffset && offset <= methodEnd)
                return method;
        }
        return null;
    }

    private static Method findSourceMethod(IModuleExtensionService service, Method extensionMethod)
    {
        Map<Pragma, Method> sourceMethods = service.getSourceMethod(extensionMethod);
        if (sourceMethods == null || sourceMethods.isEmpty())
            return null;

        Method fallback = null;
        for (Map.Entry<Pragma, Method> entry : sourceMethods.entrySet())
        {
            Method sourceMethod = entry.getValue();
            if (sourceMethod == null)
                continue;

            if (fallback == null)
                fallback = sourceMethod;
            if (isChangeAndValidatePragma(entry.getKey()))
                return sourceMethod;
        }
        return fallback;
    }

    private static boolean hasChangeAndValidate(Method method)
    {
        if (method == null)
            return false;

        for (Pragma pragma : method.getPragmas())
        {
            if (isChangeAndValidatePragma(pragma))
                return true;
        }
        return false;
    }

    private static boolean isChangeAndValidatePragma(Pragma pragma)
    {
        if (pragma == null || pragma.getSymbol() == null)
            return false;

        return Symbols.CHANGE_AND_VALIDATE.equalsIgnoreCase(pragma.getSymbol())
            || Symbols.CHANGE_AND_VALIDATE_RUS.equalsIgnoreCase(pragma.getSymbol());
    }

    private static MethodRange getMethodRange(Method method, String documentText)
    {
        ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
        if (node == null)
            throw new IllegalStateException(Messages.ChangeAndValidateMergeQuickfix_NoMethodNode);

        int offset = node.getTotalOffset();
        int length = node.getTotalLength();
        if (offset < 0 || length <= 0 || offset + length > documentText.length())
            throw new IllegalStateException(Messages.ChangeAndValidateMergeQuickfix_InvalidMethodRange);

        return new MethodRange(offset, length);
    }

    private static String getMethodText(Method method)
    {
        ICompositeNode node = NodeModelUtils.findActualNodeFor(method);
        if (node == null)
            throw new IllegalStateException(Messages.ChangeAndValidateMergeQuickfix_NoSourceMethodNode);

        return node.getText();
    }

    private static String openMergeDialog(MergeData mergeData)
        throws CoreException
    {
        Display display = getDisplay();
        if (Display.getCurrent() == display)
            return openMergeDialogOnUiThread(mergeData);

        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<CoreException> coreException = new AtomicReference<>();
        AtomicReference<RuntimeException> runtimeException = new AtomicReference<>();
        display.syncExec(() -> {
            try
            {
                result.set(openMergeDialogOnUiThread(mergeData));
            }
            catch (CoreException e)
            {
                coreException.set(e);
            }
            catch (RuntimeException e)
            {
                runtimeException.set(e);
            }
        });

        if (coreException.get() != null)
            throw coreException.get();
        if (runtimeException.get() != null)
            throw runtimeException.get();
        return result.get();
    }

    private static String openMergeDialogOnUiThread(MergeData mergeData)
        throws CoreException
    {
        CompareConfiguration configuration = new CompareConfiguration();
        configuration.setLeftLabel(Messages.ChangeAndValidateMergeQuickfix_LeftLabel);
        configuration.setRightLabel(Messages.ChangeAndValidateMergeQuickfix_RightLabel);
        configuration.setAncestorLabel(Messages.ChangeAndValidateMergeQuickfix_AncestorLabel);

        StringBasedTypedElement left = typedElement(Messages.ChangeAndValidateMergeQuickfix_LeftLabel,
            mergeData.sourceMethodText, false);
        StringBasedTypedElement right = typedElement(Messages.ChangeAndValidateMergeQuickfix_RightLabel,
            mergeData.extensionMethodText, false);
        StringBasedTypedElement ancestor = typedElement(Messages.ChangeAndValidateMergeQuickfix_AncestorLabel,
            mergeData.ancestorMethodText, false);
        StringBasedTypedElement result = typedElement(Messages.ChangeAndValidateMergeQuickfix_ResultLabel,
            mergeData.suggestedMethodText, true);

        ThreeSideTextMergeInput mergeInput = new ThreeSideTextMergeInput(left, right, ancestor, result,
            dialogTitle(mergeData));
        ThreeSideTextMergeEditorInput editorInput = new ThreeSideTextMergeEditorInput(configuration, mergeInput);
        SavingThreeSideTextMergeDialog dialog =
            new SavingThreeSideTextMergeDialog(getShell(), editorInput, getServiceLocator());

        if (dialog.open() != Window.OK)
            return null;

        if (dialog.getSaveException() != null)
            throw dialog.getSaveException();

        return readTypedElement(mergeInput.getMergeResult());
    }

    private static StringBasedTypedElement typedElement(String name, String content, boolean editable)
    {
        StringBasedTypedElement element = new StringBasedTypedElement(name, content, editable);
        element.setType(BSL_TYPE);
        return element;
    }

    private static String dialogTitle(MergeData mergeData)
    {
        String methodName = mergeData.methodName != null && !mergeData.methodName.isBlank()
            ? mergeData.methodName
            : Messages.ChangeAndValidateMergeQuickfix_UnknownMethod;
        return Messages.ChangeAndValidateMergeQuickfix_Title + ": " + methodName; //$NON-NLS-1$
    }

    private static String readTypedElement(ITypedElement element)
        throws CoreException
    {
        if (!(element instanceof IStreamContentAccessor))
            return ""; //$NON-NLS-1$

        try (InputStream contents = ((IStreamContentAccessor)element).getContents())
        {
            if (contents == null)
                return ""; //$NON-NLS-1$
            return new String(contents.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new CoreException(new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR,
                ContextLinks.PLUGIN_ID, Messages.ChangeAndValidateMergeQuickfix_ReadResultFailed, e));
        }
    }

    private static Display getDisplay()
    {
        IWorkbench workbench = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench() : null;
        if (workbench != null && workbench.getDisplay() != null)
            return workbench.getDisplay();
        return Display.getDefault();
    }

    private static Shell getShell()
    {
        IWorkbench workbench = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench() : null;
        IWorkbenchWindow window = workbench != null ? workbench.getActiveWorkbenchWindow() : null;
        Shell shell = window != null ? window.getShell() : null;
        if (shell != null && !shell.isDisposed())
            return shell;
        return getDisplay().getActiveShell();
    }

    private static IServiceLocator getServiceLocator()
    {
        IWorkbench workbench = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench() : null;
        IWorkbenchWindow window = workbench != null ? workbench.getActiveWorkbenchWindow() : null;
        return window != null ? window : workbench;
    }

    private static String normalizeLineSeparators(String text, String reference)
    {
        String separator = reference != null && reference.contains("\r\n") ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", separator); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static final class SavingThreeSideTextMergeDialog
        extends ThreeSideTextMergeDialog
    {
        private CoreException saveException;

        SavingThreeSideTextMergeDialog(Shell shell, ThreeSideTextMergeEditorInput input, IServiceLocator serviceLocator)
        {
            super(shell, input, serviceLocator);
        }

        @Override
        protected void okPressed()
        {
            try
            {
                getInput().saveChanges(new NullProgressMonitor());
                super.okPressed();
            }
            catch (CoreException e)
            {
                saveException = e;
                ContextLinks.logError(Messages.ChangeAndValidateMergeQuickfix_SaveFailed, e);
                MessageDialog.openError(getShell(), Messages.ChangeAndValidateMergeQuickfix_Title,
                    Messages.ChangeAndValidateMergeQuickfix_SaveFailed);
            }
        }

        CoreException getSaveException()
        {
            return saveException;
        }
    }

    private static final class MergeData
    {
        private final String sourceMethodText;
        private final String extensionMethodText;
        private final String ancestorMethodText;
        private final String suggestedMethodText;
        private final int methodOffset;
        private final int methodLength;
        private final String methodName;

        MergeData(String sourceMethodText, String extensionMethodText, String ancestorMethodText,
            String suggestedMethodText, int methodOffset, int methodLength, String methodName)
        {
            this.sourceMethodText = sourceMethodText;
            this.extensionMethodText = extensionMethodText;
            this.ancestorMethodText = ancestorMethodText;
            this.suggestedMethodText = suggestedMethodText;
            this.methodOffset = methodOffset;
            this.methodLength = methodLength;
            this.methodName = methodName;
        }
    }

    private static final class MethodRange
    {
        private final int offset;
        private final int length;

        MethodRange(int offset, int length)
        {
            this.offset = offset;
            this.length = length;
        }
    }
}
