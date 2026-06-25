package ru.xelgo.edt.contextlinks.ui;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Image;

/**
 * Compare input for two variable snapshot JSON documents.
 */
final class VariableSnapshotsCompareInput
    extends CompareEditorInput
{
    private final VariableSnapshot left;
    private final VariableSnapshot right;

    VariableSnapshotsCompareInput(VariableSnapshot left, VariableSnapshot right)
    {
        super(configuration(left, right));
        this.left = left;
        this.right = right;
        setTitle("Сравнение снимков переменных"); //$NON-NLS-1$
    }

    @Override
    protected Object prepareInput(IProgressMonitor monitor)
        throws InvocationTargetException, InterruptedException
    {
        return new DiffNode(null, Differencer.CHANGE, null, new JsonElement(left), new JsonElement(right));
    }

    private static CompareConfiguration configuration(VariableSnapshot left, VariableSnapshot right)
    {
        CompareConfiguration configuration = new CompareConfiguration();
        configuration.setLeftEditable(false);
        configuration.setRightEditable(false);
        configuration.setLeftLabel(left.timestamp + " | " + left.location); //$NON-NLS-1$
        configuration.setRightLabel(right.timestamp + " | " + right.location); //$NON-NLS-1$
        return configuration;
    }

    private static final class JsonElement
        implements ITypedElement, IStreamContentAccessor
    {
        private final VariableSnapshot snapshot;

        JsonElement(VariableSnapshot snapshot)
        {
            this.snapshot = snapshot;
        }

        @Override
        public InputStream getContents()
            throws CoreException
        {
            return new ByteArrayInputStream(snapshot.json.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Image getImage()
        {
            return null;
        }

        @Override
        public String getName()
        {
            return snapshot.timestamp + ".json"; //$NON-NLS-1$
        }

        @Override
        public String getType()
        {
            return ITypedElement.TEXT_TYPE;
        }
    }
}
