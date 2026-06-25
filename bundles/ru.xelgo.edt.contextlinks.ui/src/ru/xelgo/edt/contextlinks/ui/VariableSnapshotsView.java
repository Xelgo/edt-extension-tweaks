package ru.xelgo.edt.contextlinks.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.compare.CompareUI;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

import ru.xelgo.edt.contextlinks.core.ContextLinks;
import ru.xelgo.edt.contextlinks.core.ContextLinksPreferences;

/**
 * Stores and compares debugger variable snapshots.
 */
public class VariableSnapshotsView
    extends ViewPart
{
    public static final String ID = "ru.xelgo.edt.contextlinks.ui.views.variableSnapshots"; //$NON-NLS-1$

    private final List<VariableSnapshot> snapshots = new ArrayList<>();
    private TableViewer viewer;
    private Text jsonPreview;

    @Override
    public void createPartControl(Composite parent)
    {
        SashForm sashForm = new SashForm(parent, SWT.VERTICAL);
        createTable(sashForm);
        jsonPreview = new Text(sashForm, SWT.BORDER | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.READ_ONLY);
        sashForm.setWeights(new int[] { 55, 45 });

        snapshots.addAll(VariableSnapshotStorage.load());
        refresh();
        createActions();
    }

    @Override
    public void setFocus()
    {
        if (viewer != null)
            viewer.getControl().setFocus();
    }

    private void createTable(Composite parent)
    {
        viewer = new TableViewer(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(GridDataFactory.fillDefaults().grab(true, true).create());
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        table.addListener(SWT.KeyDown, event -> {
            if (event.keyCode == SWT.DEL)
                clearSnapshots();
        });

        addColumn("Дата", 150, snapshot -> snapshot.timestamp); //$NON-NLS-1$
        addColumn("Место остановки", 380, snapshot -> snapshot.location); //$NON-NLS-1$
        addColumn("Выражений", 90, snapshot -> Integer.toString(snapshot.expressionCount)); //$NON-NLS-1$

        viewer.addSelectionChangedListener(event -> updatePreview());
    }

    private void addColumn(String title, int width, SnapshotTextProvider textProvider)
    {
        TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.getColumn().setResizable(true);
        column.setLabelProvider(new ColumnLabelProvider()
        {
            @Override
            public String getText(Object element)
            {
                return textProvider.getText((VariableSnapshot)element);
            }
        });
    }

    private void createActions()
    {
        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        toolbar.add(new Action("Сохранить снимок переменных", //$NON-NLS-1$
            PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_OBJ_ADD))
        {
            @Override
            public void run()
            {
                captureSnapshot();
            }
        });
        toolbar.add(new Action("Сравнить выбранные снимки", //$NON-NLS-1$
            PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_TOOL_FORWARD))
        {
            @Override
            public void run()
            {
                compareSelected();
            }
        });
        toolbar.add(new Action("Сохранить снимки в файл", //$NON-NLS-1$
            PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_ETOOL_SAVE_EDIT))
        {
            @Override
            public void run()
            {
                saveToFile();
            }
        });
        toolbar.add(new Action("Загрузить снимки из файла", //$NON-NLS-1$
            PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_OBJ_FOLDER))
        {
            @Override
            public void run()
            {
                loadFromFile();
            }
        });
        toolbar.add(new Action("Очистить снимки", //$NON-NLS-1$
            PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_TOOL_DELETE))
        {
            @Override
            public void run()
            {
                clearSnapshots();
            }
        });
    }

    private void captureSnapshot()
    {
        if (!ContextLinksPreferences.isVariableSnapshotsEnabled())
        {
            MessageDialog.openInformation(getSite().getShell(), "Снимки переменных", //$NON-NLS-1$
                "Функционал снимков переменных выключен в настройках EDT Extension Tweaks."); //$NON-NLS-1$
            return;
        }

        try
        {
            VariableSnapshot snapshot = new VariableSnapshotCaptureService().capture();
            snapshots.add(snapshot);
            snapshots.sort(Comparator.comparing(item -> item.timestamp));
            persistAndRefresh();
            viewer.setSelection(new StructuredSelection(snapshot), true);
        }
        catch (RuntimeException e)
        {
            ContextLinks.logError("Failed to capture variable snapshot", e); //$NON-NLS-1$
            MessageDialog.openError(getSite().getShell(), "Снимки переменных", //$NON-NLS-1$
                "Не удалось сохранить снимок переменных: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private void compareSelected()
    {
        List<?> selection = ((IStructuredSelection)viewer.getSelection()).toList();
        if (selection.size() != 2)
        {
            MessageDialog.openInformation(getSite().getShell(), "Снимки переменных", //$NON-NLS-1$
                "Выберите ровно два снимка для сравнения."); //$NON-NLS-1$
            return;
        }

        VariableSnapshot left = (VariableSnapshot)selection.get(0);
        VariableSnapshot right = (VariableSnapshot)selection.get(1);
        CompareUI.openCompareEditor(new VariableSnapshotsCompareInput(left, right));
    }

    private void clearSnapshots()
    {
        if (snapshots.isEmpty())
            return;

        if (!MessageDialog.openConfirm(getSite().getShell(), "Снимки переменных", //$NON-NLS-1$
            "Очистить все сохраненные снимки переменных?")) //$NON-NLS-1$
            return;

        snapshots.clear();
        persistAndRefresh();
    }

    private void saveToFile()
    {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Сохранить снимки переменных"); //$NON-NLS-1$
        dialog.setFileName("variable-snapshots.json"); //$NON-NLS-1$
        dialog.setFilterExtensions(new String[] { "*.json", "*.*" }); //$NON-NLS-1$ //$NON-NLS-2$
        String fileName = dialog.open();
        if (fileName == null)
            return;

        try
        {
            VariableSnapshotStorage.saveTo(Path.of(fileName), snapshots);
        }
        catch (IOException e)
        {
            ContextLinks.logError("Failed to export variable snapshots", e); //$NON-NLS-1$
            MessageDialog.openError(getSite().getShell(), "Снимки переменных", //$NON-NLS-1$
                "Не удалось сохранить снимки: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private void loadFromFile()
    {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN);
        dialog.setText("Загрузить снимки переменных"); //$NON-NLS-1$
        dialog.setFilterExtensions(new String[] { "*.json", "*.*" }); //$NON-NLS-1$ //$NON-NLS-2$
        String fileName = dialog.open();
        if (fileName == null)
            return;

        try
        {
            snapshots.clear();
            snapshots.addAll(VariableSnapshotStorage.loadFrom(Path.of(fileName)));
            persistAndRefresh();
        }
        catch (IOException e)
        {
            ContextLinks.logError("Failed to import variable snapshots", e); //$NON-NLS-1$
            MessageDialog.openError(getSite().getShell(), "Снимки переменных", //$NON-NLS-1$
                "Не удалось загрузить снимки: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private void persistAndRefresh()
    {
        VariableSnapshotStorage.save(snapshots);
        refresh();
    }

    private void refresh()
    {
        if (viewer != null)
            viewer.setInput(snapshots);
        updatePreview();
    }

    private void updatePreview()
    {
        if (jsonPreview == null || jsonPreview.isDisposed())
            return;

        Object selected = ((IStructuredSelection)viewer.getSelection()).getFirstElement();
        jsonPreview.setText(selected instanceof VariableSnapshot ? ((VariableSnapshot)selected).json : ""); //$NON-NLS-1$
    }

    private interface SnapshotTextProvider
    {
        String getText(VariableSnapshot snapshot);
    }
}
