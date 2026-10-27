package ru.xelgo.edt.contextlinks.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ru.xelgo.edt.contextlinks.ui.ContextLinksGitChangedResources.RepositoryChanges;
import ru.xelgo.edt.contextlinks.ui.ContextLinksGitChangedResources.Snapshot;

public class GitChangedFilterTest
{
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void indexesExactPathsAndAncestorsWithoutPrefixCollisions()
    {
        RepositoryChanges changes = new RepositoryChanges(Set.of("src/Catalogs/Items/Module.bsl"));
        assertTrue(changes.contains(""));
        assertTrue(changes.contains("src"));
        assertTrue(changes.contains("src/Catalogs/Items"));
        assertTrue(changes.contains("src/Catalogs/Items/Module.bsl"));
        assertFalse(changes.contains("src/Catalogs/Item"));
        assertFalse(changes.contains("src/Catalogs/Items2"));
        assertFalse(changes.contains("src/Catalogs/Items/Other.bsl"));
    }

    @Test
    public void untrackedDirectoriesCoverDescendantsAndPathsAreImmutable()
    {
        Set<String> source = new java.util.HashSet<>(Set.of("src/NewObject/"));
        RepositoryChanges changes = new RepositoryChanges(source);
        source.clear();
        assertTrue(changes.contains("src/NewObject/Forms/Form/Module.bsl"));
        assertTrue(changes.contains("src\\NewObject"));
        assertFalse(changes.contains("src/NewObject2/Module.bsl"));
        assertTrue(RepositoryChanges.EMPTY.isEmpty());
        assertFalse(RepositoryChanges.EMPTY.contains(""));
    }

    @Test
    public void unknownStatusKeepsObjectsVisibleAndWhitespaceIsSignificant()
    {
        assertTrue(RepositoryChanges.UNKNOWN.contains("any/path"));
        assertFalse(RepositoryChanges.UNKNOWN.isEmpty());
        RepositoryChanges changes = new RepositoryChanges(Set.of("src/name with space "));
        assertTrue(changes.contains("src/name with space "));
        assertFalse(changes.contains("src/name with space"));
    }

    @Test
    public void snapshotHandlesNestedRepositoriesAndProjectsUnderRepositoryRoots()
    {
        IPath root = new Path("C:/repo");
        Map<IPath, RepositoryChanges> roots = new HashMap<>();
        roots.put(root, RepositoryChanges.EMPTY);
        roots.put(root.append("nested"), new RepositoryChanges(Set.of("src/Module.bsl")));
        Snapshot snapshot = new Snapshot(roots);
        roots.clear();
        assertTrue(snapshot.contains(root));
        assertTrue(snapshot.contains(root.append("nested/src")));
        assertFalse(snapshot.contains(root.append("nested/clean")));
        assertFalse(snapshot.contains(new Path("C:/repo2")));
        assertTrue(snapshot.contains(null));
    }

    @Test
    public void windowsWorkTreeCasingDoesNotHideChangedFiles()
    {
        org.junit.Assume.assumeTrue(File.separatorChar == '\\');
        Snapshot snapshot = new Snapshot(Map.of(new Path("C:/Repo"), new RepositoryChanges(Set.of("src/Module.bsl"))));
        assertTrue(snapshot.contains(new Path("c:/repo/src/Module.bsl")));
        assertFalse(snapshot.contains(new Path("D:/Repo/src/Module.bsl")));
    }

    @Test
    public void readsRealStagedUnstagedDeletedAndUntrackedChanges() throws Exception
    {
        File directory = temporary.newFolder();
        try (Git git = Git.init().setDirectory(directory).call())
        {
            for (String name : List.of("modified.bsl", "staged.bsl", "deleted.bsl", "clean.bsl"))
                Files.writeString(directory.toPath().resolve(name), "old");
            Files.writeString(directory.toPath().resolve(".gitignore"), "ignored.bsl\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("Test", "test@example.test")
                .setCommitter("Test", "test@example.test").call();
            Files.writeString(directory.toPath().resolve("modified.bsl"), "modified");
            Files.writeString(directory.toPath().resolve("staged.bsl"), "staged");
            git.add().addFilepattern("staged.bsl").call();
            Files.delete(directory.toPath().resolve("deleted.bsl"));
            Files.writeString(directory.toPath().resolve("new.bsl"), "new");
            Files.writeString(directory.toPath().resolve("ignored.bsl"), "ignored");
            RepositoryChanges changes = ContextLinksGitChangedResources.readChanges(git.getRepository(),
                new NullProgressMonitor());
            for (String name : List.of("modified.bsl", "staged.bsl", "deleted.bsl", "new.bsl"))
                assertTrue(name, changes.contains(name));
            assertFalse(changes.contains("clean.bsl"));
            assertFalse(changes.contains("ignored.bsl"));
        }
    }

    @Test
    public void slowStatusRunsOnceForSharedRepositoryAndNeverFromTreeFiltering() throws Exception
    {
        try (Git git = Git.init().setDirectory(temporary.newFolder()).call())
        {
            Repository repository = git.getRepository();
            AtomicInteger reads = new AtomicInteger();
            Queue<Runnable> ui = new ConcurrentLinkedQueue<>();
            AtomicReference<Snapshot> published = new AtomicReference<>();
            AtomicReference<Thread> worker = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            Thread caller = Thread.currentThread();
            ContextLinksGitChangedRefreshJob job = new ContextLinksGitChangedRefreshJob(monitor -> {
                worker.set(Thread.currentThread());
                return Snapshot.read(List.of(repository, repository, repository), monitor, (repo, progress) -> {
                    reads.incrementAndGet();
                    started.countDown();
                    try
                    {
                        Thread.sleep(1700L); // Longer than the old cache TTL of 1500 ms.
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        throw new OperationCanceledException();
                    }
                    return new RepositoryChanges(Set.of("src/Catalogs/Items/Module.bsl"));
                });
            }, ui::add, published::set, () -> fail("Scan was canceled"));
            try
            {
                job.request(0L);
                assertTrue(started.await(5L, TimeUnit.SECONDS));
                assertNotSame(caller, worker.get());
                assertNull(published.get());
                assertTrue(job.join(10000L, new NullProgressMonitor()));
                drain(ui);
                assertNotNull(published.get());
                assertEquals(1, reads.get());

                ContextLinksGitChangedNavigatorFilter filter = new ContextLinksGitChangedNavigatorFilter();
                filter.setSnapshot(published.get());
                IPath root = Path.fromOSString(repository.getWorkTree().getAbsolutePath());
                IProject project = resource(IProject.class, null, root, null);
                IContainer objectFolder = resource(IContainer.class, project, root.append("src/Catalogs/Items"), null);
                IFile module = resource(IFile.class, project, root.append("src/Catalogs/Items/Module.bsl"), objectFolder);
                IFile metadata = resource(IFile.class, project, root.append("src/Catalogs/Items/Items.mdo"), objectFolder);
                IFile clean = resource(IFile.class, project, root.append("src/Catalogs/Clean/Module.bsl"), null);
                for (int i = 0; i < 10000; i++)
                {
                    assertTrue(filter.select(null, null, module));
                    assertTrue(filter.select(null, null, metadata));
                    assertFalse(filter.select(null, null, clean));
                }
                assertEquals("Filtering must not reread status", 1, reads.get());
                assertNotNull(git.status().call()); // The borrowed EGit repository must stay open.
            }
            finally
            {
                job.close();
            }
        }
    }

    @Test
    public void closingAfterCompletionRejectsAlreadyQueuedUiResult() throws Exception
    {
        Queue<Runnable> ui = new ConcurrentLinkedQueue<>();
        AtomicInteger published = new AtomicInteger();
        ContextLinksGitChangedRefreshJob job = new ContextLinksGitChangedRefreshJob(
            monitor -> new Snapshot(Map.of()), ui::add, snapshot -> published.incrementAndGet(), () -> fail());
        job.request(0L);
        assertTrue(job.join(5000L, new NullProgressMonitor()));
        assertFalse(ui.isEmpty());
        job.close();
        drain(ui);
        assertEquals(0, published.get());
    }

    @Test
    public void changesDuringScanRescheduleSeriallyAndRejectOldResult() throws Exception
    {
        Queue<Runnable> ui = new ConcurrentLinkedQueue<>();
        AtomicInteger scans = new AtomicInteger();
        AtomicInteger inProgress = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger published = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondScan = new CountDownLatch(1);
        ContextLinksGitChangedRefreshJob job = new ContextLinksGitChangedRefreshJob(monitor -> {
            int scan = scans.incrementAndGet();
            maximum.accumulateAndGet(inProgress.incrementAndGet(), Math::max);
            try
            {
                if (scan == 1)
                {
                    started.countDown();
                    await(release);
                }
                else
                    secondScan.countDown();
                return new Snapshot(Map.of());
            }
            finally
            {
                inProgress.decrementAndGet();
            }
        }, ui::add, snapshot -> published.incrementAndGet(), () -> fail());
        try
        {
            job.request(0L);
            assertTrue(started.await(5L, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++)
                job.request(10L);
            release.countDown();
            assertTrue(secondScan.await(5L, TimeUnit.SECONDS));
            assertTrue(job.join(10000L, new NullProgressMonitor()));
            drain(ui);
            assertEquals(2, scans.get());
            assertEquals(1, maximum.get());
            assertEquals(1, published.get());
        }
        finally
        {
            release.countDown();
            job.close();
        }
    }

    @Test
    public void cancellationIsDeliveredWithoutPublishingPartialSnapshot() throws Exception
    {
        Queue<Runnable> ui = new ConcurrentLinkedQueue<>();
        AtomicInteger canceled = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        ContextLinksGitChangedRefreshJob job = new ContextLinksGitChangedRefreshJob(monitor -> {
            started.countDown();
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (!monitor.isCanceled() && System.nanoTime() < limit)
                Thread.yield();
            assertTrue(monitor.isCanceled());
            throw new OperationCanceledException();
        }, ui::add, snapshot -> fail("Canceled result was published"), canceled::incrementAndGet);
        try
        {
            job.request(0L);
            assertTrue(started.await(5L, TimeUnit.SECONDS));
            job.cancel();
            assertTrue(job.join(10000L, new NullProgressMonitor()));
            drain(ui);
            assertEquals(1, canceled.get());
        }
        finally
        {
            job.close();
        }
    }

    @Test
    public void canceledSnapshotDoesNotStartAnyStatusRead() throws Exception
    {
        try (Git git = Git.init().setDirectory(temporary.newFolder()).call())
        {
            NullProgressMonitor monitor = new NullProgressMonitor();
            monitor.setCanceled(true);
            try
            {
                Snapshot.read(List.of(git.getRepository()), monitor, (repo, progress) -> {
                    fail("Canceled status must not start");
                    return RepositoryChanges.EMPTY;
                });
                fail("Expected cancellation");
            }
            catch (OperationCanceledException expected)
            {
                assertTrue(monitor.isCanceled());
            }
        }
    }

    @Test
    public void markersDoNotTriggerRescansButContentAndProjectChangesDo()
    {
        assertFalse(ContextLinksGitChangedFilterManager.hasRelevantChanges(
            delta(IResourceDelta.CHANGED, IResourceDelta.MARKERS)));
        assertTrue(ContextLinksGitChangedFilterManager.hasRelevantChanges(
            delta(IResourceDelta.CHANGED, 0, delta(IResourceDelta.CHANGED, IResourceDelta.CONTENT))));
        assertTrue(ContextLinksGitChangedFilterManager.hasRelevantChanges(delta(IResourceDelta.ADDED, 0)));
        assertTrue(ContextLinksGitChangedFilterManager.hasRelevantChanges(delta(IResourceDelta.REMOVED, 0)));
        assertTrue(ContextLinksGitChangedFilterManager.hasRelevantChanges(
            delta(IResourceDelta.CHANGED, IResourceDelta.OPEN)));
    }

    @Test
    public void realSwtEventLoopKeepsRunningWhileSnapshotIsCalculated() throws Exception
    {
        Display display = new Display();
        Shell shell = new Shell(display); // Hidden test surface; never opened on the desktop.
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger heartbeat = new AtomicInteger();
        AtomicInteger applied = new AtomicInteger();
        TreeViewer viewer = new TreeViewer(shell, SWT.NONE);
        IPath root = new Path("C:/test-repository");
        IProject project = resource(IProject.class, null, root, null);
        IFile[] files = new IFile[5000];
        for (int i = 0; i < files.length; i++)
            files[i] = resource(IFile.class, project, root.append("src/Module" + i + ".bsl"), null);
        viewer.setContentProvider(new ITreeContentProvider()
        {
            @Override public Object[] getElements(Object input) { return files; }
            @Override public Object[] getChildren(Object parent) { return new Object[0]; }
            @Override public Object getParent(Object element) { return null; }
            @Override public boolean hasChildren(Object element) { return false; }
        });
        viewer.setLabelProvider(new LabelProvider());
        viewer.setInput(new Object());
        ContextLinksGitChangedNavigatorFilter filter = new ContextLinksGitChangedNavigatorFilter();
        ContextLinksGitChangedRefreshJob job = new ContextLinksGitChangedRefreshJob(monitor -> {
            await(release);
            return new Snapshot(Map.of(root, new RepositoryChanges(Set.of("src/Module42.bsl"))));
        }, display::asyncExec, snapshot -> {
            filter.setSnapshot(snapshot);
            viewer.addFilter(filter);
            applied.incrementAndGet();
        }, () -> fail("UI scan canceled"));
        Runnable timer = new Runnable()
        {
            @Override
            public void run()
            {
                if (heartbeat.incrementAndGet() >= 5)
                    release.countDown();
                else
                    display.timerExec(10, this);
            }
        };
        try
        {
            job.request(0L);
            display.timerExec(10, timer);
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
            while (applied.get() == 0 && System.nanoTime() < limit)
            {
                if (!display.readAndDispatch())
                    Thread.sleep(1L);
            }
            assertTrue("UI timer did not run during the background calculation", heartbeat.get() >= 5);
            assertEquals(1, applied.get());
            assertEquals(1, viewer.getTree().getItemCount());
            assertEquals(files[42], viewer.getTree().getItem(0).getData());
            viewer.removeFilter(filter);
            assertEquals(5000, viewer.getTree().getItemCount());
        }
        finally
        {
            release.countDown();
            job.close();
            shell.dispose();
            display.dispose();
        }
    }

    private static IResourceDelta delta(int kind, int flags, IResourceDelta... children)
    {
        return (IResourceDelta)Proxy.newProxyInstance(IResourceDelta.class.getClassLoader(),
            new Class<?>[] { IResourceDelta.class }, (proxy, method, args) -> switch (method.getName())
            {
                case "getKind" -> kind;
                case "getFlags" -> flags;
                case "getAffectedChildren" -> children;
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static <T extends IResource> T resource(Class<T> type, IProject project, IPath location, IContainer parent)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
            (proxy, method, args) -> switch (method.getName())
            {
                case "isAccessible" -> true;
                case "getProject" -> project == null ? proxy : project;
                case "getLocation" -> location;
                case "getFileExtension" -> location.getFileExtension();
                case "getParent" -> parent;
                case "getName" -> location.lastSegment();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> location.toString();
                default -> throw new UnsupportedOperationException(method.getName());
            }));
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            assertTrue(latch.await(5L, TimeUnit.SECONDS));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new OperationCanceledException();
        }
    }

    private static void drain(Queue<Runnable> queue)
    {
        Runnable runnable;
        while ((runnable = queue.poll()) != null)
            runnable.run();
    }
}
