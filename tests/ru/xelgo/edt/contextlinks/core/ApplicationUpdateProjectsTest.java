package ru.xelgo.edt.contextlinks.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.ISources;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseUpdateCallback;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseSyncResolution;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseEqualityState;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseChangesResolutionResult;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.e1c.g5.dt.applications.wst.IServerApplication;

import ru.xelgo.edt.contextlinks.ui.ConfigureApplicationUpdateProjectsHandler;

/**
 * Regression tests using the installed EDT API and in-memory project properties, without starting an IDE or an IB.
 */
public class ApplicationUpdateProjectsTest
{
    private final IProject configuration = project("Configuration");
    private final IProject first = project("Extension-clone-1");
    private final IProject second = project("Extension-clone-2");
    private final InfobaseReference left = infobase(UUID.randomUUID(), "IB 1");
    private final InfobaseReference right = infobase(UUID.randomUUID(), "IB 2");

    @Test
    public void exclusionsAreIndependentForTwoInfobasesAndConfigurationProjects() throws Exception
    {
        exclude(left, second);
        exclude(right, first);
        assertTrue(ContextLinks.isApplicationUpdateDisabled(configuration, left, second));
        assertFalse(ContextLinks.isApplicationUpdateDisabled(configuration, left, first));
        assertTrue(ContextLinks.isApplicationUpdateDisabled(configuration, right, first));
        assertFalse(ContextLinks.isApplicationUpdateDisabled(configuration, right, second));

        IProject otherConfiguration = project("Other configuration");
        assertTrue(ContextLinks.getDisabledApplicationUpdateProjectNames(otherConfiguration, left).isEmpty());
        assertEquals(Set.of(second.getName()), ContextLinks.getDisabledApplicationUpdateProjectNames(configuration,
            infobase(left.getUuid(), "Renamed and reloaded IB")));
    }

    @Test
    public void legacySettingsAreDefaultsUntilAnInfobaseIsConfigured() throws Exception
    {
        ContextLinks.setDisabledApplicationUpdateProjectNames(configuration, Set.of(first.getName()));
        assertEquals(Set.of(first.getName()), ContextLinks.getDisabledApplicationUpdateProjectNames(configuration, left));
        exclude(left, second);
        assertEquals(Set.of(second.getName()), ContextLinks.getDisabledApplicationUpdateProjectNames(configuration, left));
        assertEquals(Set.of(first.getName()), ContextLinks.getDisabledApplicationUpdateProjectNames(configuration, right));
        assertEquals(Set.of(first.getName()), ContextLinks.getDisabledApplicationUpdateProjectNames(configuration));
    }

    @Test
    public void explicitEmptyListOverridesLegacyDefaultsAndSurvivesReload() throws Exception
    {
        ContextLinks.setDisabledApplicationUpdateProjectNames(configuration, Set.of(first.getName()));
        ContextLinks.setDisabledApplicationUpdateProjectNames(configuration, left, Set.of());
        ContextLinks.setDisabledApplicationUpdateProjectNames(configuration, Set.of(second.getName()));
        assertTrue(ContextLinks.getDisabledApplicationUpdateProjectNames(configuration, left).isEmpty());
        assertEquals(Set.of(second.getName()), ContextLinks.getDisabledApplicationUpdateProjectNames(configuration, right));
        assertTrue(ContextLinks.hasApplicationUpdateProjectSettings(configuration));

        Map<QualifiedName, String> properties = configuration.getPersistentProperties();
        IProject reloadedProject = project(configuration.getName(), properties);
        assertTrue(ContextLinks.getDisabledApplicationUpdateProjectNames(reloadedProject,
            infobase(left.getUuid(), "Reloaded IB")).isEmpty());
        assertTrue(properties.containsValue(""));
    }

    @Test
    public void savingWithoutInfobaseIdentityCannotChangeProjectDefaults() throws Exception
    {
        ContextLinks.setDisabledApplicationUpdateProjectNames(configuration, Set.of(first.getName()));
        for (InfobaseReference invalid : new InfobaseReference[] { null, infobase(null, "Missing UUID") })
        {
            try
            {
                ContextLinks.setDisabledApplicationUpdateProjectNames(configuration, invalid, Set.of(second.getName()));
                fail("An infobase UUID is required");
            }
            catch (CoreException expected)
            {
                assertEquals(IStatus.ERROR, expected.getStatus().getSeverity());
            }
        }
        assertEquals(Set.of(first.getName()), ContextLinks.getDisabledApplicationUpdateProjectNames(configuration));
    }

    @Test
    public void dialogReadsTheSelectedInfobaseSettings() throws Throwable
    {
        exclude(left, second);
        exclude(right, first);
        assertEquals(List.of(second), dialogSelection(application(left)));
        assertEquals(List.of(first), dialogSelection(application(right)));
    }

    @Test
    public void standaloneApplicationResolvesTheSameInfobaseAsTheUpdateManager() throws Throwable
    {
        IServerApplication standalone = standaloneApplication(left);
        assertSame(standalone, selectedApplication(standalone));

        InfobaseReference fromDialog = applicationInfobase(standalone);
        assertSame(left, fromDialog);
        assertSame(right, applicationInfobase(application(right)));
        assertNull(selectedApplication(standalone, application(right)));
        assertNull(selectedApplication(configuration));
        assertNull(applicationInfobase(standaloneApplication(null)));

        exclude(fromDialog, second);
        assertEquals(List.of(second), dialogSelection(standalone));
        assertTrue(dialogSelection(application(right)).isEmpty());

        FakeDelegate delegate = delegate();
        InfobaseReference fromUpdate = infobase(left.getUuid(), "Standalone designer endpoint");
        assertTrue(update(delegate, configuration, fromUpdate, false, new NullProgressMonitor()).isOK());
        assertEquals(List.of(call(configuration, fromUpdate, false), call(first, fromUpdate, false)), delegate.calls);
    }

    @Test
    public void enabledParentDoesNotUpdateAnExcludedDependentForEitherInfobase() throws Throwable
    {
        exclude(left, second);
        exclude(right, first);
        FakeDelegate delegate = delegate();
        assertTrue(update(delegate, configuration, left, false, new NullProgressMonitor()).isOK());
        assertEquals(List.of(call(configuration, left, false), call(first, left, false)), delegate.calls);
        delegate.calls.clear();
        assertTrue(update(delegate, configuration, right, false, new NullProgressMonitor()).isOK());
        assertEquals(List.of(call(configuration, right, false), call(second, right, false)), delegate.calls);
    }

    @Test
    public void excludedParentStillRoutesReloadToEnabledDescendants() throws Throwable
    {
        exclude(left, configuration, first);
        IProject nested = project("Nested extension");
        FakeDelegate delegate = delegate();
        delegate.add(nested, left, right);
        delegate.dependents.put(first, List.of(nested));
        assertTrue(update(delegate, configuration, left, true, new NullProgressMonitor()).isOK());
        assertEquals(List.of(call(nested, left, true), call(second, left, true)), delegate.calls);
    }

    @Test
    public void updateAllFiltersEachConnectedInfobaseIncludingLegacyDefaults() throws Throwable
    {
        ContextLinks.setDisabledApplicationUpdateProjectNames(configuration, Set.of(first.getName()));
        exclude(left, second);
        FakeDelegate delegate = delegate();
        Object proxy = proxy();
        @SuppressWarnings("unchecked")
        Map<Object, Object> result = (Map<Object, Object>)invoke(proxy, "updateAllInfobases",
            new Class<?>[] { Object.class, IProject.class, IProject.class, Object[].class },
            delegate, configuration, configuration, new Object[] { configuration, null, new NullProgressMonitor() });
        assertEquals(List.of(call(configuration, left, false), call(configuration, right, false),
            call(first, left, false), call(second, right, false)), delegate.calls);
        assertEquals(Set.of(left, right), result.keySet());
    }

    @Test
    public void updateAllHonorsAnEmptyOverrideOfLegacyExclusions() throws Throwable
    {
        ContextLinks.setDisabledApplicationUpdateProjectNames(configuration,
            Set.of(configuration.getName(), first.getName(), second.getName()));
        ContextLinks.setDisabledApplicationUpdateProjectNames(configuration, left, Set.of());
        FakeDelegate delegate = delegate();
        invoke(proxy(), "updateAllInfobases",
            new Class<?>[] { Object.class, IProject.class, IProject.class, Object[].class },
            delegate, configuration, configuration, new Object[] { configuration, null, new NullProgressMonitor() });
        assertEquals(List.of(call(configuration, left, false), call(first, left, false), call(second, left, false)),
            delegate.calls);
    }

    @Test
    public void failedOrCancelledUpdatesDoNotContinueWithOtherProjects() throws Throwable
    {
        exclude(left, configuration);
        FakeDelegate delegate = delegate();
        delegate.synchronizations.get(first).result = Status.CANCEL_STATUS;
        assertFalse(update(delegate, configuration, left, false, new NullProgressMonitor()).isOK());
        assertEquals(List.of(call(first, left, false)), delegate.calls);
        delegate.calls.clear();
        NullProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        assertFalse(update(delegate, configuration, left, false, monitor).isOK());
        assertTrue(delegate.calls.isEmpty());
    }

    @Test
    public void incrementalUpdatePreservesNativeFallbackWhenAvailable() throws Throwable
    {
        exclude(left, second);
        FallbackDelegate delegate = new FallbackDelegate();
        delegate.add(configuration, left, right);
        delegate.add(first, left, right);
        delegate.add(second, left, right);
        delegate.dependents.put(configuration, List.of(first, second));
        assertTrue(update(delegate, configuration, left, false, new NullProgressMonitor()).isOK());
        assertEquals(2, delegate.fallbackCalls);
        assertEquals(List.of(call(configuration, left, false), call(first, left, false)), delegate.calls);
    }

    @Test
    public void synchronizationStateIsMaskedOnlyForTheSelectedInfobase() throws Throwable
    {
        exclude(left, first);
        Object proxy = proxy();
        Method equalityState = IInfobaseSynchronizationManager.class.getMethod("getEqualityState", IProject.class,
            InfobaseReference.class);
        Class<?>[] signature = { Method.class, IProject.class, IProject.class, InfobaseReference.class };
        assertEquals(InfobaseEqualityState.EQUAL,
            invoke(proxy, "disabledValue", signature, equalityState, configuration, first, left));
        Object delegated = invoke(proxy, "disabledValue", signature, equalityState, configuration, first, right);
        assertFalse(delegated instanceof InfobaseEqualityState);

        Method isConnected = IInfobaseSynchronizationManager.class.getMethod("isConnected", IProject.class,
            InfobaseReference.class);
        assertEquals(Boolean.TRUE, invoke(proxy, "disabledValue", signature, isConnected, configuration, first, left));
        assertEquals(delegated, invoke(proxy, "disabledValue", signature, isConnected, configuration, first, right));
    }

    @Test
    public void unresolvedConflictsStopTheFilteredUpdateChain() throws Throwable
    {
        exclude(left, second);
        FakeDelegate delegate = delegate();
        delegate.synchronizations.get(configuration).result =
            new InfobaseSyncResolution(InfobaseChangesResolutionResult.CHANGES_NOT_RESOLVED);
        assertFalse(update(delegate, configuration, left, false, new NullProgressMonitor()).isOK());
        assertEquals(List.of(call(configuration, left, false)), delegate.calls);
    }

    @Test
    public void filteredUpdatesSupportTheLegacyBooleanApi() throws Throwable
    {
        exclude(left, second);
        FakeDelegate delegate = delegate();
        Method publicMethod = BooleanUpdateApi.class.getMethod("updateInfobase");
        Object result = invoke(proxy(), "updateFilteredInfobases",
            new Class<?>[] { Object.class, Method.class, IProject.class, IProject.class, InfobaseReference.class,
                Object[].class }, delegate, publicMethod, configuration, configuration, left,
            new Object[] { configuration, left, null, Boolean.FALSE, new NullProgressMonitor() });
        assertEquals(Boolean.TRUE, result);
        assertEquals(List.of(call(configuration, left, false), call(first, left, false)), delegate.calls);
    }

    private void exclude(InfobaseReference infobase, IProject... projects) throws CoreException
    {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (IProject project : projects)
            names.add(project.getName());
        ContextLinks.setDisabledApplicationUpdateProjectNames(configuration, infobase, names);
    }

    private IInfobaseApplication application(InfobaseReference infobase)
    {
        return mock(IInfobaseApplication.class, (proxy, method, args) -> switch (method.getName())
        {
            case "getProject" -> configuration;
            case "getInfobase" -> infobase;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private IServerApplication standaloneApplication(InfobaseReference infobase)
    {
        return mock(IServerApplication.class, (proxy, method, args) -> switch (method.getName())
        {
            case "getProject" -> configuration;
            case "getAdapter" -> args[0] == InfobaseReference.class ? infobase : null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static IApplication selectedApplication(Object... elements) throws Throwable
    {
        StructuredSelection selection = new StructuredSelection(elements);
        EvaluationContext context = new EvaluationContext(null, selection);
        context.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, selection);
        return (IApplication)invoke(new ConfigureApplicationUpdateProjectsHandler(), "getSelectedApplication",
            new Class<?>[] { ExecutionEvent.class }, new ExecutionEvent(null, Map.of(), null, context));
    }

    private static InfobaseReference applicationInfobase(IApplication application) throws Throwable
    {
        return (InfobaseReference)invoke(new ConfigureApplicationUpdateProjectsHandler(), "getInfobase",
            new Class<?>[] { IApplication.class }, application);
    }

    private List<Object> dialogSelection(IApplication application) throws Throwable
    {
        return List.of((Object[])invoke(new ConfigureApplicationUpdateProjectsHandler(), "getInitialSelection",
            new Class<?>[] { IProject.class, InfobaseReference.class, IProject[].class },
            application.getProject(), applicationInfobase(application), new IProject[] { configuration, first, second }));
    }

    private FakeDelegate delegate()
    {
        FakeDelegate result = new FakeDelegate();
        result.add(configuration, left, right);
        result.add(first, left, right);
        result.add(second, left, right);
        result.dependents.put(configuration, List.of(first, second));
        return result;
    }

    private IStatus update(FakeDelegate delegate, IProject project, InfobaseReference infobase, boolean reload,
        IProgressMonitor monitor) throws Throwable
    {
        Method publicMethod = IInfobaseSynchronizationManager.class.getMethod(reload ? "reloadInfobase" : "updateInfobase",
            IProject.class, InfobaseReference.class, IInfobaseUpdateCallback.class, boolean.class, IProgressMonitor.class);
        return (IStatus)invoke(proxy(), "updateFilteredInfobases",
            new Class<?>[] { Object.class, Method.class, IProject.class, IProject.class, InfobaseReference.class,
                Object[].class }, delegate, publicMethod, configuration, project, infobase,
            new Object[] { project, infobase, null, Boolean.FALSE, monitor });
    }

    private static Object proxy() throws Exception
    {
        Constructor<?> constructor = ContextLinksInfobaseSynchronizationManagerProxy.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(new Object[] { null });
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Throwable
    {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try
        {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException e)
        {
            throw e.getTargetException();
        }
    }

    private static IProject project(String name)
    {
        return project(name, new LinkedHashMap<>());
    }

    private static IProject project(String name, Map<QualifiedName, String> properties)
    {
        return mock(IProject.class, (proxy, method, args) -> switch (method.getName())
        {
            case "getName" -> name;
            case "isAccessible" -> true;
            case "getPersistentProperty" -> properties.get(args[0]);
            case "getPersistentProperties" -> new LinkedHashMap<>(properties);
            case "setPersistentProperty" -> {
                if (args[1] == null)
                    properties.remove(args[0]);
                else
                    properties.put((QualifiedName)args[0], (String)args[1]);
                yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static InfobaseReference infobase(UUID uuid, String name)
    {
        return mock(InfobaseReference.class, (proxy, method, args) -> switch (method.getName())
        {
            case "getUuid" -> uuid;
            case "getName" -> name;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private static <T> T mock(Class<T> type, InvocationHandler handler)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class)
            {
                return switch (method.getName())
                {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName();
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            return handler.invoke(proxy, method, args);
        }));
    }

    private static String call(IProject project, InfobaseReference infobase, boolean reload)
    {
        return project.getName() + ":" + infobase.getUuid() + ":" + reload;
    }

    public static class FakeDelegate
    {
        protected final Map<IProject, FakeSynchronization> synchronizations = new HashMap<>();
        protected final Map<IProject, List<IProject>> dependents = new HashMap<>();
        protected final List<String> calls = new ArrayList<>();

        protected void add(IProject project, InfobaseReference... infobases)
        {
            synchronizations.put(project, new FakeSynchronization(project, List.of(infobases), calls));
        }

        public FakeSynchronization getOrCreateSynchronization(IProject project)
        {
            return synchronizations.get(project);
        }

        public List<IProject> getDependentExtensionProjects(IProject project)
        {
            return dependents.getOrDefault(project, List.of());
        }

        public void synchronizeConnectionsWithApplications(IProject project, FakeSynchronization synchronization)
        {
            // Connections are represented by the fixture's infobase list.
        }
    }

    public static final class FallbackDelegate extends FakeDelegate
    {
        private int fallbackCalls;

        public Object updateIncrementallyWithFallback(InfobaseReference infobase, Object callback,
            FakeSynchronization synchronization, IProgressMonitor monitor)
        {
            fallbackCalls++;
            return synchronization.updateConnectedInfobase(false, infobase, callback, monitor);
        }
    }

    public interface BooleanUpdateApi
    {
        boolean updateInfobase();
    }

    public static final class FakeSynchronization
    {
        private final IProject project;
        private final List<InfobaseReference> infobases;
        private final List<String> calls;
        private Object result = new InfobaseSyncResolution(true);

        private FakeSynchronization(IProject project, List<InfobaseReference> infobases, List<String> calls)
        {
            this.project = project;
            this.infobases = infobases;
            this.calls = calls;
        }

        public List<InfobaseReference> getConnectedInfobases()
        {
            return infobases;
        }

        public Object updateConnectedInfobase(boolean reload, InfobaseReference infobase, Object callback,
            IProgressMonitor monitor)
        {
            calls.add(call(project, infobase, reload));
            return result;
        }
    }
}
