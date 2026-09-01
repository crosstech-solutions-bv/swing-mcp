package solutions.crosstech.swingmcp.server.domain;

/**
 * Thrown when the in-process agent reports a command failure or the
 * command round-trip fails at the transport level.
 */
public class AgentCommandException extends RuntimeException {

    public AgentCommandException(String message) {
        super(message);
    }

    public AgentCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
