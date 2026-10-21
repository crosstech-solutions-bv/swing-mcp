package solutions.crosstech.swingmcp.agent.toolkit;

import solutions.crosstech.swingmcp.common.spi.UiToolkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared threading and modal-dialog policy for {@link UiToolkit}
 * implementations.
 *
 * <p>Both supported toolkits have the same shape of problem: a single UI thread
 * that must not be blocked, and blocking modal dialogs that take over the event
 * pump so an action posted to that thread may never return. The policy is
 * identical for both, only the marshalling primitive differs, so it lives here
 * once.</p>
 *
 * <p>Queries are marshalled and awaited with a hard timeout. Actions are
 * fire-and-poll: posted, awaited briefly, and if they have not completed the
 * open dialogs are described back to the caller so the model can dismiss them
 * instead of the agent hanging.</p>
 */
public abstract class AbstractUiToolkit implements UiToolkit {

    private static final Logger LOG = Logger.getLogger(AbstractUiToolkit.class.getName());

    /** How long an action may run before a pending result is returned instead. */
    protected static final long ACTION_WAIT_MS = 500;
    /** Hard cap for UI-thread round trips; prevents a wedged UI hanging the agent. */
    protected static final long UI_TIMEOUT_MS = 20_000;
    /** Bounded wait when collecting dialog info for a pending result. */
    protected static final long DIALOG_PROBE_MS = 2_000;

    /**
     * Posts {@code work} to the UI thread without waiting. The one primitive a
     * concrete toolkit must supply; everything else here is built on it.
     */
    @Override
    public abstract void postToUiThread(Runnable work);

    @Override
    public abstract boolean isUiThread();

    @Override
    public <T> T invokeOnUiThread(Callable<T> work, long timeoutMs) throws Exception {
        if (isUiThread()) {
            return work.call();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        postToUiThread(() -> {
            try {
                result.set(work.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("The " + uiThreadName() + " did not respond within "
                + timeoutMs + " ms. The application UI may be blocked or busy.");
        }
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    /** Convenience overload using the default hard timeout. */
    protected <T> T onUi(Callable<T> work) throws Exception {
        return invokeOnUiThread(work, UI_TIMEOUT_MS);
    }

    /**
     * Runs an action on the UI thread, waiting only briefly. If it has not
     * finished — almost always because it opened a modal dialog — a pending
     * result describing the open dialogs is returned instead of blocking.
     */
    protected Object action(Callable<?> work) throws Exception {
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        postToUiThread(() -> {
            try {
                result.set(work.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        return awaitOrPending(latch, result, error);
    }

    /**
     * Runs an action on a worker thread, for actions that drive the UI thread
     * internally (context menus) and must not themselves sit on it.
     */
    protected Object actionOffUi(Callable<?> work) throws Exception {
        return actionOffUi(work, ACTION_WAIT_MS);
    }

    /**
     * As {@link #actionOffUi(Callable)}, waiting up to {@code waitMs} before
     * answering pending — for actions that legitimately take longer, such as
     * typing a long text one keystroke at a time.
     */
    protected Object actionOffUi(Callable<?> work, long waitMs) throws Exception {
        AtomicReference<Object> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                result.set(work.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        }, id() + "-mcp-action");
        worker.setDaemon(true);
        worker.start();
        return awaitOrPending(latch, result, error, waitMs);
    }

    private Object awaitOrPending(CountDownLatch latch,
                                  AtomicReference<Object> result,
                                  AtomicReference<Exception> error) throws Exception {
        return awaitOrPending(latch, result, error, ACTION_WAIT_MS);
    }

    private Object awaitOrPending(CountDownLatch latch,
                                  AtomicReference<Object> result,
                                  AtomicReference<Exception> error,
                                  long waitMs) throws Exception {
        if (latch.await(waitMs, TimeUnit.MILLISECONDS)) {
            if (error.get() != null) {
                throw error.get();
            }
            return result.get();
        }
        return pendingResult();
    }

    /** Builds the result returned when an action did not complete in time. */
    protected Map<String, Object> pendingResult() {
        List<Map<String, Object>> dialogs = probeDialogs();
        boolean modalOpen = dialogs.stream().anyMatch(d -> Boolean.TRUE.equals(d.get("modal")));
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("status", "pending");
        pending.put("modalDialogOpen", modalOpen);
        pending.put("dialogs", dialogs);
        pending.put("note", "The action has not completed yet"
            + (modalOpen ? " because it opened a modal dialog" : "")
            + ". Use list_dialogs and handle_dialog to inspect and dismiss any open dialog; "
            + "the action will finish once the dialog is closed.");
        return pending;
    }

    /** Collects dialog info with a bounded wait; empty on failure. */
    private List<Map<String, Object>> probeDialogs() {
        AtomicReference<List<Map<String, Object>>> ref = new AtomicReference<>(List.of());
        CountDownLatch latch = new CountDownLatch(1);
        postToUiThread(() -> {
            try {
                ref.set(listDialogsOnUiThread());
            } catch (Exception e) {
                LOG.log(Level.FINE, "Failed to probe dialogs", e);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(DIALOG_PROBE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ref.get();
    }

    /**
     * Lists open dialogs. Called on the UI thread — implementations must not
     * marshal again from here or the probe will deadlock against itself.
     */
    protected abstract List<Map<String, Object>> listDialogsOnUiThread();

    /** Human-readable name of the UI thread, used in timeout messages. */
    protected abstract String uiThreadName();

    /** Helper for commands a toolkit genuinely cannot serve. */
    protected UnsupportedByToolkitException unsupported(String command, String why) {
        return new UnsupportedByToolkitException(id(), command, why);
    }
}
