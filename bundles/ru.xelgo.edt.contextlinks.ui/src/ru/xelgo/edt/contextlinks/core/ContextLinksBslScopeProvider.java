package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.scoping.IScope;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.scoping.BslScopeProvider;

/**
 * Logs the actual BSL scopes requested from common module files.
 */
public class ContextLinksBslScopeProvider
    extends BslScopeProvider
{
    private static final Set<String> loggedScopeKeys = ConcurrentHashMap.newKeySet();

    @Override
    public IScope getScope(EObject context, EReference reference)
    {
        IScope scope = super.getScope(context, reference);
        logCommonModuleScope(context, reference, scope);
        return scope;
    }

    private static void logCommonModuleScope(EObject context, EReference reference, IScope scope)
    {
        if (!ContextLinks.isDebugLoggingEnabled())
            return;

        Module module = context != null ? EcoreUtil2.getContainerOfType(context, Module.class) : null;
        URI moduleUri = module != null && module.eResource() != null ? module.eResource().getURI() : null;
        if (moduleUri == null || !String.valueOf(moduleUri).contains("/CommonModules/")) //$NON-NLS-1$
            return;

        String referenceName = reference != null ? reference.getName() : "NULL"; //$NON-NLS-1$
        String contextType = context != null ? context.eClass().getName() : "NULL"; //$NON-NLS-1$
        String key = moduleUri + "|" + contextType + "|" + referenceName; //$NON-NLS-1$ //$NON-NLS-2$
        if (!loggedScopeKeys.add(key))
            return;

        ContextLinks.logDebug("EDT Extension Tweaks DEBUG [bsl.scope] project=" + describeProject(moduleUri) //$NON-NLS-1$
            + " moduleUri=" + moduleUri //$NON-NLS-1$
            + " context=" + contextType //$NON-NLS-1$
            + " reference=" + referenceName //$NON-NLS-1$
            + " scope=" + describeScope(scope) //$NON-NLS-1$
            + " sample=" + sampleElements(scope)); //$NON-NLS-1$
    }

    private static String describeProject(URI uri)
    {
        return ContextLinks.getProject(uri) != null ? ContextLinks.getProject(uri).getName() : "NULL"; //$NON-NLS-1$
    }

    private static String describeScope(IScope scope)
    {
        return scope != null ? scope.getClass().getName() + "@" //$NON-NLS-1$
            + Integer.toHexString(System.identityHashCode(scope)) : "NULL"; //$NON-NLS-1$
    }

    private static List<String> sampleElements(IScope scope)
    {
        List<String> result = new ArrayList<>();
        if (scope == null)
            return result;

        int index = 0;
        try
        {
            for (IEObjectDescription description : scope.getAllElements())
            {
                result.add(String.valueOf(description.getName()));
                index++;
                if (index >= 20)
                    break;
            }
        }
        catch (RuntimeException e)
        {
            result.add("ERROR:" + e.getClass().getName() + ":" + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }
}
