package ru.xelgo.edt.contextlinks.core;

import java.util.Collection;
import java.util.Comparator;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;

/**
 * Resolves the native EDT infobase synchronization manager behind EDT Extension Tweaks wrappers.
 */
public final class ContextLinksInfobaseSynchronizationServices
{
    private ContextLinksInfobaseSynchronizationServices()
    {
        // Utility class.
    }

    public static Lease acquireDelegate()
    {
        Bundle bundle = FrameworkUtil.getBundle(ContextLinksInfobaseSynchronizationServices.class);
        BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context == null)
            return null;

        try
        {
            Collection<ServiceReference<IInfobaseSynchronizationManager>> references =
                context.getServiceReferences(IInfobaseSynchronizationManager.class, null);
            if (references == null || references.isEmpty())
                return null;

            return references.stream()
                .filter(reference -> reference.getProperty(
                    ContextLinksInfobaseSynchronizationManagerRegistrar.WRAPPER_MARKER_PROPERTY) == null)
                .sorted(Comparator.comparingInt(ContextLinksInfobaseSynchronizationServices::serviceRanking)
                    .reversed()
                    .thenComparingLong(ContextLinksInfobaseSynchronizationServices::serviceId))
                .map(reference -> acquire(context, reference))
                .filter(lease -> lease != null)
                .findFirst()
                .orElse(null);
        }
        catch (InvalidSyntaxException e)
        {
            ContextLinks.logError("EDT Extension Tweaks failed to find native IInfobaseSynchronizationManager", e); //$NON-NLS-1$
            return null;
        }
    }

    private static Lease acquire(BundleContext context, ServiceReference<IInfobaseSynchronizationManager> reference)
    {
        IInfobaseSynchronizationManager service = context.getService(reference);
        return service != null ? new Lease(context, reference, service) : null;
    }

    private static int serviceRanking(ServiceReference<?> reference)
    {
        Object value = reference.getProperty(Constants.SERVICE_RANKING);
        return value instanceof Integer ? ((Integer)value).intValue() : 0;
    }

    private static long serviceId(ServiceReference<?> reference)
    {
        Object value = reference.getProperty(Constants.SERVICE_ID);
        return value instanceof Long ? ((Long)value).longValue() : Long.MAX_VALUE;
    }

    public static final class Lease
        implements AutoCloseable
    {
        private final BundleContext context;
        private final ServiceReference<IInfobaseSynchronizationManager> reference;
        private final IInfobaseSynchronizationManager service;

        private Lease(BundleContext context, ServiceReference<IInfobaseSynchronizationManager> reference,
            IInfobaseSynchronizationManager service)
        {
            this.context = context;
            this.reference = reference;
            this.service = service;
        }

        public IInfobaseSynchronizationManager service()
        {
            return service;
        }

        @Override
        public void close()
        {
            context.ungetService(reference);
        }
    }
}
