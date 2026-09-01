package solutions.crosstech.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import solutions.crosstech.swingmcp.common.command.CommandRequest;
import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies the agent-side security controls added in 1.2.0: the socket auth
 * token and the {@code evaluate_java} gate are both enforced in the agent,
 * not only in the server — so neither can be bypassed by talking to the
 * socket directly. Headless-safe (PING and gating need no display).
 */
class CommandHandlerAuthTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonCodec codec = new JsonCodec();

    private CommandResponse send(CommandHandler handler, CommandRequest request) throws Exception {
        String response = handler.handle(mapper.writeValueAsString(request));
        return mapper.readValue(response, CommandResponse.class);
    }

    @Test
    void acceptsCommandWithCorrectToken() throws Exception {
        CommandHandler handler = new CommandHandler(codec, false, "secret-token");
        CommandResponse r = send(handler, new CommandRequest(
            UUID.randomUUID().toString(), CommandType.PING, Map.of(), "secret-token"));
        assertTrue(r.success());
        assertEquals("pong", r.result());
    }

    @Test
    void rejectsCommandWithWrongToken() throws Exception {
        CommandHandler handler = new CommandHandler(codec, false, "secret-token");
        CommandResponse r = send(handler, new CommandRequest(
            UUID.randomUUID().toString(), CommandType.PING, Map.of(), "wrong"));
        assertFalse(r.success());
        assertNotNull(r.error());
        assertTrue(r.error().toLowerCase().contains("unauthorized"));
    }

    @Test
    void rejectsCommandWithMissingToken() throws Exception {
        CommandHandler handler = new CommandHandler(codec, false, "secret-token");
        CommandResponse r = send(handler, new CommandRequest(
            UUID.randomUUID().toString(), CommandType.PING, Map.of()));
        assertFalse(r.success());
    }

    @Test
    void allowsAnyCommandWhenNoTokenConfigured() throws Exception {
        CommandHandler handler = new CommandHandler(codec); // no token
        CommandResponse r = send(handler, new CommandRequest(
            UUID.randomUUID().toString(), CommandType.PING, Map.of()));
        assertTrue(r.success());
    }

    @Test
    void preDispatchGateRejectsBadTokenAndAllowsGoodToken() throws Exception {
        CommandHandler handler = new CommandHandler(codec, false, "secret-token");
        String bad = mapper.writeValueAsString(new CommandRequest("r1", CommandType.PING, Map.of(), "wrong"));
        String good = mapper.writeValueAsString(new CommandRequest("r2", CommandType.PING, Map.of(), "secret-token"));
        String rejection = handler.rejectIfUnauthorized(bad);
        assertNotNull(rejection, "bad token must produce a rejection line");
        CommandResponse r = mapper.readValue(rejection, CommandResponse.class);
        assertFalse(r.success());
        assertEquals("r1", r.requestId());
        assertEquals(null, handler.rejectIfUnauthorized(good), "good token must pass the gate");
    }

    @Test
    void tokenIsRedactedBeforeLogging() {
        String line = "{\"requestId\":\"x\",\"type\":\"PING\",\"params\":{},\"token\":\"super-secret-uuid\"}";
        String redacted = CommandHandler.redactToken(line);
        assertFalse(redacted.contains("super-secret-uuid"), "token value must not survive redaction");
        assertTrue(redacted.contains("<redacted>"));
        assertTrue(redacted.contains("\"requestId\":\"x\""), "other fields must be preserved");
    }

    @Test
    void evaluateJavaRejectedWhenDisabled() throws Exception {
        CommandHandler handler = new CommandHandler(codec, false, null);
        CommandResponse r = send(handler, new CommandRequest(
            UUID.randomUUID().toString(), CommandType.EVALUATE_JAVA, Map.of("code", "1+1")));
        assertFalse(r.success());
        assertTrue(r.error().toLowerCase().contains("disabled"));
    }
}
