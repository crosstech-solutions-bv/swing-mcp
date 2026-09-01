package solutions.crosstech.swingmcp.server.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import solutions.crosstech.swingmcp.common.enums.AttachMode;
import solutions.crosstech.swingmcp.server.domain.NoActiveSessionException;
import solutions.crosstech.swingmcp.server.session.AgentConnection;
import solutions.crosstech.swingmcp.server.session.AttachedAppSession;
import solutions.crosstech.swingmcp.server.session.FakeAgentServer;
import org.junit.jupiter.api.Test;

class SessionRegistryTest {

    @Test
    void requireThrowsWhenEmpty() {
        SessionRegistry registry = new SessionRegistry();
        assertFalse(registry.hasSession());
        assertThrows(NoActiveSessionException.class, registry::require);
    }

    @Test
    void setAndClearManageSessionLifecycle() throws Exception {
        try (FakeAgentServer server = FakeAgentServer.echoing()) {
            AgentConnection connection = AgentConnection.connect(server.port(), 5000);
            AttachedAppSession session = new AttachedAppSession(ProcessHandle.current().pid(), connection, server.port());

            SessionRegistry registry = new SessionRegistry();
            registry.set(session);
            assertTrue(registry.hasSession());
            assertEquals(AttachMode.ATTACHED, registry.require().info().mode());

            registry.clear();
            assertFalse(registry.hasSession());
            assertFalse(session.isAlive());
        }
    }

    @Test
    void setClosesPreviousSession() throws Exception {
        try (FakeAgentServer server = FakeAgentServer.echoing()) {
            AgentConnection first = AgentConnection.connect(server.port(), 5000);
            AttachedAppSession firstSession = new AttachedAppSession(ProcessHandle.current().pid(), first, server.port());
            AgentConnection second = AgentConnection.connect(server.port(), 5000);
            AttachedAppSession secondSession = new AttachedAppSession(ProcessHandle.current().pid(), second, server.port());

            SessionRegistry registry = new SessionRegistry();
            registry.set(firstSession);
            registry.set(secondSession);

            assertFalse(firstSession.isAlive());
            assertTrue(secondSession.isAlive());
            registry.clear();
        }
    }

    @Test
    void supportsMultipleNamedSessions() throws Exception {
        try (FakeAgentServer server = FakeAgentServer.echoing()) {
            AgentConnection first = AgentConnection.connect(server.port(), 5000);
            AttachedAppSession firstSession = new AttachedAppSession(ProcessHandle.current().pid(), first, server.port());
            AgentConnection second = AgentConnection.connect(server.port(), 5000);
            AttachedAppSession secondSession = new AttachedAppSession(ProcessHandle.current().pid(), second, server.port());

            SessionRegistry registry = new SessionRegistry();
            String idA = registry.register("app-a", firstSession);
            String idB = registry.register("app-b", secondSession);
            assertEquals("app-a", idA);
            assertEquals("app-b", idB);

            // Both sessions stay alive; the most recently registered is active.
            assertTrue(firstSession.isAlive());
            assertTrue(secondSession.isAlive());
            assertEquals("app-b", registry.activeSessionId());
            assertEquals(2, registry.sessions().size());

            registry.select("app-a");
            assertEquals("app-a", registry.activeSessionId());
            assertEquals(firstSession, registry.require());
            assertEquals(secondSession, registry.require("app-b"));

            // Closing the active session promotes another one.
            assertEquals("app-a", registry.close(null));
            assertFalse(firstSession.isAlive());
            assertTrue(secondSession.isAlive());
            assertEquals("app-b", registry.activeSessionId());

            registry.clear();
            assertFalse(registry.hasSession());
            assertFalse(secondSession.isAlive());
        }
    }

    @Test
    void registerGeneratesIdWhenMissing() throws Exception {
        try (FakeAgentServer server = FakeAgentServer.echoing()) {
            AgentConnection connection = AgentConnection.connect(server.port(), 5000);
            AttachedAppSession session = new AttachedAppSession(ProcessHandle.current().pid(), connection, server.port());

            SessionRegistry registry = new SessionRegistry();
            String id = registry.register(null, session);
            assertTrue(id.startsWith("session-"));
            assertEquals(id, registry.activeSessionId());
            registry.clear();
        }
    }

    @Test
    void selectUnknownSessionThrows() {
        SessionRegistry registry = new SessionRegistry();
        assertThrows(NoActiveSessionException.class, () -> registry.select("missing"));
    }
}
