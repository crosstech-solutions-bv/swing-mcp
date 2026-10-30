package solutions.crosstech.swingmcp.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import solutions.crosstech.swingmcp.server.config.SwingMcpProperties;
import solutions.crosstech.swingmcp.server.domain.AgentCommandException;
import solutions.crosstech.swingmcp.server.registry.SessionRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #25: the launched process gets extra environment variables, and the
 * {@code -javaagent} argument goes after the java launcher, not after whatever
 * the first token happens to be.
 */
class LaunchCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void agentGoesAfterPlainJava() {
        assertEquals(1, ApplicationService.agentArgIndex(List.of("java", "-jar", "app.jar")));
    }

    @Test
    void agentGoesAfterJavaBehindAWrapper() {
        assertEquals(3, ApplicationService.agentArgIndex(
            List.of("env", "HDF5_PLUGIN_PATH=./plugins", "java", "-jar", "app.jar")));
    }

    @Test
    void javaWithPathOrExeSuffixIsRecognised() {
        assertEquals(1, ApplicationService.agentArgIndex(List.of("/usr/lib/jvm/temurin-21/bin/java", "-jar", "a.jar")));
        assertEquals(1, ApplicationService.agentArgIndex(List.of("C:\\Program Files\\Java\\bin\\javaw.exe", "-jar", "a.jar")));
        assertEquals(1, ApplicationService.agentArgIndex(List.of("JAVA.EXE", "-jar", "a.jar")));
    }

    @Test
    void argumentsNamedLikeJavaDoNotCount() {
        assertEquals(1, ApplicationService.agentArgIndex(List.of("java", "-jar", "java-app.jar")));
    }

    @Test
    void commandWithoutAJavaLauncherIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> ApplicationService.agentArgIndex(List.of("./run.sh", "-Dx=<y>", "app")));
        assertTrue(e.getMessage().contains("Java launcher"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
            () -> ApplicationService.agentArgIndex(List.of("rm", "-rf", "java-stuff")));
    }

    @Test
    void launchRejectsANonJavaCommandBeforeStartingAnything() throws Exception {
        Path jar = Files.createFile(tempDir.resolve("agent.jar"));
        SwingMcpProperties properties = new SwingMcpProperties();
        properties.setAgentJar(jar.toString());
        ApplicationService service = new ApplicationService(new SessionRegistry(), properties);
        Path marker = tempDir.resolve("ran");
        assertThrows(IllegalArgumentException.class,
            () -> service.launch("touch " + marker, null));
        assertFalse(Files.exists(marker));
    }

    /**
     * When the agent cannot start it says so in the response file: launch fails
     * at once with that reason, and the process it started (wrapper and child)
     * is killed and its files are removed.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void failedLaunchKillsTheProcessTreeAndCleansUp() throws Exception {
        // A fake "java" launcher: records its pid and its child's, reports an agent
        // failure through the response file, then keeps running like a stuck app.
        Path fakeJava = tempDir.resolve("java");
        Files.writeString(fakeJava, """
            #!/bin/sh
            echo $$ > "$PID_DIR/parent"
            for a in "$@"; do
              case "$a" in -javaagent:*) rf="${a##*responseFile=}" ;; esac
            done
            sleep 300 &
            echo $! > "$PID_DIR/child"
            echo "ERROR No free port in range [1, 2]" > "$rf"
            wait
            """);
        Files.setPosixFilePermissions(fakeJava, PosixFilePermissions.fromString("rwx------"));
        Path jar = Files.createFile(tempDir.resolve("agent.jar"));
        SwingMcpProperties properties = new SwingMcpProperties();
        properties.setAgentJar(jar.toString());
        SessionRegistry registry = new SessionRegistry();
        ApplicationService service = new ApplicationService(registry, properties);

        long start = System.nanoTime();
        AgentCommandException e = assertThrows(AgentCommandException.class,
            () -> service.launch(fakeJava + " -jar app.jar", null, null, Map.of("PID_DIR", tempDir.toString())));
        assertTrue(e.getMessage().contains("No free port in range [1, 2]"), e.getMessage());
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(20), "did not fail fast");
        assertFalse(registry.hasSession());

        for (String name : List.of("parent", "child")) {
            long pid = Long.parseLong(Files.readString(tempDir.resolve(name)).strip());
            ProcessHandle.of(pid).ifPresent(h -> {
                try {
                    h.onExit().get(10, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    // asserted below
                }
            });
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), name + " still alive");
        }
    }

    @Test
    void processBuilderAppliesEnvironmentAndWorkingDir() {
        ProcessBuilder pb = ApplicationService.processBuilder(List.of("java", "-version"), tempDir.toString(),
            Map.of("HDF5_PLUGIN_PATH", "./plugins"));
        assertEquals("./plugins", pb.environment().get("HDF5_PLUGIN_PATH"));
        assertEquals(tempDir.toFile(), pb.directory());
    }

    @Test
    void blankVariableNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> ApplicationService.processBuilder(List.of("java"), null, Map.of(" ", "x")));
    }

    /** End to end: a real child JVM sees the variable. */
    @Test
    void launchedProcessSeesTheVariable() throws Exception {
        Path source = tempDir.resolve("PrintEnv.java");
        Files.writeString(source, "public class PrintEnv { public static void main(String[] a) "
            + "{ System.out.print(System.getenv(a[0])); } }");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = ApplicationService.processBuilder(
            List.of(java, source.toString(), "SWING_MCP_LAUNCH_TEST"), null,
            Map.of("SWING_MCP_LAUNCH_TEST", "hello from env"));
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        Process process = pb.start();
        assertEquals(true, process.waitFor(60, TimeUnit.SECONDS), "child JVM did not exit");
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("hello from env", out);
    }
}
