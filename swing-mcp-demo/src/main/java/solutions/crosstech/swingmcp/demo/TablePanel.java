package solutions.crosstech.swingmcp.demo;

import java.awt.BorderLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/**
 * Panel with a JTable of sample data.
 */
public class TablePanel extends JPanel {

    public TablePanel(JLabel statusLabel) {
        super(new BorderLayout());
        setName("tablePanel");

        String[] columns = {"ID", "Name", "Role"};
        Object[][] data = {
            {1, "Alice", "Engineer"},
            {2, "Bob", "Designer"},
            {3, "Carol", "Manager"},
            {4, "Dave", "Analyst"},
            {5, "Eve", "Tester"}
        };
        DefaultTableModel model = new DefaultTableModel(data, columns) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(model);
        table.setName("peopleTable");
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                statusLabel.setText("Table selected row: " + table.getValueAt(table.getSelectedRow(), 1));
            }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);
    }
}
