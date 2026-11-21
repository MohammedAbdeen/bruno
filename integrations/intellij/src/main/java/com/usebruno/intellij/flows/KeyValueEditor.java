package com.usebruno.intellij.flows;

import com.intellij.icons.AllIcons;
import com.intellij.ui.table.JBTable;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

final class KeyValueEditor extends JPanel {
    private final List<RequestRow> rows = new ArrayList<>();
    private final boolean unique;
    private final boolean secrets;
    private final Runnable changed;
    final JTable table;
    private final AbstractTableModel model;
    private final JButton expand;

    KeyValueEditor(List<RequestRow> initial, boolean unique, boolean secrets, Runnable changed) {
        super(new BorderLayout(4, 4));
        this.unique = unique; this.secrets = secrets; this.changed = changed;
        initial.forEach(r -> rows.add(r.copy()));
        model = new AbstractTableModel() {
            public int getRowCount() { return rows.size(); }
            public int getColumnCount() { return secrets ? 5 : 4; }
            public String getColumnName(int c) { return new String[]{"Enabled", "Key", "Value", "Description", "Secret"}[c]; }
            public Class<?> getColumnClass(int c) { return c == 0 || c == 4 ? Boolean.class : String.class; }
            public boolean isCellEditable(int r, int c) { return true; }
            public Object getValueAt(int r, int c) {
                var row = rows.get(r);
                return switch (c) {
                    case 0 -> row.enabled; case 1 -> row.key; case 2 -> row.secret ? "••••" : row.value;
                    case 3 -> row.description; default -> row.secret;
                };
            }
            public void setValueAt(Object value, int r, int c) {
                var row = rows.get(r);
                switch (c) {
                    case 0 -> { boolean next=Boolean.TRUE.equals(value); if (row.enabled==next) return; row.enabled=next; }
                    case 1 -> { String next=Objects.toString(value,""); if (row.key.equals(next)) return; row.key=next; }
                    case 2 -> { String next=Objects.toString(value,""); if (row.value.equals(next)) return; row.value=next; row.secretEdited=true; }
                    case 3 -> { String next=Objects.toString(value,""); if (row.description.equals(next)) return; row.description=next; }
                    case 4 -> {
                        boolean next=Boolean.TRUE.equals(value); if (row.secret==next) return; row.secret=next;
                        if (!row.secret && !row.secretRef.isEmpty()) { row.value = ""; row.secretRef = ""; }
                        row.secretEdited = true;
                    }
                }
                fireTableRowsUpdated(r, r);
                // JTable removes its editor after setValueAt returns. Collecting
                // rows synchronously here would recursively commit that editor.
                SwingUtilities.invokeLater(changed);
            }
        };
        table = new JBTable(model) {
            @Override public TableCellEditor getCellEditor(int row, int column) {
                if (convertColumnIndexToModel(column) == 2 && rows.get(convertRowIndexToModel(row)).secret) {
                    return new DefaultCellEditor(new JPasswordField()) {
                        @Override public Component getTableCellEditorComponent(JTable t, Object v, boolean selected, int r, int c) {
                            return super.getTableCellEditorComponent(t, rows.get(t.convertRowIndexToModel(r)).value, selected, r, c);
                        }
                    };
                }
                return super.getCellEditor(row, column);
            }
        };
        var valueRenderer=new DefaultTableCellRenderer();
        var rendered=new JPanel(new BorderLayout()); var icon=new JLabel(AllIcons.Actions.Expandall);
        icon.setPreferredSize(com.intellij.util.ui.JBUI.size(26,24)); icon.setHorizontalAlignment(SwingConstants.CENTER);
        rendered.add(valueRenderer,BorderLayout.CENTER); rendered.add(icon,BorderLayout.EAST);
        table.getColumnModel().getColumn(2).setCellRenderer((view,value,selected,focus,row,column) -> {
            var label=valueRenderer.getTableCellRendererComponent(view,value,selected,focus,row,column);
            rendered.setBackground(label.getBackground()); icon.setForeground(label.getForeground());
            rendered.setToolTipText("Expand value to view or edit the full text"); return rendered;
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int column=table.columnAtPoint(event.getPoint()), row=table.rowAtPoint(event.getPoint());
                if (row<0 || column<0 || table.convertColumnIndexToModel(column)!=2) return;
                Rectangle cell=table.getCellRect(row,column,true);
                if (event.getX()>=cell.x+cell.width-com.intellij.util.ui.JBUI.scale(26)) expandSelectedValue();
            }
        });
        table.putClientProperty("terminateEditOnFocusLost", true);
        table.setRowHeight(Math.max(com.intellij.util.ui.JBUI.scale(24), table.getRowHeight()));
        table.getColumnModel().getColumn(0).setMaxWidth(com.intellij.util.ui.JBUI.scale(72));
        if (secrets) table.getColumnModel().getColumn(4).setMaxWidth(com.intellij.util.ui.JBUI.scale(65));
        JButton bulk = BrunoUi.button("Bulk Edit", AllIcons.Actions.Edit, this::bulkEdit);
        expand=BrunoUi.button("Expand value…",AllIcons.Actions.Expandall,this::expandSelectedValue);
        expand.setToolTipText("View/edit the full selected value — Ctrl+Shift+Enter / Cmd+Shift+Enter");
        expand.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(event -> expand.setEnabled(table.getSelectedRow()>=0));
        JPanel tools = BrunoUi.toolbar(
                BrunoUi.icon("Add row", AllIcons.General.Add, () -> addRow(new RequestRow())),
                BrunoUi.icon("Remove selected rows", AllIcons.General.Remove, this::removeSelected), bulk, expand);
        add(tools, BorderLayout.NORTH); add(new JScrollPane(table), BorderLayout.CENTER);
        int modifier=com.intellij.openapi.util.SystemInfo.isMac ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,modifier | InputEvent.SHIFT_DOWN_MASK),"expandValue");
        table.getActionMap().put("expandValue",new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { expandSelectedValue(); }
        });
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "editCell");
        table.getActionMap().put("editCell", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                int r = table.getSelectedRow(), c = table.getSelectedColumn();
                if (r >= 0 && c >= 0) table.editCellAt(r, c);
            }
        });
    }

    ExpandedValueDialog expandedValueDialog() {
        commit(); int selected=table.getSelectedRow();
        if (selected<0) return null;
        int index=table.convertRowIndexToModel(selected); var row=rows.get(index);
        return new ExpandedValueDialog(this,row.copy(),next -> {
            int current=rows.indexOf(row); if (current>=0) model.setValueAt(next,current,2);
        });
    }
    private void expandSelectedValue() {
        var dialog=expandedValueDialog(); if (dialog!=null) dialog.show();
    }

    void emptyText(String text) { ((JBTable) table).getEmptyText().setText(text); }

    void commit() { if (table.isEditing()) table.getCellEditor().stopCellEditing(); }
    List<RequestRow> rows() { commit(); return rows.stream().map(RequestRow::copy).toList(); }
    void setRows(List<RequestRow> next) {
        commit(); rows.clear(); next.forEach(r -> rows.add(r.copy())); model.fireTableDataChanged();
    }
    void addRow(RequestRow row) {
        commit(); rows.add(row); model.fireTableDataChanged();
        int view=table.convertRowIndexToView(rows.size()-1);
        if (view>=0) table.setRowSelectionInterval(view,view);
        table.setColumnSelectionInterval(1, 1); changed.run(); table.requestFocusInWindow();
    }
    private void removeSelected() {
        commit(); int[] selected = java.util.Arrays.stream(table.getSelectedRows()).map(table::convertRowIndexToModel).sorted().toArray();
        for (int i = selected.length - 1; i >= 0; i--) rows.remove(selected[i]);
        model.fireTableDataChanged(); changed.run();
    }
    private void bulkEdit() {
        commit();
        try {
            JTextArea text = new JTextArea(BulkRows.format(rows), 15, 60);
            JPanel content = new JPanel(new BorderLayout(4, 4));
            content.add(new JLabel("key=value   •   # key=value disables a row   •   Tab separates description"), BorderLayout.NORTH);
            content.add(new JScrollPane(text), BorderLayout.CENTER);
            while (JOptionPane.showConfirmDialog(this, content, "Bulk Edit — Apply or Cancel", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                try {
                    var parsed = BulkRows.parse(text.getText(), rows, unique);
                    boolean different = !BulkRows.format(parsed).equals(BulkRows.format(rows));
                    setRows(parsed); if (different) changed.run(); return;
                } catch (IllegalArgumentException error) {
                    BrunoNotifications.error(null, error.getMessage());
                }
            }
        } catch (IllegalArgumentException error) {
            BrunoNotifications.info(null, error.getMessage());
        }
    }
}
