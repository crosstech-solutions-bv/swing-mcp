package solutions.crosstech.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Map;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Commands whose listeners open a modal dialog answer promptly with a pending
 * result instead of timing out on the EDT, and the dialog can then be handled.
 */
class ModalOpeningCommandsTest {

    private final SwingCommands commands = new SwingCommands();
    /** Set only by the hover test, so the real pointer resting on the zone cannot open a dialog. */
    private volatile boolean hoverArmed;
    private JFrame frame;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        SwingUtilities.invokeAndWait(() -> {
            frame = new JFrame("Modal Opening Test");
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    JOptionPane.showConfirmDialog(frame, "Discard changes?", "Confirm close",
                        JOptionPane.YES_NO_OPTION);
                }
            });
            JPanel panel = new JPanel(new GridLayout(0, 1));

            JSpinner spinner = new JSpinner();
            spinner.setName("spinner");
            spinner.addChangeListener(e -> alert("Spinner changed"));
            panel.add(spinner);

            JComboBox<String> combo = new JComboBox<>(new String[] {"One", "Two"});
            combo.setName("combo");
            combo.addActionListener(e -> alert("Combo changed"));
            panel.add(combo);

            JTable table = new JTable(new Object[][] {{"a"}, {"b"}}, new Object[] {"Col"});
            table.setName("table");
            table.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    alert("Row selected");
                }
            });
            panel.add(new JScrollPane(table));

            JTree tree = new JTree();
            tree.setName("tree");
            tree.addTreeSelectionListener(e -> alert("Node selected"));
            panel.add(new JScrollPane(tree));

            JPanel dropZone = new JPanel();
            dropZone.setName("dropZone");
            dropZone.setPreferredSize(new Dimension(200, 40));
            dropZone.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    alert("Dropped");
                }
            });
            panel.add(dropZone);

            JPanel hoverZone = new JPanel();
            hoverZone.setName("hoverZone");
            hoverZone.setPreferredSize(new Dimension(200, 40));
            hoverZone.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    if (hoverArmed) {
                        alert("Hovered");
                    }
                }
            });
            panel.add(hoverZone);

            frame.add(panel);
            frame.setSize(400, 600);
            frame.setVisible(true);
        });
    }

    private void alert(String title) {
        JOptionPane.showMessageDialog(frame, title, title, JOptionPane.INFORMATION_MESSAGE);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (frame != null) {
            SwingCommands.disposeAllWindows();
        }
    }

    @Test
    void closeWindowWithConfirmDialogIsPending() throws Exception {
        CommandResponse r = timed(CommandType.CLOSE_WINDOW, Map.of());
        Map<?, ?> result = (Map<?, ?>) r.result();
        assertEquals("pending", result.get("status"));
        assertEquals(Boolean.TRUE, result.get("modalDialogOpen"));
        dismiss("Confirm close", "No");
        assertTrue(frame.isVisible(), "answering No must keep the window open");
    }

    @Test
    void fillIsPendingWhileAChangeListenerShowsADialog() throws Exception {
        CommandResponse r = timed(CommandType.FILL, Map.of("uid", commands.uidOf("spinner"), "text", "5"));
        assertPendingText(r);
        dismiss("Spinner changed", "OK");
    }

    @Test
    void selectOptionIsPendingWhileAListenerShowsADialog() throws Exception {
        CommandResponse r = timed(CommandType.SELECT_OPTION, Map.of("uid", commands.uidOf("combo"), "text", "Two"));
        assertPendingText(r);
        dismiss("Combo changed", "OK");
    }

    @Test
    void selectTableCellIsPendingWhileAListenerShowsADialog() throws Exception {
        CommandResponse r = timed(CommandType.SELECT_TABLE_CELL, Map.of("uid", commands.uidOf("table"), "row", 1));
        assertPendingText(r);
        dismiss("Row selected", "OK");
    }

    @Test
    void selectTreeNodeIsPendingWhileAListenerShowsADialog() throws Exception {
        CommandResponse r = timed(CommandType.SELECT_TREE_NODE,
            Map.of("uid", commands.uidOf("tree"), "path", "JTree > colors"));
        assertPendingText(r);
        dismiss("Node selected", "OK");
    }

    @Test
    void dragIsPendingWhileTheDropHandlerShowsADialog() throws Exception {
        CommandResponse r = timed(CommandType.DRAG, Map.of("fromUid", commands.uidOf("dropZone"),
            "fromX", 10, "fromY", 10, "toX", 100, "toY", 20));
        assertPendingText(r);
        dismiss("Dropped", "OK");
    }

    @Test
    void hoverIsPendingWhileTheEnterHandlerShowsADialog() throws Exception {
        String uid = commands.uidOf("hoverZone");
        hoverArmed = true;
        CommandResponse r = timed(CommandType.HOVER, Map.of("uid", uid));
        assertPendingText(r);
        dismiss("Hovered", "OK");
    }

    @Test
    void commandsWithoutADialogStillReturnTheirResult() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<?> quiet = new JComboBox<>(new String[] {"A", "B"});
            quiet.setName("quiet");
            frame.getContentPane().add(quiet, 0);
            frame.revalidate();
        });
        CommandResponse r = commands.call(CommandType.SELECT_OPTION, Map.of("uid", commands.uidOf("quiet"), "text", "B"));
        assertTrue(r.success(), r.error());
        assertTrue(String.valueOf(r.result()).startsWith("Option selected"), String.valueOf(r.result()));
    }

    private CommandResponse timed(CommandType type, Map<String, Object> params) throws Exception {
        long start = System.currentTimeMillis();
        CommandResponse r = commands.call(type, params);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(r.success(), type + " failed: " + r.error());
        assertTrue(elapsed < 5_000, type + " blocked on the modal dialog for " + elapsed + " ms");
        return r;
    }

    private static void assertPendingText(CommandResponse r) {
        String text = String.valueOf(r.result());
        assertTrue(text.startsWith("Pending"), "expected a pending result, got: " + text);
        assertTrue(text.contains("modal dialog"), text);
    }

    private void dismiss(String title, String button) throws Exception {
        SwingCommands.waitUntil(title + " dialog shown", () -> SwingCommands.dialogVisible(title));
        CommandResponse handled = commands.call(CommandType.HANDLE_DIALOG, Map.of("button", button));
        assertTrue(handled.success(), "handle_dialog failed: " + handled.error());
        SwingCommands.waitUntil(title + " dialog closed", () -> SwingCommands.visibleDialogCount() == 0);
    }
}
