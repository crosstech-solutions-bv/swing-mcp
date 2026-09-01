package solutions.crosstech.swingmcp.server.tools;

import solutions.crosstech.swingmcp.server.service.WindowService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for window management on the target application.
 */
@Component
public class WindowTools {

    private final WindowService windowService;

    public WindowTools(WindowService windowService) {
        this.windowService = windowService;
    }

    @Tool(name = "list_windows", description = "List all visible windows in the target JVM with their index, title, and bounds.")
    public String listWindows() {
        return ToolJson.toJson(windowService.listWindows());
    }

    @Tool(name = "select_window", description = "Select the active window by index (from list_windows) and bring it to front.")
    public String selectWindow(
            @ToolParam(description = "Window index from list_windows") int index) {
        return ToolJson.toJson(windowService.selectWindow(index));
    }

    @Tool(name = "resize_window", description = "Resize the active window to the given width and height in pixels.")
    public String resizeWindow(
            @ToolParam(description = "New width in pixels", required = false) Integer width,
            @ToolParam(description = "New height in pixels", required = false) Integer height) {
        return ToolJson.toJson(windowService.resizeWindow(width, height));
    }

    @Tool(name = "move_window", description = "Move the active window to the given screen position in pixels.")
    public String moveWindow(
            @ToolParam(description = "New x position in pixels", required = false) Integer x,
            @ToolParam(description = "New y position in pixels", required = false) Integer y) {
        return ToolJson.toJson(windowService.moveWindow(x, y));
    }

    @Tool(name = "maximize_window", description = "Maximize the active frame window.")
    public String maximizeWindow() {
        return ToolJson.toJson(windowService.setWindowState("MAXIMIZED"));
    }

    @Tool(name = "minimize_window", description = "Minimize (iconify) the active frame window.")
    public String minimizeWindow() {
        return ToolJson.toJson(windowService.setWindowState("MINIMIZED"));
    }

    @Tool(name = "restore_window", description = "Restore the active frame window to its normal state.")
    public String restoreWindow() {
        return ToolJson.toJson(windowService.setWindowState("NORMAL"));
    }

    @Tool(name = "close_window", description = "Close the active window by dispatching a window-closing event.")
    public String closeWindow() {
        return ToolJson.toJson(windowService.closeWindow());
    }
}
