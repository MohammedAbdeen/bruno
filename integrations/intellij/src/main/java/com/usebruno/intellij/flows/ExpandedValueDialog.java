package com.usebruno.intellij.flows;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** Wrapped view/edit surface; visual wrapping never changes the value. */
final class ExpandedValueDialog extends DialogWrapper {
    private final JTextArea value;
    private final JPasswordField masked;
    private final JCheckBox reveal = new JCheckBox("Show value");
    private final JLabel feedback = new JLabel(" ");
    private final RequestRow row;
    private final Consumer<String> apply;
    private final JPanel content = new JPanel(new BorderLayout(JBUI.scale(6),JBUI.scale(6)));

    ExpandedValueDialog(Component parent, RequestRow row, Consumer<String> apply) {
        super(parent,true);
        this.row=row; this.apply=apply;
        value=new JTextArea(row.value,12,64); value.setLineWrap(true); value.setWrapStyleWord(false);
        value.getAccessibleContext().setAccessibleName("Expanded value");
        masked=new JPasswordField(row.value); masked.getAccessibleContext().setAccessibleName("Hidden value");
        var fields=new JPanel(new CardLayout());
        fields.add(new JScrollPane(value),"plain");
        var hidden=new JPanel(new BorderLayout()); hidden.add(masked,BorderLayout.NORTH);
        hidden.add(new JLabel("Secret value is hidden. Show it only when needed."),BorderLayout.CENTER);
        fields.add(hidden,"hidden");
        ((CardLayout)fields.getLayout()).show(fields,row.secret ? "hidden" : "plain");
        reveal.setVisible(row.secret);
        reveal.addActionListener(event -> {
            if (reveal.isSelected()) value.setText(new String(masked.getPassword()));
            else masked.setText(value.getText());
            ((CardLayout)fields.getLayout()).show(fields,reveal.isSelected() ? "plain" : "hidden");
        });
        var copy=BrunoUi.button("Copy value",com.intellij.icons.AllIcons.Actions.Copy,() -> BrunoUi.copy(text(),feedback,"Value copied"));
        copy.setEnabled(!row.secret); reveal.addActionListener(event -> copy.setEnabled(reveal.isSelected()));
        content.add(new JLabel(row.secret && !row.secretRef.isBlank() && !row.secretEdited
                ? "Stored secret is not loaded here. Enter a replacement, or use Show selected secret in Environments."
                : "Visual wrapping preserves the exact value; it does not insert line breaks."),BorderLayout.NORTH);
        fields.setPreferredSize(JBUI.size(640,280)); content.add(fields,BorderLayout.CENTER);
        var footer=new JPanel(new BorderLayout()); footer.add(BrunoUi.toolbar(reveal,copy),BorderLayout.WEST); footer.add(feedback,BorderLayout.CENTER);
        content.add(footer,BorderLayout.SOUTH);
        setTitle("Expanded value"+(row.key.isBlank() ? "" : " — "+row.key)); setOKButtonText("Apply"); setResizable(true); init();
    }
    private String text() { return row.secret && !reveal.isSelected() ? new String(masked.getPassword()) : value.getText(); }
    @Override protected JComponent createCenterPanel() { return content; }
    @Override public JComponent getPreferredFocusedComponent() { return row.secret ? masked : value; }
    @Override protected void doOKAction() {
        String next=text(); if (!next.equals(row.value)) apply.accept(next);
        super.doOKAction();
    }
}
