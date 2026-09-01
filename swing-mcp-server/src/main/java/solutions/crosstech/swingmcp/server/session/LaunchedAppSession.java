package solutions.crosstech.swingmcp.server.session;

import solutions.crosstech.swingmcp.common.enums.AttachMode;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.domain.SessionInfo;
import java.io.IOException;
import java.util.Map;

/**
 * Session for a Swing application launched by the MCP server with the agent
 * preloaded via {@code -javaagent}.
 */
public final class LaunchedAppSession implements AppSession {

    private final Process process;
    private final AgentConnection connection;
    private final SessionInfo info;

    public LaunchedAppSession(Process process, AgentConnection connection, int agentPort, String description) {
        this.process = process;
        this.connection = connection;
        this.info = new SessionInfo(AttachMode.LAUNCHED, process.pid(), agentPort, description);
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
        return process.isAlive() && connection.isOpen();
    }

    /** Terminates the launched process after closing the agent connection. */
    @Override
    public void close() throws IOException {
        try {
            connection.close();
        } finally {
            process.destroy();
        }
    }
}
