package solutions.crosstech.swingmcp.server.registry;

import jakarta.annotation.PreDestroy;
import solutions.crosstech.swingmcp.server.domain.NoActiveSessionException;
import solutions.crosstech.swingmcp.server.session.AppSession;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds the named {@link AppSession}s managed by the server. Multiple
 * concurrent sessions are supported; one of them is the <em>active</em>
 * session that tools operate on by default. Registering a session under an
 * existing id replaces (and closes) the previous session with that id.
 */
@Component
public class SessionRegistry {

    /** Session id used when the caller does not provide one. */
    public static final String DEFAULT_SESSION_ID = "default";

    private static final Logger LOG = LoggerFactory.getLogger(SessionRegistry.class);

    private final Map<String, AppSession> sessions = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeId = new AtomicReference<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);

    /**
     * Returns the active session.
     *
     * @throws NoActiveSessionException if no session is active
     */
    public AppSession require() {
        String id = activeId.get();
        AppSession session = id == null ? null : sessions.get(id);
        if (session == null) {
            throw new NoActiveSessionException();
        }
        return session;
    }

    /**
     * Returns the session with the given id, or the active session if the id
     * is null or blank.
     *
     * @throws NoActiveSessionException if the session does not exist
     */
    public AppSession require(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return require();
        }
        AppSession session = sessions.get(sessionId);
        if (session == null) {
            throw new NoActiveSessionException();
        }
        return session;
    }

    /** Returns true if at least one session is registered. */
    public boolean hasSession() {
        return !sessions.isEmpty();
    }

    /** Returns true if a session with the given id exists. */
    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /** Returns the id of the active session, or null if none. */
    public String activeSessionId() {
        return activeId.get();
    }

    /** Returns a snapshot of all registered sessions keyed by id. */
    public Map<String, AppSession> sessions() {
        return new LinkedHashMap<>(sessions);
    }

    /**
     * Registers a session under the default id, replacing (and closing) any
     * previous session with that id, and makes it the active session.
     */
    public void set(AppSession session) {
        register(DEFAULT_SESSION_ID, session);
    }

    /**
     * Registers a session under the given id (a unique id is generated when
     * null or blank), replacing and closing any previous session with the same
     * id, and makes it the active session.
     *
     * @return the id the session was registered under
     */
    public String register(String sessionId, AppSession session) {
        String id = sessionId;
        if (id == null || id.isBlank()) {
            do {
                id = "session-" + idCounter.incrementAndGet();
            } while (sessions.containsKey(id));
        }
        AppSession previous = sessions.put(id, session);
        closeQuietly(previous);
        activeId.set(id);
        return id;
    }

    /**
     * Makes the session with the given id the active session.
     *
     * @throws NoActiveSessionException if no session with that id exists
     */
    public void select(String sessionId) {
        if (!sessions.containsKey(sessionId)) {
            throw new NoActiveSessionException();
        }
        activeId.set(sessionId);
    }

    /**
     * Closes and removes the session with the given id (or the active session
     * when the id is null or blank). When the active session is closed, another
     * registered session (if any) becomes active.
     *
     * @return the id of the closed session, or null if nothing was closed
     */
    public String close(String sessionId) {
        String id = (sessionId == null || sessionId.isBlank()) ? activeId.get() : sessionId;
        if (id == null) {
            return null;
        }
        AppSession session = sessions.remove(id);
        if (session == null) {
            return null;
        }
        closeQuietly(session);
        activeId.compareAndSet(id, null);
        if (activeId.get() == null) {
            sessions.keySet().stream().findFirst().ifPresent(activeId::set);
        }
        return id;
    }

    /**
     * Closes every session when the Spring context shuts down (including on a
     * normal stdio MCP server restart), so applications launched via
     * {@code launch_app} are terminated instead of orphaned.
     */
    @PreDestroy
    public void shutdown() {
        if (hasSession()) {
            LOG.info("Server shutting down; closing {} active session(s)", sessions.size());
        }
        clear();
    }

    /**
     * Clears and closes all sessions.
     */
    public void clear() {
        activeId.set(null);
        sessions.keySet().forEach(id -> closeQuietly(sessions.remove(id)));
    }

    private void closeQuietly(AppSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (IOException e) {
                LOG.warn("Failed to close session", e);
            }
        }
    }
}
