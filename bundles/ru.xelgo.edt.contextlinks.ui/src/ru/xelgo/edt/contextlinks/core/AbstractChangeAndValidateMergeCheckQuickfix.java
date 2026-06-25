package ru.xelgo.edt.contextlinks.core;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.text.edits.TextEdit;

import com.e1c.g5.v8.dt.bsl.check.qfix.IXtextBslModuleFixModel;
import com.e1c.g5.v8.dt.bsl.check.qfix.SingleVariantXtextBslModuleFix;

import ru.xelgo.edt.contextlinks.ui.Messages;

abstract class AbstractChangeAndValidateMergeCheckQuickfix
    extends SingleVariantXtextBslModuleFix
{
    @Override
    protected void configureFix(FixConfigurer configurer)
    {
        configurer.description(Messages.ChangeAndValidateMergeQuickfix_Label);
        configurer.details(Messages.ChangeAndValidateMergeQuickfix_Description);
        configurer.interactive(true);
    }

    @Override
    protected TextEdit fixIssue(XtextResource resource, IXtextBslModuleFixModel model)
    {
        IDocument document = model.getDocument();
        try
        {
            return ChangeAndValidateMergeQuickfixProvider.createMergeTextEdit(resource, document.get(), model.getIssue());
        }
        catch (CoreException | RuntimeException e)
        {
            ContextLinks.logError(Messages.ChangeAndValidateMergeQuickfix_SaveFailed, e);
            return null;
        }
    }
}
