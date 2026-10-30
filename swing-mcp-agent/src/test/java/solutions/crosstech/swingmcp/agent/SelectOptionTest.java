package solutions.crosstech.swingmcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import solutions.crosstech.swingmcp.common.command.CommandResponse;
import solutions.crosstech.swingmcp.common.enums.CommandType;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * select_option selects the model's own element for non-String models and
 * fails clearly instead of silently doing nothing; icon-only tabs and menu
 * items (no text) do not break lookups.
 */
class SelectOptionTest {

    enum Level { LOW, MEDIUM, HIGH }

    /** A domain object whose toString is not what the user sees. */
    record Person(String name) {
        @Override
        public String toString() {
            return "Person@" + name.hashCode();
        }
    }

    private final SwingCommands commands = new SwingCommands();
    private final AtomicInteger saves = new AtomicInteger();
    private JFrame frame;
    private JComboBox<Level> levels;
    private JComboBox<Person> people;
    private JList<String> list;
    private JTabbedPane tabs;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        SwingUtilities.invokeAndWait(() -> {
            levels = new JComboBox<>(Level.values());
            levels.setName("levels");
            people = new JComboBox<>(new Person[] {new Person("Ada"), new Person("Linus")});
            people.setName("people");
            people.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                              boolean selected, boolean focused) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(l, value, index, selected, focused);
                    label.setText(value instanceof Person p ? p.name() : "");
                    return label;
                }
            });
            list = new JList<>(new String[] {"alpha", "beta"});
            list.setName("list");
            ImageIcon icon = new ImageIcon(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
            tabs = new JTabbedPane();
            tabs.setName("tabs");
            tabs.addTab(null, icon, new JPanel());
            tabs.addTab("Second", new JPanel());

            JMenuBar bar = new JMenuBar();
            JMenu iconMenu = new JMenu();
            iconMenu.setIcon(icon);
            bar.add(iconMenu);
            JMenu file = new JMenu("File");
            file.add(new JMenuItem(icon));
            JMenuItem save = new JMenuItem("Save");
            save.addActionListener(e -> saves.incrementAndGet());
            file.add(save);
            bar.add(file);

            JPanel panel = new JPanel(new GridLayout(0, 1));
            panel.add(levels);
            panel.add(people);
            panel.add(list);
            panel.add(tabs);
            frame = new JFrame("Select Option Test");
            frame.setJMenuBar(bar);
            frame.add(panel);
            frame.setSize(300, 400);
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
    void enumComboSelectsTheModelElement() throws Exception {
        CommandResponse r = select("levels", Map.of("text", "HIGH"));
        assertTrue(r.success(), r.error());
        assertSame(Level.HIGH, levels.getSelectedItem());
    }

    @Test
    void domainObjectComboMatchesRenderedText() throws Exception {
        CommandResponse r = select("people", Map.of("text", "Linus"));
        assertTrue(r.success(), r.error());
        assertEquals("Linus", ((Person) people.getSelectedItem()).name());
    }

    @Test
    void unmatchedComboTextIsAnError() throws Exception {
        CommandResponse r = select("levels", Map.of("text", "EXTREME"));
        assertFalse(r.success(), "selecting a missing option must fail");
        assertTrue(r.error().contains("No combo box item matches") && r.error().contains("MEDIUM"), r.error());
        assertSame(Level.LOW, levels.getSelectedItem());
    }

    @Test
    void outOfRangeIndexIsAnError() throws Exception {
        CommandResponse combo = select("levels", Map.of("index", 7));
        assertFalse(combo.success());
        assertTrue(combo.error().contains("out of range"), combo.error());

        CommandResponse jlist = select("list", Map.of("index", 2));
        assertFalse(jlist.success());
        assertTrue(jlist.error().contains("out of range"), jlist.error());
        assertEquals(-1, list.getSelectedIndex());

        assertTrue(select("list", Map.of("index", 1)).success());
        assertEquals(1, list.getSelectedIndex());
    }

    @Test
    void iconOnlyTabDoesNotBreakSelectionByTitle() throws Exception {
        CommandResponse r = select("tabs", Map.of("text", "Second"));
        assertTrue(r.success(), r.error());
        assertEquals(1, tabs.getSelectedIndex());
    }

    @Test
    void iconOnlyMenusAndItemsDoNotBreakMenuLookup() throws Exception {
        CommandResponse r = commands.call(CommandType.SELECT_MENU_ITEM, Map.of("path", "File > Save"));
        assertTrue(r.success(), r.error());
        SwingCommands.waitUntil("Save action ran", () -> saves.get() == 1);
    }

    private CommandResponse select(String name, Map<String, Object> params) throws Exception {
        Map<String, Object> all = new java.util.HashMap<>(params);
        all.put("uid", commands.uidOf(name));
        CommandResponse r = commands.call(CommandType.SELECT_OPTION, all);
        SwingUtilities.invokeAndWait(() -> { });
        return r;
    }
}
