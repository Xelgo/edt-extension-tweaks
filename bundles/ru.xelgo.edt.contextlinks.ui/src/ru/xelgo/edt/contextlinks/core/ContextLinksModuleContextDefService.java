package ru.xelgo.edt.contextlinks.core;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.dt.bsl.contextdef.IBslModuleContextDefExtension;
import com._1c.g5.v8.dt.bsl.contextdef.IBslModuleContextDefRegistry;
import com._1c.g5.v8.dt.bsl.contextdef.IBslModuleContextDefService;
import com._1c.g5.v8.dt.bsl.model.FormalParam;
import com._1c.g5.v8.dt.bsl.model.Function;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.ParamSet;
import com._1c.g5.v8.dt.mcore.Parameter;
import com._1c.g5.v8.dt.mcore.util.Environments;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com.google.inject.Inject;

/**
 * Mirrors EDT's module context definition service and logs the common-module path.
 */
public class ContextLinksModuleContextDefService
    implements IBslModuleContextDefService
{
    private static final Set<String> loggedContextKeys = ConcurrentHashMap.newKeySet();

    @Inject
    private IBslModuleContextDefRegistry registry;

    @Override
    public ContextDef getContextDef(Module module)
    {
        if (module == null || module.getOwner() == null)
        {
            logCommonModuleContext("skip", module, null, null, "module-or-owner-null"); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }

        for (IBslModuleContextDefExtension provider : registry.getAllProviders())
        {
            if (!provider.isAppropriateFor(module))
                continue;

            ContextDef contextDef = provider.getContextDef(module);
            logCommonModuleContext("provider", module, provider, contextDef, "matched"); //$NON-NLS-1$ //$NON-NLS-2$
            ContextDef fallbackContextDef = ensureFallbackContextDef(module, contextDef);
            if (fallbackContextDef != null)
            {
                logCommonModuleContext("fallback", module, provider, fallbackContextDef, //$NON-NLS-1$
                    "resource-backed-export-methods-from-bsl"); //$NON-NLS-1$
                return fallbackContextDef;
            }
            return contextDef;
        }

        logCommonModuleContext("miss", module, null, null, "no-provider"); //$NON-NLS-1$ //$NON-NLS-2$
        return null;
    }

    @Override
    public List<Event> getModuleEvents(Module module)
    {
        if (module == null || module.getOwner() == null)
            return List.of();

        for (IBslModuleContextDefExtension provider : registry.getAllProviders())
        {
            if (provider.isAppropriateFor(module))
                return provider.getModuleEvents(module);
        }

        return List.of();
    }

    private static ContextDef ensureFallbackContextDef(Module module, ContextDef contextDef)
    {
        EObject owner = module != null ? module.getOwner() : null;
        if (!(owner instanceof CommonModule))
            return null;

        if (contextDef != null && !contextDef.allMethods().isEmpty())
            return null;

        List<com._1c.g5.v8.dt.bsl.model.Method> exportMethods = module.allMethods()
            .stream()
            .filter(com._1c.g5.v8.dt.bsl.model.Method::isExport)
            .collect(Collectors.toList());
        if (exportMethods.isEmpty())
            return null;

        ContextDef fallbackContextDef = McoreFactory.eINSTANCE.createContextDef();
        fallbackContextDef.setEnvironments(contextDef != null && contextDef.getEnvironments() != null
            ? contextDef.getEnvironments() : Environments.ALL);
        exportMethods.stream()
            .map(ContextLinksModuleContextDefService::createFallbackMethod)
            .forEach(fallbackContextDef.getMethods()::add);
        return fallbackContextDef;
    }

    private static com._1c.g5.v8.dt.mcore.Method createFallbackMethod(
        com._1c.g5.v8.dt.bsl.model.Method bslMethod)
    {
        com._1c.g5.v8.dt.mcore.Method method = McoreFactory.eINSTANCE.createMethod();
        method.setName(safeName(bslMethod.getName()));
        method.setNameRu(safeName(bslMethod.getName()));
        method.setRetVal(bslMethod instanceof Function);
        method.setEnvironments(Environments.ALL);
        method.getParamSet().add(createFallbackParamSet(bslMethod));
        return method;
    }

    private static ParamSet createFallbackParamSet(com._1c.g5.v8.dt.bsl.model.Method bslMethod)
    {
        ParamSet paramSet = McoreFactory.eINSTANCE.createParamSet();
        int minParams = 0;
        for (FormalParam formalParam : bslMethod.getFormalParams())
        {
            Parameter parameter = McoreFactory.eINSTANCE.createParameter();
            parameter.setName(safeName(formalParam.getName()));
            parameter.setNameRu(safeName(formalParam.getName()));
            parameter.setDefaultValue(formalParam.getDefaultValue() != null);
            parameter.setOut(!formalParam.isByValue());
            paramSet.getParams().add(parameter);
            if (formalParam.getDefaultValue() == null)
                minParams++;
        }

        paramSet.setMinParams(minParams);
        paramSet.setMaxParams(bslMethod.getFormalParams().size());
        return paramSet;
    }

    private static void logCommonModuleContext(String stage, Module module, IBslModuleContextDefExtension provider,
        ContextDef contextDef, String reason)
    {
        if (!ContextLinks.isDebugLoggingEnabled())
            return;

        EObject owner = module != null ? module.getOwner() : null;
        if (!(owner instanceof CommonModule commonModule))
            return;

        URI moduleUri = module.eResource() != null ? module.eResource().getURI() : null;
        String key = stage + "|" + moduleUri + "|" + reason; //$NON-NLS-1$ //$NON-NLS-2$
        if (!loggedContextKeys.add(key))
            return;

        ContextLinks.logDebug("EDT Context Links DEBUG [module.context." + stage + "] project=" //$NON-NLS-1$ //$NON-NLS-2$
            + describeProject(moduleUri) + " moduleUri=" + moduleUri //$NON-NLS-1$
            + " owner=" + safeName(commonModule.getName()) //$NON-NLS-1$
            + " ownerProxy=" + commonModule.eIsProxy() //$NON-NLS-1$
            + " provider=" + describeProvider(provider) //$NON-NLS-1$
            + " reason=" + reason //$NON-NLS-1$
            + " context=" + describeContextDef(contextDef) //$NON-NLS-1$
            + " methods=" + sampleMethods(contextDef)); //$NON-NLS-1$
    }

    private static String describeProject(URI uri)
    {
        return ContextLinks.getProject(uri) != null ? ContextLinks.getProject(uri).getName() : "NULL"; //$NON-NLS-1$
    }

    private static String describeProvider(IBslModuleContextDefExtension provider)
    {
        return provider != null ? provider.getClass().getName() : "NULL"; //$NON-NLS-1$
    }

    private static String describeContextDef(ContextDef contextDef)
    {
        if (contextDef == null)
            return "NULL"; //$NON-NLS-1$

        return contextDef.getClass().getName()
            + "{properties=" + safeSize(contextDef.getProperties()) //$NON-NLS-1$
            + ",methods=" + safeSize(contextDef.getMethods()) //$NON-NLS-1$
            + ",allProperties=" + safeSize(contextDef.allProperties()) //$NON-NLS-1$
            + ",allMethods=" + safeSize(contextDef.allMethods()) //$NON-NLS-1$
            + ",refs=" + safeSize(contextDef.getRefContextDefs()) //$NON-NLS-1$
            + ",resource=" + (contextDef.eResource() != null ? contextDef.eResource().getURI() : "NULL") + "}"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String sampleMethods(ContextDef contextDef)
    {
        if (contextDef == null)
            return "[]"; //$NON-NLS-1$

        return contextDef.allMethods()
            .stream()
            .limit(12)
            .map(method -> safeName(method.getName()))
            .collect(Collectors.toList())
            .toString();
    }

    private static int safeSize(List<?> list)
    {
        return list != null ? list.size() : -1;
    }

    private static String safeName(String value)
    {
        return value != null && !value.isBlank() ? value : "NULL_OR_BLANK"; //$NON-NLS-1$
    }
}
