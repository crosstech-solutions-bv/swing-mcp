package solutions.crosstech.swingmcp.demo;

import java.awt.BorderLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

/**
 * Panel with a JTree of sample nodes.
 */
public class TreePanel extends JPanel {

    public TreePanel(JLabel statusLabel) {
        super(new BorderLayout());
        setName("treePanel");

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Projects");
        DefaultMutableTreeNode alpha = new DefaultMutableTreeNode("Alpha");
        alpha.add(new DefaultMutableTreeNode("src"));
        alpha.add(new DefaultMutableTreeNode("docs"));
        DefaultMutableTreeNode beta = new DefaultMutableTreeNode("Beta");
        beta.add(new DefaultMutableTreeNode("src"));
        beta.add(new DefaultMutableTreeNode("tests"));
        root.add(alpha);
        root.add(beta);

        JTree tree = new JTree(root);
        tree.setName("projectTree");
        tree.addTreeSelectionListener(e -> {
            if (tree.getLastSelectedPathComponent() != null) {
                statusLabel.setText("Tree selected: " + tree.getLastSelectedPathComponent());
            }
        });

        add(new JScrollPane(tree), BorderLayout.CENTER);
    }
}
