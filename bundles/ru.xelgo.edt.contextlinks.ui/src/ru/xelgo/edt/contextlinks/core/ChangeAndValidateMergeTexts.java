package ru.xelgo.edt.contextlinks.core;

import org.eclipse.compare.CompareConfiguration;

import com._1c.g5.v8.dt.compare.ui.StringBasedTypedElement;

import ru.xelgo.edt.contextlinks.ui.Messages;

/**
 * Shared pieces of the ChangeAndValidate merge UI, used by both the single quickfix and the batch
 * dialog.
 */
public final class ChangeAndValidateMergeTexts
{
    private static final String BSL_TYPE = "bsl"; //$NON-NLS-1$

    private ChangeAndValidateMergeTexts()
    {
        // Utility class.
    }

    /** Compare configuration with the labels shared by both merge windows. */
    public static CompareConfiguration newCompareConfiguration()
    {
        CompareConfiguration configuration = new CompareConfiguration();
        configuration.setLeftLabel(Messages.ChangeAndValidateMergeQuickfix_LeftLabel);
        configuration.setRightLabel(Messages.ChangeAndValidateMergeQuickfix_RightLabel);
        configuration.setAncestorLabel(Messages.ChangeAndValidateMergeQuickfix_AncestorLabel);
        return configuration;
    }

    /** In-memory element typed as BSL so the merge viewer picks up syntax highlighting. */
    public static StringBasedTypedElement typedElement(String name, String content, boolean editable)
    {
        StringBasedTypedElement element = new StringBasedTypedElement(name, content == null ? "" : content, //$NON-NLS-1$
            editable);
        element.setType(BSL_TYPE);
        return element;
    }

    /** Rewrites line separators of {@code text} to the ones used by {@code reference}. */
    public static String normalizeLineSeparators(String text, String reference)
    {
        if (text == null)
            return ""; //$NON-NLS-1$

        String separator = reference != null && reference.contains("\r\n") ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", separator); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
