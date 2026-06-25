package ru.xelgo.edt.contextlinks.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.Platform;

import ru.xelgo.edt.contextlinks.core.ContextLinks;

/**
 * Persists variable snapshots under workspace metadata.
 */
final class VariableSnapshotStorage
{
    private static final String FILE_NAME = "variable-snapshots.json"; //$NON-NLS-1$

    private VariableSnapshotStorage()
    {
        // Utility class.
    }

    static List<VariableSnapshot> load()
    {
        Path path = storagePath();
        if (!Files.isRegularFile(path))
            return new ArrayList<>();

        try
        {
            return VariableSnapshotJson.parseSnapshots(Files.readString(path, StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            ContextLinks.logError("Failed to load variable snapshots from " + path, e); //$NON-NLS-1$
            return new ArrayList<>();
        }
    }

    static void save(List<VariableSnapshot> snapshots)
    {
        if (snapshots.isEmpty())
        {
            delete();
            return;
        }

        Path path = storagePath();
        try
        {
            Files.createDirectories(path.getParent());
            Files.writeString(path, VariableSnapshotJson.snapshotsArray(snapshots), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            ContextLinks.logError("Failed to save variable snapshots to " + path, e); //$NON-NLS-1$
        }
    }

    static void delete()
    {
        Path path = storagePath();
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException e)
        {
            ContextLinks.logError("Failed to delete variable snapshots from " + path, e); //$NON-NLS-1$
        }
    }

    static List<VariableSnapshot> loadFrom(Path path)
        throws IOException
    {
        return VariableSnapshotJson.parseSnapshots(Files.readString(path, StandardCharsets.UTF_8));
    }

    static void saveTo(Path path, List<VariableSnapshot> snapshots)
        throws IOException
    {
        Files.writeString(path, VariableSnapshotJson.snapshotsArray(snapshots), StandardCharsets.UTF_8);
    }

    private static Path storagePath()
    {
        return Platform.getStateLocation(Platform.getBundle(ContextLinks.PLUGIN_ID)).append(FILE_NAME).toFile().toPath();
    }
}
