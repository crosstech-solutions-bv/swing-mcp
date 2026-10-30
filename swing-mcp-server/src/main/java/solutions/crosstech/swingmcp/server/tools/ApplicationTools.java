package solutions.crosstech.swingmcp.server.tools;

import solutions.crosstech.swingmcp.server.service.ApplicationService;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
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

    @McpTool(name = "launch_app",
        annotations = @McpTool.McpAnnotations(title = "Launch application", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        description = """
        Launch a Java desktop application (Swing or JavaFX) with the swing-mcp agent preloaded. \
        Provide the full java command line, e.g. "java -jar /path/to/app.jar"; JVM options go \
        in the command, environment variables in env. The command must run a java launcher \
        (java or javaw), directly or behind a wrapper such as env, timeout or xvfb-run. \
        Returns session info including the target PID.""")
    public String launchApp(
            @McpToolParam(description = "Full java command line to launch the application") String command,
            @McpToolParam(description = "Optional working directory for the launched process", required = false) String workingDir,
            @McpToolParam(description = "Optional session id; auto-generated when omitted", required = false) String sessionId,
            @McpToolParam(description = "Optional environment variables for the launched process, e.g. {\"PLUGIN_PATH\": \"./plugins\"}", required = false) Map<String, String> env) {
        return ToolJson.toJson(applicationService.launch(command, workingDir, sessionId, env));
    }

    @McpTool(name = "attach_to_app",
        annotations = @McpTool.McpAnnotations(title = "Attach to running application", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
        description = """
        Attach the swing-mcp agent to an already-running Java desktop JVM (Swing or JavaFX) by process id (PID). \
        The target keeps running when the session is closed.""")
    public String attachToApp(
            @McpToolParam(description = "Process id of the target JVM") long pid,
            @McpToolParam(description = "Optional session id; auto-generated when omitted", required = false) String sessionId) {
        return ToolJson.toJson(applicationService.attach(pid, sessionId));
    }

    @McpTool(name = "stop_app",
        annotations = @McpTool.McpAnnotations(title = "Stop session", readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false),
        description = """
        Close a session (the active one by default). A launched application is terminated; \
        an attached application is only disconnected and keeps running.""")
    public String stopApp(
            @McpToolParam(description = "Optional session id; the active session when omitted", required = false) String sessionId) {
        return applicationService.stop(sessionId);
    }

    @McpTool(name = "list_sessions",
        annotations = @McpTool.McpAnnotations(title = "List sessions", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        description = "List all application sessions with their id, mode, PID, liveness, and which one is active.")
    public String listSessions() {
        return ToolJson.toJson(applicationService.listSessions());
    }

    @McpTool(name = "select_session",
        annotations = @McpTool.McpAnnotations(title = "Select session", readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        description = "Make the session with the given id the active session that other tools operate on.")
    public String selectSession(
            @McpToolParam(description = "Session id from list_sessions") String sessionId) {
        return ToolJson.toJson(applicationService.selectSession(sessionId));
    }

    @McpTool(name = "app_status",
        annotations = @McpTool.McpAnnotations(title = "Session status", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        description = "Get the status of the active application session (session id, mode, PID, liveness).")
    public String appStatus() {
        return ToolJson.toJson(applicationService.status());
    }

    @McpTool(name = "get_app_output",
        annotations = @McpTool.McpAnnotations(title = "Read application output", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
        description = """
        Read the stdout/stderr of an application started with launch_app: the last lines by default, \
        or the lines after sinceLine (pass the previous toLine to follow new output). \
        Use it to find stack traces and log messages the UI does not show.""")
    public String getAppOutput(
            @McpToolParam(description = "Number of last lines to return (default 50, max 500)", required = false) Integer tail,
            @McpToolParam(description = "Return the lines after this line number instead of a tail", required = false) Integer sinceLine,
            @McpToolParam(description = "Optional session id; the active session when omitted", required = false) String sessionId) {
        return ToolJson.toJson(applicationService.output(sessionId, tail, sinceLine));
    }
}
