package solutions.crosstech.swingmcp.demofx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the JavaFX demo through the <em>real</em> MCP server over stdio.
 *
 * <p>{@link DemoFxToolkitIT} proves the toolkit works when called directly.
 * That is not the same as proving it works when called by the server: the
 * server translates each tool's arguments into agent command parameters, and
 * the first version of the JavaFX toolkit read different parameter names for
 * five commands, so {@code find_component} through the server returned nothing
 * while every direct test passed. This test exists so that class of defect is
 * caught by the build rather than by a user.</p>
 *
 * <p>Requires the server and agent jars, passed by failsafe as system
 * properties; skipped cleanly when they are absent (e.g. a partial build).</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerRoundTripIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FX_UID = Pattern.compile("\"uid\"\\s*:\\s*\"(fx-\\d+)\"");

    private Process server;
    private BufferedWriter in;
    private BufferedReader out;
    private int id = 0;

    @BeforeAll
    void startServer() throws Exception {
        String serverJar = System.getProperty("swingmcp.server.jar");
        String agentJar = System.getProperty("swingmcp.agent.jar");
        String demoJar = System.getProperty("swingmcp.demofx.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(serverJar != null && new File(serverJar).isFile(),
            "server jar not available: " + serverJar);
        org.junit.jupiter.api.Assumptions.assumeTrue(agentJar != null && new File(agentJar).isFile(),
            "agent jar not available: " + agentJar);
        org.junit.jupiter.api.Assumptions.assumeTrue(demoJar != null && new File(demoJar).isFile(),
            "demo jar not available: " + demoJar);

        ProcessBuilder pb = new ProcessBuilder("java", "-jar", serverJar);
        pb.environment().put("SWING_MCP_AGENT_JAR", agentJar);
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.redirectError(Files.createTempFile("swing-mcp-server", ".err").toFile());
        server = pb.start();
        in = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
        out = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));

        JsonNode init = rpc("initialize", JSON.createObjectNode()
            .put("protocolVersion", "2025-11-25")
            .set("capabilities", JSON.createObjectNode()));
        assertEquals("swing-mcp-server", init.path("result").path("serverInfo").path("name").asText());
        notify("notifications/initialized");
    }

    @AfterAll
    void stopServer() throws Exception {
        try {
            tool("stop_app", JSON.createObjectNode());
        } catch (Exception ignored) {
            // best effort
        }
        if (in != null) {
            in.close();
        }
        if (server != null) {
            server.destroy();
            server.waitFor();
        }
    }

    // ---- JSON-RPC plumbing ----------------------------------------------------

    private JsonNode rpc(String method, ObjectNode params) throws Exception {
        ObjectNode msg = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", ++id).put("method", method);
        msg.set("params", params);
        in.write(JSON.writeValueAsString(msg));
        in.newLine();
        in.flush();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            String line = out.readLine();
            if (line == null) {
                throw new IllegalStateException("server closed stdout");
            }
            if (!line.startsWith("{")) {
                continue;
            }
            JsonNode node = JSON.readTree(line);
            if (node.path("id").asInt(-1) == id) {
                return node;
            }
        }
        throw new IllegalStateException("timed out waiting for " + method);
    }

    private void notify(String method) throws Exception {
        ObjectNode msg = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        in.write(JSON.writeValueAsString(msg));
        in.newLine();
        in.flush();
    }

    /** Calls a tool and returns its text content; fails loudly on isError. */
    private String tool(String name, ObjectNode args) throws Exception {
        ObjectNode params = JSON.createObjectNode().put("name", name);
        params.set("arguments", args);
        JsonNode r = rpc("tools/call", params);
        StringBuilder text = new StringBuilder();
        for (JsonNode c : r.path("result").path("content")) {
            if ("text".equals(c.path("type").asText())) {
                text.append(c.path("text").asText());
            }
        }
        assertFalse(r.path("result").path("isError").asBoolean(false),
            name + " returned isError: " + text);
        return text.toString();
    }

    private static String firstFxUid(String text) {
        Matcher m = FX_UID.matcher(text);
        assertTrue(m.find(), "expected an fx- uid in: " + text.substring(0, Math.min(300, text.length())));
        return m.group(1);
    }

    /** The module path is whatever JavaFX jars are on this test JVM's classpath. */
    private static String javafxModulePath() {
        List<String> fx = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            String base = Path.of(entry).getFileName().toString();
            if (base.startsWith("javafx-") && base.endsWith(".jar") && !base.contains("-sources")) {
                fx.add(entry);
            }
        }
        assertFalse(fx.isEmpty(), "no javafx jars on the test classpath");
        return String.join(File.pathSeparator, fx);
    }

    // ---- the round trip ----------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("launch_app starts the JavaFX demo with the agent attached")
    void launch() throws Exception {
        String demoJar = System.getProperty("swingmcp.demofx.jar");
        String cmd = "java --module-path \"" + javafxModulePath() + "\" --add-modules javafx.controls -jar \"" + demoJar + "\"";
        ObjectNode args = JSON.createObjectNode().put("command", cmd);
        // env is an object parameter (#25); it must survive the server's schema binding.
        args.putObject("env").put("SWING_MCP_ROUND_TRIP", "1");
        String r = tool("launch_app", args);
        assertTrue(r.contains("pid") || r.contains("session"), r);
        assertTrue(r.contains("outputFile"), "launch_app must say where the output goes: " + r);
        // Give the stage a moment to show before the first snapshot.
        Thread.sleep(2500);

        // The agent logs its start to stderr, which get_app_output must return (#26).
        String output = tool("get_app_output", JSON.createObjectNode().put("tail", 200));
        assertTrue(output.contains("Swing MCP Agent started"), output);
    }

    @Test
    @Order(2)
    @DisplayName("take_snapshot returns fx- uids and no skin internals")
    void snapshot() throws Exception {
        String snap = tool("take_snapshot", JSON.createObjectNode().put("filter", "VISIBLE_ONLY"));
        assertTrue(snap.contains("Swing MCP JavaFX Demo"), snap.substring(0, Math.min(300, snap.length())));
        firstFxUid(snap);
        assertFalse(snap.contains("LabeledText"), "skin internals leaked through the server");

        // maxDepth and uid reach the agent through the server contract (#21).
        String shallow = tool("take_snapshot", JSON.createObjectNode().put("maxDepth", 1));
        assertTrue(shallow.contains("omittedChildren"), shallow);
        String root = firstFxUid(shallow);
        String subtree = tool("take_snapshot", JSON.createObjectNode().put("uid", root).put("maxNodes", 1));
        assertTrue(subtree.contains("\"truncated\":true"), subtree);
    }

    @Test
    @Order(3)
    @DisplayName("find_component by NAME reaches a JavaFX control through the server contract")
    void findByName() throws Exception {
        // This is the call that returned [] before the wire contract was honoured.
        String r = tool("find_component", JSON.createObjectNode().put("query", "nameField").put("by", "NAME"));
        firstFxUid(r);
        assertTrue(r.contains("nameField"), r);
    }

    @Test
    @Order(4)
    @DisplayName("fill, select_option, click and get_component_details round-trip through the server")
    void fillAndSubmit() throws Exception {
        String name = firstFxUid(tool("find_component", JSON.createObjectNode().put("query", "nameField").put("by", "NAME")));
        tool("fill", JSON.createObjectNode().put("uid", name).put("text", "Ada"));
        String combo = firstFxUid(tool("find_component", JSON.createObjectNode().put("query", "countryCombo").put("by", "NAME")));
        tool("select_option", JSON.createObjectNode().put("uid", combo).put("text", "Belgium"));
        String submit = firstFxUid(tool("find_component", JSON.createObjectNode().put("query", "submitButton").put("by", "NAME")));
        tool("click", JSON.createObjectNode().put("uid", submit));
        String result = firstFxUid(tool("find_component", JSON.createObjectNode().put("query", "formResult").put("by", "NAME")));
        String details = tool("get_component_details", JSON.createObjectNode().put("uid", result));
        assertTrue(details.contains("submitted: Ada"), details);
        assertTrue(details.contains("Belgium"), details);
    }

    @Test
    @Order(5)
    @DisplayName("wait_for COMPONENT_TEXT honours the server's conditionType contract")
    void waitFor() throws Exception {
        String r = tool("wait_for", JSON.createObjectNode()
            .put("conditionType", "COMPONENT_TEXT").put("expectedValue", "submitted: Ada").put("timeoutMs", 3000));
        assertTrue(r.contains("Condition met"), r);
    }

    @Test
    @Order(6)
    @DisplayName("get_table_data honours startRow/endRow through the server")
    void table() throws Exception {
        String tabs = firstFxUid(tool("find_component", JSON.createObjectNode().put("query", "tabs").put("by", "NAME")));
        tool("select_option", JSON.createObjectNode().put("uid", tabs).put("text", "Data"));
        String table = firstFxUid(tool("find_component", JSON.createObjectNode().put("query", "partsTable").put("by", "NAME")));
        String data = tool("get_table_data", JSON.createObjectNode().put("uid", table).put("startRow", 1).put("endRow", 3));
        assertTrue(data.contains("Gasket"), data);
        assertFalse(data.contains("Bearing"), "row 0 should be excluded by startRow=1: " + data);

        // select_table_cell takes a rows array (#18); the schema must carry it through.
        ObjectNode select = JSON.createObjectNode().put("uid", table);
        select.putArray("rows").add(0).add(2);
        String selected = tool("select_table_cell", select);
        assertTrue(selected.contains("rows [0, 2]"), selected);
    }

    @Test
    @Order(6)
    @DisplayName("take_screenshot writes a real PNG for a JavaFX window through the server")
    void screenshot() throws Exception {
        // The JavaFX toolkit used to answer with keys the server did not read, so the
        // server wrote the bytes of base64("null") as the PNG (#24).
        JsonNode shot = JSON.readTree(tool("take_screenshot", JSON.createObjectNode()));
        byte[] png = Files.readAllBytes(Path.of(shot.path("path").asText()));
        assertTrue(png.length > 100, "PNG too small: " + png.length + " bytes");
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        assertTrue(shot.path("width").asInt() > 0, shot.toString());
    }

    @Test
    @Order(7)
    @DisplayName("a modal Alert goes pending, is listed, and is dismissed — all through the server")
    void modalDialog() throws Exception {
        String r = tool("select_menu_item", JSON.createObjectNode().put("path", "Help > About"));
        assertTrue(r.contains("pending"), r);
        String dialogs = tool("list_dialogs", JSON.createObjectNode());
        assertTrue(dialogs.contains("About"), dialogs);
        assertTrue(dialogs.contains("Used for testing the swing-mcp server."), dialogs);
        String h = tool("handle_dialog", JSON.createObjectNode().put("button", "OK"));
        assertTrue(h.contains("Pressed OK"), h);
    }

    @Test
    @Order(8)
    @DisplayName("press_key honours the server's keys chord contract")
    void pressKey() throws Exception {
        String tabs = firstFxUid(tool("find_component", JSON.createObjectNode().put("query", "tabs").put("by", "NAME")));
        tool("select_option", JSON.createObjectNode().put("uid", tabs).put("text", "Form"));
        String name = firstFxUid(tool("find_component", JSON.createObjectNode().put("query", "nameField").put("by", "NAME")));
        tool("focus", JSON.createObjectNode().put("uid", name));
        String r = tool("press_key", JSON.createObjectNode().put("keys", "END"));
        assertTrue(r.contains("Key pressed: END"), r);
    }

    @Test
    @Order(9)
    @DisplayName("mouse_wheel carries rotation, point and modifiers through the server")
    void mouseWheel() throws Exception {
        String tabs = firstFxUid(tool("find_component", JSON.createObjectNode().put("query", "tabs").put("by", "NAME")));
        tool("select_option", JSON.createObjectNode().put("uid", tabs).put("text", "Canvas"));
        String canvas = firstFxUid(tool("find_component", JSON.createObjectNode().put("query", "graphCanvas").put("by", "NAME")));
        String r = tool("mouse_wheel", JSON.createObjectNode()
            .put("uid", canvas).put("rotation", -3).put("x", 20).put("y", 30).put("modifiers", "SHIFT"));
        assertTrue(r.contains("-3 notch(es) at (20,30)") && r.contains("SHIFT"), r);
        String state = firstFxUid(tool("find_component", JSON.createObjectNode().put("query", "canvasState").put("by", "NAME")));
        String details = tool("get_component_details", JSON.createObjectNode().put("uid", state));
        assertTrue(details.contains("Canvas zoom 100%, offset (60,0)"), "SHIFT+wheel pans: " + details);
    }

    @Test
    @Order(10)
    @DisplayName("a tool error reaches the client once, with its message")
    void toolErrorText() throws Exception {
        ObjectNode params = JSON.createObjectNode().put("name", "select_session");
        params.set("arguments", JSON.createObjectNode().put("sessionId", "no-such-session"));
        JsonNode r = rpc("tools/call", params);
        assertTrue(r.path("result").path("isError").asBoolean(false), r.toString());
        String text = r.path("result").path("content").path(0).path("text").asText();
        String message = "Unknown session id 'no-such-session'";
        // Spring AI 2.0.1 and 2.0.2 append the root cause's message to the exception's own,
        // which repeats a message that has no cause.
        assertEquals(text.indexOf(message), text.lastIndexOf(message), "message repeated: " + text);
        assertTrue(text.contains(message), text);
    }
}
