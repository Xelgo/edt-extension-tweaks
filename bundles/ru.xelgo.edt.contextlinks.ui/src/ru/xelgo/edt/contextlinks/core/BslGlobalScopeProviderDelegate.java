package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.IResourceDescription;
import org.eclipse.xtext.resource.IResourceDescriptions;
import org.eclipse.xtext.scoping.IScope;

import com._1c.g5.v8.dt.bsl.scoping.BslGlobalScopeProvider;
import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IDependentProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.wiring.ServiceAccess;
import com.google.common.base.Predicate;

public final class BslGlobalScopeProviderDelegate
{
    private static volatile BslGlobalScopeProvider globalScopeProvider;

    private BslGlobalScopeProviderDelegate()
    {
    }

    public static synchronized void init()
    {
        if (globalScopeProvider == null)
        {
            globalScopeProvider = ServiceAccess.get(BslGlobalScopeProvider.class);
        }
    }

    public static IScope getScopeFromProject(IProject project)
    {
        if (globalScopeProvider == null)
            init();

        if (globalScopeProvider == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [delegate] BslGlobalScopeProvider is null");
            return null;
        }

        if (project == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [delegate] project is null");
            return null;
        }

        IV8ProjectManager projectManager = ServiceAccess.get(IV8ProjectManager.class);
        if (projectManager == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [delegate] projectManager is null");
            return null;
        }

        IV8Project v8Project = projectManager.getProject(project);
        if (v8Project == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [delegate] v8Project is null for " + project.getName());
            return null;
        }

        Resource configResource = getConfigurationResource(v8Project);
        if (configResource == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [delegate] configResource is null for " + project.getName());
            return null;
        }

        EClass typeItemClass = McorePackage.Literals.TYPE_ITEM;

        Predicate<IEObjectDescription> typeItemFilter = description -> {
            EClass eClass = description.getEClass();
            return eClass != null && typeItemClass.isSuperTypeOf(eClass);
        };

        IScope scope = globalScopeProvider.getScope(configResource, null, typeItemFilter);

        if (scope == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [delegate] scope is null for " + project.getName());
            return null;
        }

        List<IEObjectDescription> descriptions = new ArrayList<>();
        for (IEObjectDescription desc : scope.getAllElements())
        {
            if (desc.getEObjectURI() != null && desc.getEClass() != null)
            {
                EObject obj = desc.getEObjectOrProxy();
                if (obj != null && obj.eResource() != null && !obj.eIsProxy())
                {
                    descriptions.add(desc);
                }
            }
        }

        ContextLinks.logDebug("EDT Context Links DEBUG [delegate] found " + descriptions.size() + " TYPE_ITEM from BslGlobalScopeProvider for " + project.getName());

        if (descriptions.isEmpty())
            return null;

        return new SimpleScope(descriptions);
    }

    private static Resource getConfigurationResource(IV8Project v8Project)
    {
        EObject config = null;
        if (v8Project instanceof IConfigurationAware)
        {
            config = ((IConfigurationAware)v8Project).getConfiguration();
        }
        else if (v8Project instanceof IDependentProject)
        {
            IConfigurationProject parent = ((IDependentProject)v8Project).getParent();
            if (parent != null)
                config = parent.getConfiguration();
        }

        return config != null ? config.eResource() : null;
    }

    private static final class SimpleScope
        implements IScope
    {
        private final List<IEObjectDescription> descriptions;

        SimpleScope(List<IEObjectDescription> descriptions)
        {
            this.descriptions = descriptions;
        }

        @Override
        public IEObjectDescription getSingleElement(QualifiedName name)
        {
            if (name == null)
                return null;

            for (IEObjectDescription desc : descriptions)
            {
                if (name.equals(desc.getName()))
                    return desc;
            }
            return null;
        }

        @Override
        public Iterable<IEObjectDescription> getElements(QualifiedName name)
        {
            if (name == null)
                return List.of();

            List<IEObjectDescription> result = new ArrayList<>();
            for (IEObjectDescription desc : descriptions)
            {
                if (name.equals(desc.getName()))
                    result.add(desc);
            }
            return result;
        }

        @Override
        public IEObjectDescription getSingleElement(EObject object)
        {
            if (object == null)
                return null;

            for (IEObjectDescription desc : descriptions)
            {
                EObject obj = desc.getEObjectOrProxy();
                if (obj != null && obj.equals(object) && !obj.eIsProxy())
                    return desc;
            }
            return null;
        }

        @Override
        public Iterable<IEObjectDescription> getElements(EObject object)
        {
            IEObjectDescription desc = getSingleElement(object);
            return desc != null ? List.of(desc) : List.of();
        }

        @Override
        public Iterable<IEObjectDescription> getAllElements()
        {
            return descriptions;
        }
    }
}
