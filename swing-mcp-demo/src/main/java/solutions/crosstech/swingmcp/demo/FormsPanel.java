package solutions.crosstech.swingmcp.demo;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

/**
 * Panel with form inputs: text field, password field, text area, and spinner.
 */
public class FormsPanel extends JPanel {

    public FormsPanel(JLabel statusLabel) {
        super(new GridBagLayout());
        setName("formsPanel");

        JTextField nameField = new JTextField(20);
        nameField.setName("nameField");

        JPasswordField passwordField = new JPasswordField(20);
        passwordField.setName("passwordField");

        JTextArea notesArea = new JTextArea(5, 20);
        notesArea.setName("notesArea");

        JSpinner ageSpinner = new JSpinner(new SpinnerNumberModel(30, 0, 150, 1));
        ageSpinner.setName("ageSpinner");

        JButton submit = new JButton("Submit");
        submit.setName("submitButton");
        submit.addActionListener(e -> statusLabel.setText(
            "Submitted: name=" + nameField.getText() + ", age=" + ageSpinner.getValue()));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        add(new JLabel("Name:"), gbc);
        gbc.gridx = 1;
        add(nameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        add(new JLabel("Password:"), gbc);
        gbc.gridx = 1;
        add(passwordField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        add(new JLabel("Age:"), gbc);
        gbc.gridx = 1;
        add(ageSpinner, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        add(new JLabel("Notes:"), gbc);
        gbc.gridx = 1;
        add(new JScrollPane(notesArea), gbc);

        gbc.gridx = 1;
        gbc.gridy = 4;
        add(submit, gbc);
    }
}
