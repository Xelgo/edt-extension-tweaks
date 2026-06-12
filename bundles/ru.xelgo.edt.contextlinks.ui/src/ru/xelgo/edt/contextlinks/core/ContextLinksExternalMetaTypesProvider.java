package ru.xelgo.edt.contextlinks.core;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.ecore.resource.Resource;

import com._1c.g5.v8.dt.bsl.types.extension.IExternalMetaTypesProvider;
import com._1c.g5.v8.dt.mcore.ContextDef;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Property;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.util.Environments;

/**
 * Diagnostic provider for the official EDT external meta types extension point.
 */
public class ContextLinksExternalMetaTypesProvider
    implements IExternalMetaTypesProvider
{
    private static final String PROBE_TYPE_NAME = "XelgoContextLinksProbe"; //$NON-NLS-1$
    private static final String PROBE_PROPERTY_NAME = "ContextLinksProviderWasCalled"; //$NON-NLS-1$

    public ContextLinksExternalMetaTypesProvider()
    {
        ContextLinks.logWarning("EDT Context Links external meta types provider constructed"); //$NON-NLS-1$
    }

    @Override
    public Collection<Type> getExternalTypes(Resource context)
    {
        ContextLinks.logDebug("EDT Context Links DEBUG [external.provider.enter] resource=" + describeResource(context)); //$NON-NLS-1$

        if (context == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [external.provider.exit] result=[] reason=context-null"); //$NON-NLS-1$
            return List.of();
        }

        IProject currentProject = ContextLinks.getProject(context.getURI());
        if (currentProject == null)
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [external.provider.exit] result=[] reason=project-null"); //$NON-NLS-1$
            return List.of();
        }

        if (!currentProject.isAccessible())
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [external.provider.exit] project=" + currentProject.getName() //$NON-NLS-1$
                + " result=[] reason=project-not-accessible"); //$NON-NLS-1$
            return List.of();
        }

        Set<String> linkedProjectNames = ContextLinks.getContextProjectNames(currentProject);
        ContextLinks.logDebug("EDT Context Links DEBUG [external.provider.settings] project=" + currentProject.getName() //$NON-NLS-1$
            + " linked=" + linkedProjectNames + " workspace=" + describeWorkspace(linkedProjectNames)); //$NON-NLS-1$ //$NON-NLS-2$

        if (linkedProjectNames.isEmpty())
        {
            ContextLinks.logDebug("EDT Context Links DEBUG [external.provider.exit] project=" + currentProject.getName() //$NON-NLS-1$
                + " result=[] reason=no-linked-projects"); //$NON-NLS-1$
            return List.of();
        }

        Type probeType = createProbeType();
        ContextLinks.logWarning("EDT Context Links external meta types provider returns probe type " + PROBE_TYPE_NAME //$NON-NLS-1$
            + " for project=" + currentProject.getName() + ", linked=" + linkedProjectNames); //$NON-NLS-1$
        return List.of(probeType);
    }

    private Type createProbeType()
    {
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(PROBE_TYPE_NAME);
        type.setNameRu(PROBE_TYPE_NAME);
        type.setEnvironments(Environments.ALL);
        type.setContextDef(createProbeContextDef());
        return type;
    }

    private ContextDef createProbeContextDef()
    {
        ContextDef contextDef = McoreFactory.eINSTANCE.createContextDef();
        contextDef.setEnvironments(Environments.ALL);
        contextDef.getProperties().add(createProbeProperty());
        return contextDef;
    }

    private Property createProbeProperty()
    {
        Property property = McoreFactory.eINSTANCE.createProperty();
        property.setName(PROBE_PROPERTY_NAME);
        property.setNameRu(PROBE_PROPERTY_NAME);
        property.setReadable(true);
        property.setWritable(false);
        property.setEnvironments(Environments.ALL);
        return property;
    }

    private String describeResource(Resource context)
    {
        if (context == null)
            return "NULL"; //$NON-NLS-1$

        return String.valueOf(context.getURI());
    }

    private String describeWorkspace(Set<String> linkedProjectNames)
    {
        return linkedProjectNames.stream()
            .map(this::describeLinkedProject)
            .collect(Collectors.joining(", ", "[", "]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private String describeLinkedProject(String linkedProjectName)
    {
        try
        {
            IProject linkedProject = ResourcesPlugin.getWorkspace().getRoot().getProject(linkedProjectName);
            return linkedProjectName + "{exists=" + linkedProject.exists() + ", accessible=" //$NON-NLS-1$ //$NON-NLS-2$
                + linkedProject.isAccessible() + "}"; //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            ContextLinks.logError("EDT Context Links failed to describe linked project " + linkedProjectName, e); //$NON-NLS-1$
            return linkedProjectName + "{error=" + e.getClass().getSimpleName() + "}"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
