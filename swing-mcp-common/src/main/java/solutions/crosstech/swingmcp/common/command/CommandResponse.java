package solutions.crosstech.swingmcp.common.command;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A response from the agent running inside the target JVM back to the MCP server.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandResponse(
    String requestId,
    boolean success,
    Object result,
    String error
) {}
