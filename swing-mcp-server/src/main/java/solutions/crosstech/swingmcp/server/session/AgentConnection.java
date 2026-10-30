package solutions.crosstech.swingmcp.server.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import solutions.crosstech.swingmcp.common.command.CommandRequest;
import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.domain.AgentCommandException;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON line-protocol client for the agent's localhost socket server.
 * Requests are serialized as single-line JSON and answered with a single
 * JSON response line. Calls are serialized; one command is in flight at a time.
 *
 * <p>Responses are correlated to requests by {@code requestId}: stale
 * responses (from earlier calls that timed out, or written out of order by
 * the agent's concurrent command loop) are recognized and discarded instead
 * of being misattributed to the current request. This makes read timeouts
 * recoverable in-session — no off-by-N protocol desync (Issue #1, Defect B).</p>
 */
public class AgentConnection implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(AgentConnection.class);
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int MAX_ABANDONED_TRACKED = 100;
    private static final long SHUTDOWN_TIMEOUT_MS = 2000;

    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long readTimeoutMs;
    private final String token;

    /** Request ids that timed out; their late responses are silently dropped. */
    private final Set<String> abandonedRequests = new LinkedHashSet<>();

    /**
     * Set when a read timed out, possibly partway through a response line: the
     * first line read afterwards may be that line's tail, which is skipped if
     * it does not parse instead of failing the next command.
     */
    private boolean partialLineLost;

    private AgentConnection(Socket socket, long readTimeoutMs, String token) throws IOException {
        this.socket = socket;
        this.readTimeoutMs = readTimeoutMs;
        this.token = token;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
    }

    /**
     * Opens an unauthenticated connection to the agent on localhost (used by tests).
     *
     * @param port          the agent's listening port
     * @param readTimeoutMs socket read timeout for command round-trips
     */
    public static AgentConnection connect(int port, long readTimeoutMs) throws IOException {
        return connect(port, readTimeoutMs, null);
    }

    /**
     * Opens a connection to the agent on localhost, authenticating every
     * command with the per-session {@code token} the agent generated.
     *
     * @param port          the agent's listening port
     * @param readTimeoutMs socket read timeout for command round-trips
     * @param token         per-session auth token, or null for no authentication
     */
    public static AgentConnection connect(int port, long readTimeoutMs, String token) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout((int) Math.min(readTimeoutMs, Integer.MAX_VALUE));
        return new AgentConnection(socket, readTimeoutMs, token);
    }

    /**
     * Sends a command and waits for <em>its own</em> response, identified by
     * request id. Responses belonging to other (abandoned) requests are
     * discarded.
     *
     * @throws AgentCommandException if the agent reports an error or the transport fails
     */
    public Object send(CommandType type, Map<String, Object> params) {
        return send(type, params, 0);
    }

    /**
     * Like {@link #send(CommandType, Map)}, but waits at least
     * {@code minReadTimeoutMs} for the response — for commands such as
     * {@code WAIT_FOR} that legitimately run longer than the configured
     * read timeout.
     */
    public Object send(CommandType type, Map<String, Object> params, long minReadTimeoutMs) {
        return sendWithin(type, params, Math.max(readTimeoutMs, minReadTimeoutMs));
    }

    /**
     * Asks the agent to stop its socket server (the target JVM keeps running),
     * waiting only briefly: an agent too old to know {@code SHUTDOWN} answers
     * with an uncorrelated parse error, which must not stall a disconnect.
     */
    public void requestShutdown() {
        sendWithin(CommandType.SHUTDOWN, Map.of(), Math.min(readTimeoutMs, SHUTDOWN_TIMEOUT_MS));
    }

    private synchronized Object sendWithin(CommandType type, Map<String, Object> params, long timeoutMs) {
        String requestId = UUID.randomUUID().toString();
        try {
            String json = mapper.writeValueAsString(new CommandRequest(requestId, type, params, token));
            writer.println(json);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw timeout(type, requestId, timeoutMs);
                }
                socket.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
                String line;
                try {
                    line = reader.readLine();
                } catch (SocketTimeoutException e) {
                    // readLine may have consumed part of a line, which is lost;
                    // the next read then starts with its remainder.
                    partialLineLost = true;
                    throw timeout(type, requestId, timeoutMs);
                }
                if (line == null) {
                    throw new AgentCommandException("The target application has exited or closed the agent"
                        + " connection while handling " + type + ". This session is no longer usable —"
                        + " relaunch with launch_app or reattach with attach_to_app.");
                }
                CommandResponse response;
                try {
                    response = mapper.readValue(line, CommandResponse.class);
                } catch (JsonProcessingException e) {
                    if (!partialLineLost) {
                        throw e;
                    }
                    partialLineLost = false;
                    LOG.debug("Discarded the remainder of a response line cut off by an earlier timeout");
                    continue;
                }
                partialLineLost = false;
                if (!requestId.equals(response.requestId())) {
                    if (abandonedRequests.remove(response.requestId())) {
                        LOG.debug("Discarded late response of abandoned request {} while waiting for {}",
                            response.requestId(), requestId);
                    } else {
                        LOG.warn("Discarded unexpected response with request id {} while waiting for {}",
                            response.requestId(), requestId);
                    }
                    continue;
                }
                if (!response.success()) {
                    throw new AgentCommandException("Agent command " + type + " failed: " + response.error());
                }
                return response.result();
            }
        } catch (IOException e) {
            throw new AgentCommandException("Agent command " + type + " transport failure: " + e.getMessage(), e);
        }
    }

    private AgentCommandException timeout(CommandType type, String requestId, long timeoutMs) {
        rememberAbandoned(requestId);
        return new AgentCommandException("Agent command " + type + " timed out after " + timeoutMs
            + " ms. If the last action opened a modal dialog, use list_dialogs and handle_dialog to dismiss it; "
            + "the session remains usable.");
    }

    private void rememberAbandoned(String requestId) {
        abandonedRequests.add(requestId);
        if (abandonedRequests.size() > MAX_ABANDONED_TRACKED) {
            var it = abandonedRequests.iterator();
            it.next();
            it.remove();
        }
    }

    /** Returns true while the socket is open. */
    public boolean isOpen() {
        return !socket.isClosed() && socket.isConnected();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
