package solutions.crosstech.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** wait_for reads the component tree on the EDT, not on the command thread. */
class WaitForEdtTest {

    private final SwingCommands commands = new SwingCommands();
    /** Whether each read of the label's text happened on the EDT, once armed. */
    private final List<Boolean> readsOnEdt = new CopyOnWriteArrayList<>();
    private volatile boolean armed;
    private JFrame frame;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        SwingUtilities.invokeAndWait(() -> {
            JLabel label = new JLabel("Status: ready") {
                @Override
                public String getText() {
                    if (armed) {
                        readsOnEdt.add(SwingUtilities.isEventDispatchThread());
                    }
                    return super.getText();
                }
            };
            label.setName("status");
            frame = new JFrame("Wait For Test");
            frame.add(label);
            frame.setSize(300, 100);
            frame.setVisible(true);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (frame != null) {
            SwingCommands.disposeAllWindows();
        }
    }

    @Test
    void componentConditionsAreCheckedOnTheEdt() throws Exception {
        String uid = commands.uidOf("status");
        armed = true;

        CommandResponse text = commands.call(CommandType.WAIT_FOR,
            Map.of("conditionType", "COMPONENT_TEXT", "uid", uid, "expectedValue", "ready", "timeoutMs", 2000));
        assertTrue(text.success(), text.error());
        CommandResponse exists = commands.call(CommandType.WAIT_FOR,
            Map.of("conditionType", "COMPONENT_EXISTS", "expectedValue", "Status:", "timeoutMs", 2000));
        assertTrue(exists.success(), exists.error());

        assertFalse(readsOnEdt.isEmpty(), "the conditions never read the label");
        assertFalse(readsOnEdt.contains(Boolean.FALSE), "wait_for read Swing state off the EDT");
    }

    @Test
    void edtIdleAndTimeoutStillWork() throws Exception {
        CommandResponse idle = commands.call(CommandType.WAIT_FOR,
            Map.of("conditionType", "EDT_IDLE", "timeoutMs", 2000));
        assertTrue(idle.success(), idle.error());

        CommandResponse never = commands.call(CommandType.WAIT_FOR,
            Map.of("conditionType", "COMPONENT_EXISTS", "expectedValue", "no such text", "timeoutMs", 500));
        assertFalse(never.success());
        assertTrue(never.error().contains("Timeout"), never.error());
    }
}
