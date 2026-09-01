package solutions.crosstech.swingmcp.server.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared JSON serialization for tool result payloads.
 */
final class ToolJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolJson() {
    }

    static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize tool result", e);
        }
    }
}
