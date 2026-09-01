package solutions.crosstech.swingmcp.server.service;

import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.registry.SessionRegistry;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Reads and writes the system clipboard of the target JVM, enabling
 * copy/paste test flows.
 */
@Service
public class ClipboardService {

    private final SessionRegistry registry;

    public ClipboardService(SessionRegistry registry) {
        this.registry = registry;
    }

    /** Reads the target JVM's system clipboard as text. */
    public Object getClipboard() {
        return registry.require().send(CommandType.GET_CLIPBOARD, Map.of());
    }

    /** Writes text to the target JVM's system clipboard. */
    public Object setClipboard(String text) {
        return registry.require().send(CommandType.SET_CLIPBOARD, Map.of("text", text));
    }
}
