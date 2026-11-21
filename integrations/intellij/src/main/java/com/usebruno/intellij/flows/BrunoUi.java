package com.usebruno.intellij.flows;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;

final class BrunoUi {
    static final Color AMBER = new JBColor(new Color(0x8A5900), new Color(0xE8B15A));
    static final Color MUTED = new JBColor(new Color(0x626870), new Color(0xA8ADB5));
    static JButton button(String text, Icon icon, Runnable action) {
        JButton button = new JButton(text, icon);
        if (action != null) button.addActionListener(e -> action.run());
        return button;
    }
    static JButton primaryButton(String text, Icon icon, Runnable action) {
        JButton button = button(text, icon, action);
        button.putClientProperty("JButton.buttonType", "default");
        return button;
    }
    static JButton icon(String title, Icon icon, Runnable action) {
        JButton button = button("", icon, action);
        button.setToolTipText(title); button.getAccessibleContext().setAccessibleName(title);
        button.setPreferredSize(JBUI.size(28, 28));
        return button;
    }
    static JPanel toolbar(Component... controls) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(3)));
        for (Component control : controls) panel.add(control);
        return panel;
    }
    static JPanel labeled(String label, JComponent control) {
        JPanel panel = new JPanel(new BorderLayout(6, 4));
        JLabel text = new JLabel(label); text.setLabelFor(control);
        panel.add(text, BorderLayout.WEST); panel.add(control, BorderLayout.CENTER);
        return panel;
    }
    static void onText(javax.swing.text.JTextComponent field, Runnable change) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { change.run(); }
            public void removeUpdate(DocumentEvent e) { change.run(); }
            public void changedUpdate(DocumentEvent e) { change.run(); }
        });
    }
    static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    static Color methodColor(String method) {
        return switch (method) {
            case "GET" -> new JBColor(new Color(0x187443), new Color(0x69C78C));
            case "POST" -> AMBER;
            case "PUT" -> new JBColor(new Color(0x2466A8), new Color(0x73AEF5));
            case "DELETE" -> new JBColor(new Color(0xB13333), new Color(0xF08080));
            case "PATCH" -> new JBColor(new Color(0x8053A6), new Color(0xBF9BE8));
            case "HEAD" -> new JBColor(new Color(0x08767B), new Color(0x66C8C8));
            default -> MUTED;
        };
    }
    static void copy(String text, JLabel feedback, String message) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        feedback.setText(message);
        if (feedback.isShowing()) {
            com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                    .createHtmlTextBalloonBuilder(message, com.intellij.openapi.ui.MessageType.INFO, null)
                    .setFadeoutTime(1800).createBalloon()
                    .show(com.intellij.ui.awt.RelativePoint.getSouthOf(feedback), com.intellij.openapi.ui.popup.Balloon.Position.below);
        }
        // Status feedback never includes the copied value.
    }
}
