package solutions.crosstech.swingmcp.server.session;

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

    private final Socket socket;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long readTimeoutMs;

    /** Request ids that timed out; their late responses are silently dropped. */
    private final Set<String> abandonedRequests = new LinkedHashSet<>();

    private AgentConnection(Socket socket, long readTimeoutMs) throws IOException {
        this.socket = socket;
        this.readTimeoutMs = readTimeoutMs;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
    }

    /**
     * Opens a connection to the agent on localhost.
     *
     * @param port          the agent's listening port
     * @param readTimeoutMs socket read timeout for command round-trips
     */
    public static AgentConnection connect(int port, long readTimeoutMs) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout((int) readTimeoutMs);
        return new AgentConnection(socket, readTimeoutMs);
    }

    /**
     * Sends a command and waits for <em>its own</em> response, identified by
     * request id. Responses belonging to other (abandoned) requests are
     * discarded.
     *
     * @throws AgentCommandException if the agent reports an error or the transport fails
     */
    public synchronized Object send(CommandType type, Map<String, Object> params) {
        String requestId = UUID.randomUUID().toString();
        try {
            String json = mapper.writeValueAsString(new CommandRequest(requestId, type, params));
            writer.println(json);
            long deadline = System.currentTimeMillis() + readTimeoutMs;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw timeout(type, requestId);
                }
                socket.setSoTimeout((int) remaining);
                String line;
                try {
                    line = reader.readLine();
                } catch (SocketTimeoutException e) {
                    throw timeout(type, requestId);
                }
                if (line == null) {
                    throw new AgentCommandException("Agent connection closed while waiting for response to " + type);
                }
                CommandResponse response = mapper.readValue(line, CommandResponse.class);
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

    private AgentCommandException timeout(CommandType type, String requestId) {
        rememberAbandoned(requestId);
        return new AgentCommandException("Agent command " + type + " timed out after " + readTimeoutMs
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
