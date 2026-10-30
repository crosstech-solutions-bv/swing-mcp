package solutions.crosstech.swingmcp.server.session;

import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.domain.SessionInfo;
import java.io.Closeable;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

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

    /**
     * Sends a command that may legitimately run longer than the configured
     * tool timeout, waiting at least {@code minReadTimeoutMs} for its response.
     */
    Object send(CommandType type, Map<String, Object> params, long minReadTimeoutMs);

    /** Returns true if the underlying target process/connection is still alive. */
    boolean isAlive();

    /**
     * The file capturing the target's stdout and stderr, when the server owns
     * the process. Empty for attached sessions, whose output goes elsewhere.
     */
    default Optional<Path> outputFile() {
        return Optional.empty();
    }
}
