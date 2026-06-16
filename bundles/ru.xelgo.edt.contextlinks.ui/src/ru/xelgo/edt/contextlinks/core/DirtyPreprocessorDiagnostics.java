package ru.xelgo.edt.contextlinks.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Debug-only timings for dirty preprocessor model rebuilds.
 */
final class DirtyPreprocessorDiagnostics
{
    private static final int LOG_LIMIT = Integer.getInteger(
        ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.diagnosticLogLimit", 250); //$NON-NLS-1$
    private static final long SLOW_MILLIS = Long.getLong(
        ContextLinks.PLUGIN_ID + ".dirtyPreprocessor.slowMillis", 100L); //$NON-NLS-1$
    private static final AtomicLong sequence = new AtomicLong();
    private static final AtomicInteger logged = new AtomicInteger();

    private DirtyPreprocessorDiagnostics()
    {
        // Utility class.
    }

    static long nextId()
    {
        return sequence.incrementAndGet();
    }

    static void logStart(long id, String stage, String details)
    {
        if (!ContextLinks.isDebugLoggingEnabled() || logged.getAndIncrement() >= LOG_LIMIT)
            return;

        ContextLinks.logDebug("EDT Extension Tweaks dirty timing start id=" + id //$NON-NLS-1$
            + " stage=" + stage //$NON-NLS-1$
            + " thread=" + Thread.currentThread().getName() //$NON-NLS-1$
            + " details=" + details //$NON-NLS-1$
            + " stack=" + stackTraceSummary()); //$NON-NLS-1$
    }

    static void logEnd(long id, String stage, long startedNanos, String details)
    {
        if (!ContextLinks.isDebugLoggingEnabled() || logged.getAndIncrement() >= LOG_LIMIT)
            return;

        long elapsedMillis = elapsedMillis(startedNanos);
        ContextLinks.logDebug("EDT Extension Tweaks dirty timing end id=" + id //$NON-NLS-1$
            + " stage=" + stage //$NON-NLS-1$
            + " elapsedMillis=" + elapsedMillis //$NON-NLS-1$
            + " thread=" + Thread.currentThread().getName() //$NON-NLS-1$
            + " details=" + details); //$NON-NLS-1$
    }

    static void logSlow(long id, String stage, long startedNanos, String details)
    {
        long elapsedMillis = elapsedMillis(startedNanos);
        if (elapsedMillis < SLOW_MILLIS)
            return;

        logEnd(id, "slow." + stage, startedNanos, details); //$NON-NLS-1$
    }

    static void logSkip(String stage, String details)
    {
        if (!ContextLinks.isDebugLoggingEnabled() || logged.getAndIncrement() >= LOG_LIMIT)
            return;

        ContextLinks.logDebug("EDT Extension Tweaks dirty timing skip" //$NON-NLS-1$
            + " stage=" + stage //$NON-NLS-1$
            + " thread=" + Thread.currentThread().getName() //$NON-NLS-1$
            + " details=" + details //$NON-NLS-1$
            + " stack=" + stackTraceSummary()); //$NON-NLS-1$
    }

    private static long elapsedMillis(long startedNanos)
    {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String stackTraceSummary()
    {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (StackTraceElement frame : stack)
        {
            String className = frame.getClassName();
            if (className.equals(Thread.class.getName())
                || className.equals(DirtyPreprocessorDiagnostics.class.getName()))
            {
                continue;
            }

            if (builder.length() > 0)
                builder.append(" <- "); //$NON-NLS-1$
            builder.append(className).append('#').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            count++;
            if (count >= 48)
                break;
        }
        return builder.toString();
    }
}
