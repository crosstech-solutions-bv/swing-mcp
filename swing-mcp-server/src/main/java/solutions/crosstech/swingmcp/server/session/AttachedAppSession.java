package solutions.crosstech.swingmcp.server.session;

import solutions.crosstech.swingmcp.common.enums.AttachMode;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.domain.SessionInfo;
import java.io.IOException;
import java.util.Map;

/**
 * Session for an already-running Swing JVM to which the agent was attached
 * dynamically by PID. Closing this session only disconnects; the target
 * process keeps running.
 */
public final class AttachedAppSession implements AppSession {

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
    public boolean isAlive() {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && connection.isOpen();
    }

    /** Disconnects from the agent; the target JVM is left running. */
    @Override
    public void close() throws IOException {
        connection.close();
    }
}
