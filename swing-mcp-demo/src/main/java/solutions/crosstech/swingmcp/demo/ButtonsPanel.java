package solutions.crosstech.swingmcp.demo;

import java.awt.FlowLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JToggleButton;
import javax.swing.ButtonGroup;

/**
 * Panel with button variants: push, toggle, check box, and radio buttons.
 */
public class ButtonsPanel extends JPanel {

    private int clickCount;

    public ButtonsPanel(JLabel statusLabel) {
        super(new FlowLayout(FlowLayout.LEFT, 10, 10));
        setName("buttonsPanel");

        JButton clickMe = new JButton("Click Me");
        clickMe.setName("clickMeButton");
        clickMe.addActionListener(e -> statusLabel.setText("Clicked " + (++clickCount) + " times"));

        JToggleButton toggle = new JToggleButton("Toggle");
        toggle.setName("toggleButton");
        toggle.addActionListener(e -> statusLabel.setText("Toggle: " + (toggle.isSelected() ? "ON" : "OFF")));

        JCheckBox checkBox = new JCheckBox("Enable feature");
        checkBox.setName("featureCheckBox");
        checkBox.addActionListener(e -> statusLabel.setText("Feature: " + (checkBox.isSelected() ? "enabled" : "disabled")));

        JRadioButton optionA = new JRadioButton("Option A", true);
        optionA.setName("optionARadio");
        JRadioButton optionB = new JRadioButton("Option B");
        optionB.setName("optionBRadio");
        ButtonGroup group = new ButtonGroup();
        group.add(optionA);
        group.add(optionB);
        optionA.addActionListener(e -> statusLabel.setText("Selected: Option A"));
        optionB.addActionListener(e -> statusLabel.setText("Selected: Option B"));

        JButton disabled = new JButton("Disabled");
        disabled.setName("disabledButton");
        disabled.setEnabled(false);

        add(clickMe);
        add(toggle);
        add(checkBox);
        add(optionA);
        add(optionB);
        add(disabled);
    }
}
