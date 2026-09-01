package solutions.crosstech.swingmcp.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.domain.AgentCommandException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentConnectionTest {

    @Test
    void sendReturnsResultOnSuccess() throws Exception {
        try (FakeAgentServer server = FakeAgentServer.echoing();
             AgentConnection connection = AgentConnection.connect(server.port(), 5000)) {
            Object result = connection.send(CommandType.PING, Map.of());
            assertEquals("PING", result);
            assertTrue(connection.isOpen());
        }
    }

    @Test
    void sendThrowsOnAgentError() throws Exception {
        try (FakeAgentServer server = new FakeAgentServer(req ->
                new CommandResponse(req.requestId(), false, null, "boom"));
             AgentConnection connection = AgentConnection.connect(server.port(), 5000)) {
            AgentCommandException ex = assertThrows(AgentCommandException.class,
                () -> connection.send(CommandType.CLICK, Map.of("uid", "comp-1")));
            assertTrue(ex.getMessage().contains("boom"));
        }
    }

    @Test
    void sendPassesParamsThrough() throws Exception {
        try (FakeAgentServer server = new FakeAgentServer(req ->
                new CommandResponse(req.requestId(), true, req.params(), null));
             AgentConnection connection = AgentConnection.connect(server.port(), 5000)) {
            Object result = connection.send(CommandType.FILL, Map.of("uid", "comp-2", "text", "hello"));
            assertEquals(Map.of("uid", "comp-2", "text", "hello"), result);
        }
    }

    /** Issue #1 (Defect B): a stale response with a foreign request id must be skipped. */
    @Test
    void sendDiscardsStaleResponsesAndReturnsOwnResult() throws Exception {
        try (FakeAgentServer server = FakeAgentServer.multiResponding(req -> List.of(
                new CommandResponse("some-older-request", true, "STALE", null),
                new CommandResponse(req.requestId(), true, "FRESH", null)));
             AgentConnection connection = AgentConnection.connect(server.port(), 5000)) {
            Object result = connection.send(CommandType.PING, Map.of());
            assertEquals("FRESH", result);
        }
    }

    /**
     * Issue #1 (Defect B): after a request times out, its late response must be
     * dropped and the next request must receive its own response — no off-by-N
     * desync, no re-attach needed.
     */
    @Test
    void timedOutRequestDoesNotDesyncSubsequentRequests() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        AtomicReference<String> firstRequestId = new AtomicReference<>();
        try (FakeAgentServer server = FakeAgentServer.multiResponding(req -> {
                if (counter.incrementAndGet() == 1) {
                    firstRequestId.set(req.requestId());
                    return List.of(); // never answer the first request -> client times out
                }
                // Late response of request 1 arrives before the response of request 2.
                return List.of(
                    new CommandResponse(firstRequestId.get(), true, "LATE-ANSWER-1", null),
                    new CommandResponse(req.requestId(), true, "ANSWER-2", null));
            });
             AgentConnection connection = AgentConnection.connect(server.port(), 300)) {
            AgentCommandException ex = assertThrows(AgentCommandException.class,
                () -> connection.send(CommandType.SELECT_MENU_ITEM, Map.of("path", "Help > About")));
            assertTrue(ex.getMessage().contains("timed out"));

            Object result = connection.send(CommandType.LIST_DIALOGS, Map.of());
            assertEquals("ANSWER-2", result);
            assertTrue(connection.isOpen());
        }
    }
}
