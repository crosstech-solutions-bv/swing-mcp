package solutions.crosstech.swingmcp.common.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import java.util.Map;

/**
 * A command sent from the MCP server to the agent running inside the target JVM.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandRequest(
    String requestId,
    CommandType type,
    Map<String, Object> params
) {}
