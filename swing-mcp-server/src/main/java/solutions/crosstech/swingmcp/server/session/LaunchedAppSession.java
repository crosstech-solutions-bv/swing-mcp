package solutions.crosstech.swingmcp.server.session;

import solutions.crosstech.swingmcp.common.enums.AttachMode;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.domain.SessionInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Session for a Swing application launched by the MCP server with the agent
 * preloaded via {@code -javaagent}.
 */
public final class LaunchedAppSession implements AppSession {

    /** How long a terminated process tree gets to exit before it is killed forcibly. */
    static final long GRACE_PERIOD_MS = 5000;

    private final Process process;
    private final AgentConnection connection;
    private final SessionInfo info;
    private final Path outputFile;

    public LaunchedAppSession(Process process, AgentConnection connection, int agentPort, String description) {
        this(process, connection, agentPort, description, null);
    }

    /**
     * @param outputFile the file the process's stdout and stderr are redirected to, or null
     */
    public LaunchedAppSession(Process process, AgentConnection connection, int agentPort, String description,
                              Path outputFile) {
        this.process = process;
        this.connection = connection;
        this.info = new SessionInfo(AttachMode.LAUNCHED, process.pid(), agentPort, description);
        this.outputFile = outputFile;
    }

    @Override
    public SessionInfo info() {
        return info;
    }

    @Override
    public Optional<Path> outputFile() {
        return Optional.ofNullable(outputFile);
    }

    @Override
    public Object send(CommandType type, Map<String, Object> params) {
        return connection.send(type, params);
    }

    @Override
    public Object send(CommandType type, Map<String, Object> params, long minReadTimeoutMs) {
        return connection.send(type, params, minReadTimeoutMs);
    }

    @Override
    public boolean isAlive() {
        return process.isAlive() && connection.isOpen();
    }

    /**
     * Terminates the launched process (and anything it started) after closing
     * the agent connection, then deletes the captured output log. Requests
     * graceful termination first, then forcibly kills what has not exited
     * within a short grace period — so a frozen or modal Swing app is actually
     * stopped rather than left orphaned.
     */
    @Override
    public void close() throws IOException {
        try {
            connection.close();
        } finally {
            terminate(process.toHandle());
            if (outputFile != null) {
                try {
                    Files.deleteIfExists(outputFile);
                } catch (IOException e) {
                    // Still held open (Windows); deleteOnExit removes it later.
                }
            }
        }
    }

    /**
     * Destroys a process tree: the descendants first, then the process itself,
     * so the real JVM behind a wrapper ({@code xvfb-run java ...},
     * {@code timeout 60 java ...}) is stopped too. Whatever is still alive after
     * {@link #GRACE_PERIOD_MS} is killed forcibly.
     */
    public static void terminate(ProcessHandle root) {
        // Collected up front: once the root is gone its children are reparented
        // and no longer show up as its descendants.
        List<ProcessHandle> tree = new ArrayList<>(root.descendants().toList());
        tree.add(root);
        tree.forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GRACE_PERIOD_MS);
        try {
            for (ProcessHandle handle : tree) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    try {
                        handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
                    } catch (TimeoutException | ExecutionException e) {
                        // still alive; killed below
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }
}
