package solutions.crosstech.swingmcp.demo;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * Panel with a JList and a JComboBox.
 */
public class ListsPanel extends JPanel {

    public ListsPanel(JLabel statusLabel) {
        super(new BorderLayout(10, 10));
        setName("listsPanel");

        DefaultListModel<String> model = new DefaultListModel<>();
        for (int i = 1; i <= 20; i++) {
            model.addElement("Item " + i);
        }
        JList<String> list = new JList<>(model);
        list.setName("itemList");
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                statusLabel.setText("List selected: " + list.getSelectedValue());
            }
        });

        JComboBox<String> combo = new JComboBox<>(new String[] {"Red", "Green", "Blue", "Yellow"});
        combo.setName("colorCombo");
        combo.addActionListener(e -> statusLabel.setText("Color: " + combo.getSelectedItem()));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Color:"));
        top.add(combo);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
    }
}
