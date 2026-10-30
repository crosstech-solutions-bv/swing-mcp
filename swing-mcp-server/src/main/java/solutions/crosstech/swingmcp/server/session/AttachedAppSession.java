package solutions.crosstech.swingmcp.server.session;

import solutions.crosstech.swingmcp.common.enums.AttachMode;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.domain.SessionInfo;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session for an already-running Swing JVM to which the agent was attached
 * dynamically by PID. Closing this session only disconnects; the target
 * process keeps running.
 */
public final class AttachedAppSession implements AppSession {

    private static final Logger LOG = LoggerFactory.getLogger(AttachedAppSession.class);

    private final long pid;
    private final AgentConnection connection;
    private final SessionInfo info;

    public AttachedAppSession(long pid, AgentConnection connection, int agentPort) {
        this.pid = pid;
        this.connection = connection;
        this.info = new SessionInfo(AttachMode.ATTACHED, pid, agentPort, "Attached to PID " + pid);
    }

    @Override
    public SessionInfo info() {
        return info;
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
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && connection.isOpen();
    }

    /**
     * Disconnects from the agent; the target JVM is left running. The agent is
     * first asked to stop its socket server, so repeated attaches do not leave
     * one listening server (and one port of the range) behind each time.
     */
    @Override
    public void close() throws IOException {
        try {
            if (connection.isOpen()) {
                connection.requestShutdown();
            }
        } catch (RuntimeException e) {
            // Best effort: an older agent or a dead connection must not block the disconnect.
            LOG.debug("Agent SHUTDOWN failed for PID {}: {}", pid, e.getMessage());
        } finally {
            connection.close();
        }
    }
}
