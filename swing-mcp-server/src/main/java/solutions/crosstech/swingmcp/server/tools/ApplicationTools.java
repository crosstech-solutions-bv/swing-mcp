package solutions.crosstech.swingmcp.server.tools;

import solutions.crosstech.swingmcp.server.service.ApplicationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for managing the target application lifecycle.
 */
@Component
public class ApplicationTools {

    private final ApplicationService applicationService;

    public ApplicationTools(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @Tool(name = "launch_app", description = """
        Launch a Swing application with the swing-mcp agent preloaded. \
        Provide the full java command line, e.g. "java -jar /path/to/app.jar". \
        Returns session info including the target PID.""")
    public String launchApp(
            @ToolParam(description = "Full java command line to launch the application") String command,
            @ToolParam(description = "Optional working directory for the launched process", required = false) String workingDir,
            @ToolParam(description = "Optional session id; auto-generated when omitted", required = false) String sessionId) {
        return ToolJson.toJson(applicationService.launch(command, workingDir, sessionId));
    }

    @Tool(name = "attach_to_app", description = """
        Attach the swing-mcp agent to an already-running Swing JVM by process id (PID). \
        The target keeps running when the session is closed.""")
    public String attachToApp(
            @ToolParam(description = "Process id of the target Swing JVM") long pid,
            @ToolParam(description = "Optional session id; auto-generated when omitted", required = false) String sessionId) {
        return ToolJson.toJson(applicationService.attach(pid, sessionId));
    }

    @Tool(name = "stop_app", description = """
        Close a session (the active one by default). A launched application is terminated; \
        an attached application is only disconnected and keeps running.""")
    public String stopApp(
            @ToolParam(description = "Optional session id; the active session when omitted", required = false) String sessionId) {
        return applicationService.stop(sessionId);
    }

    @Tool(name = "list_sessions", description = "List all application sessions with their id, mode, PID, liveness, and which one is active.")
    public String listSessions() {
        return ToolJson.toJson(applicationService.listSessions());
    }

    @Tool(name = "select_session", description = "Make the session with the given id the active session that other tools operate on.")
    public String selectSession(
            @ToolParam(description = "Session id from list_sessions") String sessionId) {
        return ToolJson.toJson(applicationService.selectSession(sessionId));
    }

    @Tool(name = "app_status", description = "Get the status of the active application session (session id, mode, PID, liveness).")
    public String appStatus() {
        return ToolJson.toJson(applicationService.status());
    }
}
