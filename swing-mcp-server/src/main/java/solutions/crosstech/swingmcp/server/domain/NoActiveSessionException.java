package solutions.crosstech.swingmcp.server.domain;

/**
 * Thrown when a tool is invoked while no Swing application session is active.
 */
public class NoActiveSessionException extends RuntimeException {

    public NoActiveSessionException() {
        super("No active Swing application session. Use launch_app or attach_to_app first.");
    }
}
