package ru.xelgo.edt.contextlinks.core;

import java.util.List;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.ide.editor.syntaxcoloring.IHighlightedPositionAcceptor;
import org.eclipse.xtext.ide.editor.syntaxcoloring.ISemanticHighlightingCalculator;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.util.CancelIndicator;
import org.osgi.framework.Bundle;

import org.eclipse.core.runtime.Platform;

import com._1c.g5.v8.dt.bsl.model.BslPackage;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.Invocation;

/**
 * Highlights calls that the EDT BSL model already marks as server calls.
 */
public final class ContextLinksBslServerCallHighlightingCalculator
    implements ISemanticHighlightingCalculator
{
    private static final String BSL_UI_BUNDLE = "com._1c.g5.v8.dt.bsl.ui"; //$NON-NLS-1$
    private static final String BSL_SEMANTIC_HIGHLIGHTING_CALCULATOR =
        "com._1c.g5.v8.dt.bsl.ui.syntaxcoloring.BslSemanticHighlightingCalculator"; //$NON-NLS-1$
    private static final String BUILTIN_ID = "Builtin function"; //$NON-NLS-1$

    private final ISemanticHighlightingCalculator delegate = createNativeDelegate();

    @Override
    public void provideHighlightingFor(XtextResource resource, IHighlightedPositionAcceptor acceptor,
        CancelIndicator cancelIndicator)
    {
        if (delegate != null)
            delegate.provideHighlightingFor(resource, acceptor, cancelIndicator);

        if (resource == null || acceptor == null || isCanceled(cancelIndicator) || resource.getParseResult() == null)
            return;

        EObject root = resource.getParseResult().getRootASTElement();
        if (root == null)
            return;

        TreeIterator<EObject> iterator = root.eAllContents();
        while (iterator.hasNext())
        {
            if (isCanceled(cancelIndicator))
                return;

            EObject element = iterator.next();
            if (element instanceof Invocation)
                highlightInvocation((Invocation)element, acceptor,
                    ContextLinksPreferences.isServerCallHighlightingEnabled());
        }
    }

    private void highlightInvocation(Invocation invocation, IHighlightedPositionAcceptor acceptor,
        boolean highlightServerCalls)
    {
        if (!invocation.isIsServerCall())
            return;

        FeatureAccess methodAccess = invocation.getMethodAccess();
        if (methodAccess == null)
            return;

        String highlightingId =
            highlightServerCalls ? ContextLinksBslServerCallHighlightingConfiguration.SERVER_CALL_ID : BUILTIN_ID;

        highlightFeatureName(methodAccess, acceptor, highlightingId);
        if (methodAccess instanceof DynamicFeatureAccess)
            highlightSourceFeatureNames(((DynamicFeatureAccess)methodAccess).getSource(), acceptor, highlightingId);
    }

    private void highlightSourceFeatureNames(Expression source, IHighlightedPositionAcceptor acceptor,
        String highlightingId)
    {
        if (source instanceof DynamicFeatureAccess)
        {
            DynamicFeatureAccess dynamicSource = (DynamicFeatureAccess)source;
            highlightSourceFeatureNames(dynamicSource.getSource(), acceptor, highlightingId);
            highlightFeatureName(dynamicSource, acceptor, highlightingId);
        }
        else if (source instanceof FeatureAccess)
        {
            highlightFeatureName((FeatureAccess)source, acceptor, highlightingId);
        }
    }

    private void highlightFeatureName(FeatureAccess featureAccess, IHighlightedPositionAcceptor acceptor,
        String highlightingId)
    {
        List<INode> nodes = NodeModelUtils.findNodesForFeature(featureAccess, BslPackage.Literals.FEATURE_ACCESS__NAME);
        for (INode node : nodes)
        {
            if (node.getLength() > 0)
            {
                acceptor.addPosition(node.getOffset(), node.getLength(), highlightingId);
            }
        }
    }

    private boolean isCanceled(CancelIndicator cancelIndicator)
    {
        return cancelIndicator != null && cancelIndicator.isCanceled();
    }

    private static ISemanticHighlightingCalculator createNativeDelegate()
    {
        Bundle bundle = Platform.getBundle(BSL_UI_BUNDLE);
        if (bundle == null)
            return null;

        try
        {
            Object instance = bundle.loadClass(BSL_SEMANTIC_HIGHLIGHTING_CALCULATOR).getDeclaredConstructor()
                .newInstance();
            if (instance instanceof ISemanticHighlightingCalculator)
                return (ISemanticHighlightingCalculator)instance;
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            ContextLinks.logError("Failed to create native BSL semantic highlighting calculator.", e); //$NON-NLS-1$
        }
        return null;
    }
}
