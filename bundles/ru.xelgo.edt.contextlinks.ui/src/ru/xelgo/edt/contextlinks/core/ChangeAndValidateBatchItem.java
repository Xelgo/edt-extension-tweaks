package ru.xelgo.edt.contextlinks.core;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;

/**
 * One extension method whose text diverged from the base method.
 */
public final class ChangeAndValidateBatchItem
{
    private final IFile extensionFile;
    private final String extensionProjectName;
    private final String modulePresentation;
    private final String methodName;
    private final String sourceMethodText;
    private final String extensionMethodText;
    private final String ancestorMethodText;
    private final int methodOffset;
    private final int methodLength;
    private final List<String> warnings;

    private String resultText;
    private boolean checked;

    ChangeAndValidateBatchItem(IFile extensionFile, String extensionProjectName, String modulePresentation,
        String methodName, String sourceMethodText, String extensionMethodText, String ancestorMethodText,
        String suggestedText, int methodOffset, int methodLength, List<String> warnings)
    {
        this.extensionFile = extensionFile;
        this.extensionProjectName = extensionProjectName;
        this.modulePresentation = modulePresentation;
        this.methodName = methodName;
        this.sourceMethodText = sourceMethodText;
        this.extensionMethodText = extensionMethodText;
        this.ancestorMethodText = ancestorMethodText;
        this.resultText = suggestedText;
        this.methodOffset = methodOffset;
        this.methodLength = methodLength;
        this.warnings = warnings == null ? new ArrayList<>() : new ArrayList<>(warnings);
        // Items with warnings are never applied blindly: the user has to look at them first.
        this.checked = this.warnings.isEmpty();
    }

    public IFile getExtensionFile()
    {
        return extensionFile;
    }

    public String getExtensionProjectName()
    {
        return extensionProjectName;
    }

    public String getModulePresentation()
    {
        return modulePresentation;
    }

    public String getMethodName()
    {
        return methodName;
    }

    public String getSourceMethodText()
    {
        return sourceMethodText;
    }

    public String getExtensionMethodText()
    {
        return extensionMethodText;
    }

    public String getAncestorMethodText()
    {
        return ancestorMethodText;
    }

    public String getResultText()
    {
        return resultText;
    }

    public void setResultText(String resultText)
    {
        this.resultText = resultText;
    }

    public int getMethodOffset()
    {
        return methodOffset;
    }

    public int getMethodLength()
    {
        return methodLength;
    }

    public List<String> getWarnings()
    {
        return warnings;
    }

    public boolean hasWarnings()
    {
        return !warnings.isEmpty();
    }

    public boolean isChecked()
    {
        return checked;
    }

    public void setChecked(boolean checked)
    {
        this.checked = checked;
    }

    /** Nothing to apply when the result equals the current method text. */
    public boolean isNoop()
    {
        return resultText == null || resultText.equals(extensionMethodText);
    }

    @Override
    public String toString()
    {
        return extensionProjectName + " / " + modulePresentation + " / " + methodName; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
