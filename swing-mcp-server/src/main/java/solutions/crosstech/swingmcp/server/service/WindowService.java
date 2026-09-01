package solutions.crosstech.swingmcp.server.service;

import solutions.crosstech.swingmcp.common.enums.CommandType;
import solutions.crosstech.swingmcp.server.registry.SessionRegistry;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Window management operations on the target application.
 */
@Service
public class WindowService {

    private final SessionRegistry registry;

    public WindowService(SessionRegistry registry) {
        this.registry = registry;
    }

    /** Lists all visible windows in the target JVM. */
    public Object listWindows() {
        return registry.require().send(CommandType.LIST_WINDOWS, Map.of());
    }

    /** Selects the active window by index. */
    public Object selectWindow(int index) {
        return registry.require().send(CommandType.SELECT_WINDOW, Map.of("index", index));
    }

    /** Resizes the active window. */
    public Object resizeWindow(Integer width, Integer height) {
        Map<String, Object> params = new HashMap<>();
        if (width != null) {
            params.put("width", width);
        }
        if (height != null) {
            params.put("height", height);
        }
        return registry.require().send(CommandType.RESIZE_WINDOW, params);
    }

    /** Moves the active window to a screen position. */
    public Object moveWindow(Integer x, Integer y) {
        Map<String, Object> params = new HashMap<>();
        if (x != null) {
            params.put("x", x);
        }
        if (y != null) {
            params.put("y", y);
        }
        return registry.require().send(CommandType.MOVE_WINDOW, params);
    }

    /** Changes the extended state of the active frame: MAXIMIZED, MINIMIZED, or NORMAL. */
    public Object setWindowState(String state) {
        return registry.require().send(CommandType.SET_WINDOW_STATE, Map.of("state", state));
    }

    /** Closes the active window. */
    public Object closeWindow() {
        return registry.require().send(CommandType.CLOSE_WINDOW, Map.of());
    }
}
