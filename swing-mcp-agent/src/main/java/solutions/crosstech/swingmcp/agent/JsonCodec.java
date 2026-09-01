package solutions.crosstech.swingmcp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import solutions.crosstech.swingmcp.common.command.CommandRequest;
import solutions.crosstech.swingmcp.common.command.CommandResponse;

/**
 * Encodes and decodes JSON for the agent's line-protocol socket communication.
 * Uses Jackson for robust JSON handling.
 */
public class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec() {
        this.mapper = new ObjectMapper();
    }

    /**
     * Decodes a JSON line into a {@link CommandRequest}.
     *
     * @param json the JSON string
     * @return the decoded request
     * @throws Exception on parse errors
     */
    public CommandRequest decodeRequest(String json) throws Exception {
        return mapper.readValue(json, CommandRequest.class);
    }

    /**
     * Encodes a {@link CommandResponse} to a JSON string.
     *
     * @param response the response to encode
     * @return the JSON string
     * @throws Exception on serialization errors
     */
    public String encode(CommandResponse response) throws Exception {
        return mapper.writeValueAsString(response);
    }
}
