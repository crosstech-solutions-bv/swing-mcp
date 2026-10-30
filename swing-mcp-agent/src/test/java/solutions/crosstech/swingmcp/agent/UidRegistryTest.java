package solutions.crosstech.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.swing.JButton;
import org.junit.jupiter.api.Test;

/**
 * The uid registry holds components weakly, so it cannot keep a disposed
 * window alive, and stays consistent when commands register uids concurrently.
 */
class UidRegistryTest {

    private final ComponentScanner scanner = new ComponentScanner();

    @Test
    void registryDoesNotPinComponentsAndForgetsCollectedOnes() throws Exception {
        List<String> uids = new ArrayList<>();
        WeakReference<Component> ref = registerTransient(uids);
        assertEquals(1, scanner.registeredUidCount());

        long deadline = System.currentTimeMillis() + 10_000;
        while (ref.get() != null && System.currentTimeMillis() < deadline) {
            System.gc();
            byte[][] pressure = new byte[64][];
            for (int i = 0; i < pressure.length; i++) {
                pressure[i] = new byte[64 * 1024];
            }
            Thread.sleep(20);
        }
        assertNull(ref.get(), "the registry kept a component reachable");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> scanner.resolveUid(uids.get(0)));
        assertTrue(e.getMessage().contains("Unknown component UID"), e.getMessage());
        waitUntilPurged();
    }

    /** In its own frame, so no local variable keeps the button reachable. */
    private WeakReference<Component> registerTransient(List<String> uids) {
        JButton button = new JButton("transient");
        String uid = scanner.assignUid(button);
        assertSame(button, scanner.resolveUid(uid));
        assertEquals(uid, scanner.assignUid(button), "a component keeps its uid");
        uids.add(uid);
        return new WeakReference<>(button);
    }

    private void waitUntilPurged() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (scanner.registeredUidCount() != 0 && System.currentTimeMillis() < deadline) {
            System.gc();
            Thread.sleep(20);
        }
        assertEquals(0, scanner.registeredUidCount(), "the uid of a collected component was not dropped");
    }

    @Test
    void unknownUidIsReported() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> scanner.resolveUid("comp-999999"));
        assertTrue(e.getMessage().contains("Unknown component UID"), e.getMessage());
    }

    @Test
    void concurrentRegistrationGivesEachComponentOneStableUid() throws Exception {
        List<Component> components = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            components.add(new JButton("b" + i));
        }
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<String>>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    List<String> uids = new ArrayList<>();
                    for (Component c : components) {
                        uids.add(scanner.assignUid(c));
                    }
                    return uids;
                }));
            }
            List<String> first = results.get(0).get();
            for (Future<List<String>> f : results) {
                assertEquals(first, f.get(), "a component got different uids on different threads");
            }
            Set<String> distinct = new HashSet<>(first);
            assertEquals(components.size(), distinct.size(), "two components share a uid");
            for (int i = 0; i < components.size(); i++) {
                assertSame(components.get(i), scanner.resolveUid(first.get(i)));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
