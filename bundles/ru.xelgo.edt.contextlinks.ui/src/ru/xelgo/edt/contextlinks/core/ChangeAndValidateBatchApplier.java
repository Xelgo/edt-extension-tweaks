package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

import ru.xelgo.edt.contextlinks.ui.Messages;

/**
 * Writes merge results back into extension modules.
 *
 * <p>The dialog may stay open for minutes, so offsets captured at collection time can point
 * somewhere else by the time OK is pressed. Every method is therefore re-checked against the live
 * document right before it is written: the text at {@code [offset, offset+length)} must still equal
 * the snapshot taken during collection. Mismatching methods are skipped and reported instead of
 * overwriting whatever moved into their place.
 *
 * <p>Check and write share one file-buffer session, so nothing can slip in between them. Within a
 * module the methods are written bottom-up, otherwise the first replacement would shift the offsets
 * of the rest.
 */
public final class ChangeAndValidateBatchApplier
{
    private ChangeAndValidateBatchApplier()
    {
        // Utility class.
    }

    /** Outcome of a batch apply: what landed and what was skipped, with reasons. */
    public static final class Report
    {
        private final List<String> applied = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();

        public List<String> getApplied()
        {
            return applied;
        }

        public List<String> getSkipped()
        {
            return skipped;
        }

        public boolean hasSkipped()
        {
            return !skipped.isEmpty();
        }
    }

    public static Report apply(List<ChangeAndValidateBatchItem> items, IProgressMonitor monitor)
    {
        Report report = new Report();

        Map<IFile, List<ChangeAndValidateBatchItem>> byFile = new LinkedHashMap<>();
        for (ChangeAndValidateBatchItem item : items)
        {
            if (item.isChecked() && !item.isNoop())
                byFile.computeIfAbsent(item.getExtensionFile(), file -> new ArrayList<>()).add(item);
        }

        SubMonitor progress = SubMonitor.convert(monitor, byFile.size());
        for (Map.Entry<IFile, List<ChangeAndValidateBatchItem>> entry : byFile.entrySet())
        {
            List<ChangeAndValidateBatchItem> fileItems = entry.getValue();
            // Bottom-up: otherwise the first replacement shifts the offsets of the remaining methods.
            fileItems.sort(Comparator.comparingInt(ChangeAndValidateBatchItem::getMethodOffset).reversed());
            applyToFile(entry.getKey(), fileItems, report, progress.split(1));
        }
        return report;
    }

    private static void applyToFile(IFile file, List<ChangeAndValidateBatchItem> items, Report report,
        IProgressMonitor monitor)
    {
        if (!file.exists())
        {
            skipAll(items, report, Messages.ChangeAndValidateBatch_SkipFileMissing);
            return;
        }
        if (file.isReadOnly())
        {
            skipAll(items, report, Messages.ChangeAndValidateBatch_SkipReadOnly);
            return;
        }

        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        IPath path = file.getFullPath();
        try
        {
            manager.connect(path, LocationKind.IFILE, monitor);
        }
        catch (CoreException e)
        {
            ContextLinks.logError("EDT Extension Tweaks batch merge: cannot open " + path, e); //$NON-NLS-1$
            skipAll(items, report, Messages.ChangeAndValidateBatch_SkipNoBuffer);
            return;
        }

        ITextFileBuffer buffer = null;
        List<ChangeAndValidateBatchItem> writable = new ArrayList<>();
        // Everything already accounted for in the report, so a failure never double-counts
        // and never silently drops a method.
        Set<ChangeAndValidateBatchItem> reported = new HashSet<>();
        try
        {
            buffer = manager.getTextFileBuffer(path, LocationKind.IFILE);
            if (buffer == null)
            {
                skipAll(items, report, Messages.ChangeAndValidateBatch_SkipNoBuffer);
                return;
            }

            IDocument document = buffer.getDocument();

            // Check every method before touching the document. A replace failing halfway would leave
            // the shared buffer - and therefore an open editor - holding a half-rewritten module.
            for (ChangeAndValidateBatchItem item : items)
            {
                if (stillMatches(document, item))
                {
                    writable.add(item);
                }
                else
                {
                    report.getSkipped().add(item + " - " + Messages.ChangeAndValidateBatch_SkipModuleChanged); //$NON-NLS-1$
                    reported.add(item);
                }
            }

            if (writable.isEmpty())
                return;

            for (ChangeAndValidateBatchItem item : writable)
            {
                String replacement = ChangeAndValidateMergeTexts.normalizeLineSeparators(item.getResultText(),
                    item.getExtensionMethodText());
                document.replace(item.getMethodOffset(), item.getMethodLength(), replacement);
            }

            // When the module is open in an editor the buffer is shared with its document,
            // so the edit reaches the editor instead of being overwritten.
            buffer.commit(monitor, false);

            // Only a committed module counts as applied.
            for (ChangeAndValidateBatchItem item : writable)
            {
                report.getApplied().add(item.toString());
                reported.add(item);
            }
        }
        catch (OperationCanceledException e)
        {
            revertQuietly(buffer, monitor);
            throw e;
        }
        catch (CoreException | BadLocationException | RuntimeException e)
        {
            ContextLinks.logError("EDT Extension Tweaks batch merge: failed to write " + path, e); //$NON-NLS-1$
            // Drop the half-written document, otherwise an open editor keeps the partial rewrite.
            revertQuietly(buffer, monitor);
            String reason = reasonOf(e);
            for (ChangeAndValidateBatchItem item : items)
            {
                if (!reported.contains(item))
                    report.getSkipped().add(item + " - " + reason); //$NON-NLS-1$
            }
        }
        finally
        {
            disconnectQuietly(manager, path, monitor);
        }
    }

    /** Discards uncommitted edits so a shared buffer never keeps a half-rewritten module. */
    private static void revertQuietly(ITextFileBuffer buffer, IProgressMonitor monitor)
    {
        if (buffer == null || !buffer.isDirty())
            return;

        try
        {
            buffer.revert(monitor);
        }
        catch (CoreException | RuntimeException e)
        {
            ContextLinks.logError("EDT Extension Tweaks batch merge: cannot revert buffer", e); //$NON-NLS-1$
        }
    }

    private static String reasonOf(Exception e)
    {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** Guards against writing over a method that moved or changed while the dialog was open. */
    private static boolean stillMatches(IDocument document, ChangeAndValidateBatchItem item)
        throws BadLocationException
    {
        int offset = item.getMethodOffset();
        int length = item.getMethodLength();
        if (offset < 0 || length < 0 || offset + length > document.getLength())
            return false;

        return document.get(offset, length).equals(item.getExtensionMethodText());
    }

    private static void skipAll(List<ChangeAndValidateBatchItem> items, Report report, String reason)
    {
        for (ChangeAndValidateBatchItem item : items)
            report.getSkipped().add(item + " - " + reason); //$NON-NLS-1$
    }

    private static void disconnectQuietly(ITextFileBufferManager manager, IPath path, IProgressMonitor monitor)
    {
        try
        {
            manager.disconnect(path, LocationKind.IFILE, monitor);
        }
        catch (CoreException e)
        {
            ContextLinks.logError("EDT Extension Tweaks batch merge: cannot release " + path, e); //$NON-NLS-1$
        }
    }

}
