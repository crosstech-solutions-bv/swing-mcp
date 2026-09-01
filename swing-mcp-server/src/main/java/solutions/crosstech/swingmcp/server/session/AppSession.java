package solutions.crosstech.swingmcp.server.session;

import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.domain.SessionInfo;
import java.io.Closeable;
import java.util.Map;

/**
 * A connection to a target Swing application with the swing-mcp agent loaded.
 * Exactly two implementations exist: one for applications launched by the MCP
 * server and one for already-running JVMs attached to by PID.
 */
public sealed interface AppSession extends Closeable permits LaunchedAppSession, AttachedAppSession {

    /** Describes this session (mode, pid, agent port). */
    SessionInfo info();

    /**
     * Sends a command to the agent and returns the decoded result payload.
     *
     * @param type   the command type
     * @param params the command parameters (may be empty)
     * @return the result payload from the agent
     */
    Object send(CommandType type, Map<String, Object> params);

    /** Returns true if the underlying target process/connection is still alive. */
    boolean isAlive();
}
