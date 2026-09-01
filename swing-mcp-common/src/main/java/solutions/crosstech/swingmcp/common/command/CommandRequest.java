package solutions.crosstech.swingmcp.common.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import java.util.Map;

/**
 * A command sent from the MCP server to the agent running inside the target JVM.
 *
 * <p>The optional {@code token} is a per-session shared secret the agent
 * generates at startup and hands to the paired MCP server (via the port
 * response file). When the agent was started with a token, it rejects any
 * command whose token does not match — closing the "any local process can
 * drive the socket" hole. A {@code null} token means no authentication is
 * required (used by unit tests that talk to a handler directly).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandRequest(
    String requestId,
    CommandType type,
    Map<String, Object> params,
    String token
) {
    /** Convenience constructor for callers that do not use socket authentication. */
    public CommandRequest(String requestId, CommandType type, Map<String, Object> params) {
        this(requestId, type, params, null);
    }
}
