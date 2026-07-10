package ru.xelgo.edt.contextlinks.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TrayDialog;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.services.IServiceLocator;

import com._1c.g5.v8.dt.compare.ui.mergeviewer.ThreeSideTextMergeEditorInput;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.ThreeSideTextMergeInput;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.ThreeSideTextMergeViewer;
import com._1c.g5.v8.dt.compare.ui.mergeviewer.ThreeSideTextMergeViewerPanel;

import ru.xelgo.edt.contextlinks.core.ChangeAndValidateBatchItem;
import ru.xelgo.edt.contextlinks.core.ChangeAndValidateMergeTexts;

/**
 * One window for all diverged ChangeAndValidate methods: checkable tree on the left, the usual
 * four-pane merge on the right.
 */
public class ChangeAndValidateBatchMergeDialog
    extends TrayDialog
{
    private final List<ChangeAndValidateBatchItem> items;
    private final IServiceLocator serviceLocator;

    private CheckboxTreeViewer treeViewer;
    private ThreeSideTextMergeInput mergeInput;
    private ThreeSideTextMergeViewer mergeViewer;
    private CompareConfiguration compareConfiguration;
    private Label counterLabel;
    private List<ProjectNode> treeRoots = new ArrayList<>();
    private ChangeAndValidateBatchItem currentItem;
    private boolean resultsEdited;

    public ChangeAndValidateBatchMergeDialog(Shell shell, List<ChangeAndValidateBatchItem> items,
        IServiceLocator serviceLocator)
    {
        super(shell);
        this.items = items;
        this.serviceLocator = serviceLocator;
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    @Override
    protected void configureShell(Shell shell)
    {
        super.configureShell(shell);
        shell.setText(Messages.ChangeAndValidateBatch_DialogTitle);
    }

    @Override
    protected Point getInitialSize()
    {
        return new Point(1280, 800);
    }

    @Override
    protected boolean isResizable()
    {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent)
    {
        Composite area = (Composite)super.createDialogArea(parent);

        counterLabel = new Label(area, SWT.NONE);
        counterLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        SashForm sash = new SashForm(area, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createTree(sash);
        createMergePanel(sash);
        sash.setWeights(new int[] { 30, 70 });

        updateCounter();
        selectFirst();
        return area;
    }

    private void createTree(Composite parent)
    {
        Composite container = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        container.setLayout(layout);

        treeViewer = new CheckboxTreeViewer(container, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        treeViewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        treeViewer.setContentProvider(new TreeContent());
        treeViewer.setLabelProvider(new TreeLabels());
        treeRoots = buildTree();
        treeViewer.setInput(treeRoots);
        treeViewer.expandAll();

        for (ChangeAndValidateBatchItem item : items)
            treeViewer.setChecked(item, item.isChecked());
        refreshParentCheckStates();

        treeViewer.addCheckStateListener(this::onCheckStateChanged);
        treeViewer.addSelectionChangedListener(event -> {
            Object selected = event.getStructuredSelection().getFirstElement();
            if (selected instanceof ChangeAndValidateBatchItem item)
                showItem(item);
        });
    }

    private void createMergePanel(Composite parent)
    {
        compareConfiguration = ChangeAndValidateMergeTexts.newCompareConfiguration();

        mergeInput = new ThreeSideTextMergeInput(ChangeAndValidateMergeTexts.typedElement(Messages.ChangeAndValidateMergeQuickfix_LeftLabel, "", false), //$NON-NLS-1$
            ChangeAndValidateMergeTexts.typedElement(Messages.ChangeAndValidateMergeQuickfix_RightLabel, "", false), //$NON-NLS-1$
            ChangeAndValidateMergeTexts.typedElement(Messages.ChangeAndValidateMergeQuickfix_AncestorLabel, "", false), //$NON-NLS-1$
            ChangeAndValidateMergeTexts.typedElement(Messages.ChangeAndValidateMergeQuickfix_ResultLabel, "", true), //$NON-NLS-1$
            Messages.ChangeAndValidateBatch_DialogTitle);

        ThreeSideTextMergeEditorInput editorInput =
            new ThreeSideTextMergeEditorInput(compareConfiguration, mergeInput);
        ThreeSideTextMergeViewerPanel panel = editorInput.createMergeViewerPanel(parent, serviceLocator);
        mergeViewer = panel.getMergeViewer();
    }


    /** Pulls edits out of the merge viewer before anything else touches the input. */
    private void captureCurrentResult()
    {
        if (currentItem == null || mergeViewer == null)
            return;

        mergeViewer.flush(new NullProgressMonitor());
        String edited = mergeViewer.getResultContent();
        if (edited != null && !edited.equals(currentItem.getResultText()))
        {
            currentItem.setResultText(edited);
            resultsEdited = true;
        }
    }

    private void showItem(ChangeAndValidateBatchItem item)
    {
        if (item == currentItem || mergeInput == null)
            return;

        captureCurrentResult();
        currentItem = item;

        mergeInput.setLeft(ChangeAndValidateMergeTexts.typedElement(Messages.ChangeAndValidateMergeQuickfix_LeftLabel, item.getSourceMethodText(),
            false));
        mergeInput.setRight(ChangeAndValidateMergeTexts.typedElement(Messages.ChangeAndValidateMergeQuickfix_RightLabel,
            item.getExtensionMethodText(), false));
        mergeInput.setAncestor(ChangeAndValidateMergeTexts.typedElement(Messages.ChangeAndValidateMergeQuickfix_AncestorLabel,
            item.getAncestorMethodText(), false));
        mergeInput.setMergeResult(ChangeAndValidateMergeTexts.typedElement(Messages.ChangeAndValidateMergeQuickfix_ResultLabel, item.getResultText(),
            true));
        mergeInput.fireChange();
    }

    private void selectFirst()
    {
        if (!items.isEmpty())
            treeViewer.setSelection(new StructuredSelection(items.get(0)), true);
    }

    private void onCheckStateChanged(CheckStateChangedEvent event)
    {
        Object element = event.getElement();
        boolean checked = event.getChecked();

        if (element instanceof ChangeAndValidateBatchItem item)
        {
            item.setChecked(checked);
        }
        else if (element instanceof ModuleNode module)
        {
            setModuleChecked(module, checked);
        }
        else if (element instanceof ProjectNode project)
        {
            for (ModuleNode module : project.modules.values())
                setModuleChecked(module, checked);
        }

        refreshParentCheckStates();
        updateCounter();
    }

    private void setModuleChecked(ModuleNode module, boolean checked)
    {
        for (ChangeAndValidateBatchItem item : module.items)
        {
            item.setChecked(checked);
            treeViewer.setChecked(item, checked);
        }
    }

    /**
     * Keeps parent nodes in sync with their leaves: fully checked, unchecked, or grayed when only
     * part of the children is selected.
     */
    private void refreshParentCheckStates()
    {
        for (ProjectNode project : treeRoots)
        {
            int projectTotal = 0;
            int projectChecked = 0;
            for (ModuleNode module : project.modules.values())
            {
                int total = module.items.size();
                int checked = (int)module.items.stream().filter(ChangeAndValidateBatchItem::isChecked).count();
                applyTriState(module, checked, total);
                projectTotal += total;
                projectChecked += checked;
            }
            applyTriState(project, projectChecked, projectTotal);
        }
    }

    private void applyTriState(Object node, int checked, int total)
    {
        if (checked == 0)
        {
            treeViewer.setGrayed(node, false);
            treeViewer.setChecked(node, false);
        }
        else if (checked == total)
        {
            treeViewer.setGrayed(node, false);
            treeViewer.setChecked(node, true);
        }
        else
        {
            treeViewer.setGrayChecked(node, true);
        }
    }

    private void updateCounter()
    {
        long checked = items.stream().filter(ChangeAndValidateBatchItem::isChecked).count();
        long warned = items.stream().filter(ChangeAndValidateBatchItem::hasWarnings).count();
        String text = String.format(Messages.ChangeAndValidateBatch_Counter, checked, items.size());
        if (warned > 0)
            text = text + String.format(Messages.ChangeAndValidateBatch_CounterWarnings, warned);
        counterLabel.setText(text);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        createButton(parent, IDialogConstants.OK_ID, Messages.ChangeAndValidateBatch_ApplyButton, true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed()
    {
        // The last viewed item still holds its edit inside the viewer.
        captureCurrentResult();
        super.okPressed();
    }

    @Override
    protected void cancelPressed()
    {
        captureCurrentResult();
        if (resultsEdited && !MessageDialog.openConfirm(getShell(), Messages.ChangeAndValidateBatch_DialogTitle,
            Messages.ChangeAndValidateBatch_ConfirmDiscard))
        {
            return;
        }
        super.cancelPressed();
    }

    /**
     * Window#handleShellCloseEvent closes straight through, bypassing cancelPressed. Route the
     * window "x" into the same confirmation as the Cancel button.
     */
    @Override
    protected void handleShellCloseEvent()
    {
        cancelPressed();
    }

    @Override
    public boolean close()
    {
        boolean closed = super.close();
        if (closed && compareConfiguration != null)
        {
            compareConfiguration.dispose();
            compareConfiguration = null;
        }
        return closed;
    }


    // --- Tree: extension -> module -> method ---------------------------------------------------------

    private List<ProjectNode> buildTree()
    {
        Map<String, ProjectNode> projects = new LinkedHashMap<>();
        for (ChangeAndValidateBatchItem item : items)
        {
            ProjectNode project =
                projects.computeIfAbsent(item.getExtensionProjectName(), ProjectNode::new);
            // Key modules by file path, not by name: same-named modules of different extensions must not merge.
            String moduleKey = item.getExtensionFile().getFullPath().toString();
            ModuleNode module = project.modules.computeIfAbsent(moduleKey,
                key -> new ModuleNode(item.getModulePresentation()));
            module.items.add(item);
        }
        return new ArrayList<>(projects.values());
    }

    private static final class ProjectNode
    {
        final String name;
        final Map<String, ModuleNode> modules = new LinkedHashMap<>();

        ProjectNode(String name)
        {
            this.name = name;
        }
    }

    private static final class ModuleNode
    {
        final String name;
        final List<ChangeAndValidateBatchItem> items = new ArrayList<>();

        ModuleNode(String name)
        {
            this.name = name;
        }
    }

    private final class TreeContent
        implements ITreeContentProvider
    {
        @Override
        public Object[] getElements(Object input)
        {
            return ((List<?>)input).toArray();
        }

        @Override
        public Object[] getChildren(Object element)
        {
            if (element instanceof ProjectNode project)
                return project.modules.values().toArray();
            if (element instanceof ModuleNode module)
                return module.items.toArray();
            return new Object[0];
        }

        @Override
        public Object getParent(Object element)
        {
            return null;
        }

        @Override
        public boolean hasChildren(Object element)
        {
            return element instanceof ProjectNode || element instanceof ModuleNode;
        }
    }

    private static final class TreeLabels
        extends LabelProvider
    {
        @Override
        public String getText(Object element)
        {
            if (element instanceof ProjectNode project)
                return project.name;
            if (element instanceof ModuleNode module)
                return module.name;
            if (element instanceof ChangeAndValidateBatchItem item)
            {
                if (item.hasWarnings())
                    return item.getMethodName() + "  (!) " + String.join("; ", item.getWarnings()); //$NON-NLS-1$ //$NON-NLS-2$
                return item.getMethodName();
            }
            return String.valueOf(element);
        }
    }
}
