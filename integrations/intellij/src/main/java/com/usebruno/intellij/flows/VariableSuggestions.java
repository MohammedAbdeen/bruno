package com.usebruno.intellij.flows;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.EditorTextField;
import javax.swing.*;
import java.util.*;
import java.util.function.Supplier;

/** Names only: completion never reads the credential store or exposes values. */
final class VariableSuggestions {
    record Range(int start, int end, String prefix) { }
    static Range range(String text, int caret) {
        int start = text.lastIndexOf("{{", Math.max(0, caret - 1));
        if (start < 0 || caret < start + 2) return null;
        String prefix = text.substring(start + 2, caret).strip();
        if (!prefix.matches("[\\w.-]*")) return null;
        int close = text.indexOf("}}", caret);
        int end = close >= caret && text.substring(caret, close).strip().matches("[\\w.-]*") ? close + 2 : caret;
        return new Range(start, end, prefix);
    }
    static List<String> names(ApiSettings settings, RequestDraft.Fields fields) {
        Set<String> names = new TreeSet<>(); names.add("baseUrl");
        var rows = new ArrayList<RequestRow>(settings.defaults);
        settings.environments.stream().filter(e -> e.name.equals(fields.environment)).findFirst().ifPresent(env -> {
            try { env.migrate(); rows.addAll(env.variableRows); } catch (IllegalArgumentException ignored) { }
        });
        rows.addAll(fields.variables); rows.addAll(fields.path);
        rows.stream().filter(r -> r.enabled && !r.key.isBlank()).forEach(r -> names.add(r.key));
        var session = settings.session(fields.environment); names.addAll(session.values.keySet()); names.removeAll(session.deleted);
        return List.copyOf(names);
    }
    static void install(JTextField input, Supplier<List<String>> names) {
        Runnable show = () -> {
            if (!input.isShowing() || !input.isFocusOwner()) return;
            String original = input.getText();
            var found = range(original, input.getCaretPosition());
            final Range range = found == null ? new Range(input.getCaretPosition(), input.getCaretPosition(), "") : found;
            var values = names.get().stream().filter(n -> n.startsWith(range.prefix())).toList();
            if (values.isEmpty()) return;
            JBPopupFactory.getInstance().createPopupChooserBuilder(values).setTitle("Variables")
                    .setItemChosenCallback(name -> {
                        if (!input.getText().equals(original)) return;
                        input.select(range.start(), range.end()); input.replaceSelection("{{" + name + "}}");
                    }).createPopup().showUnderneathOf(input);
        };
        input.registerKeyboardAction(e -> show.run(), KeyStroke.getKeyStroke("control SPACE"), JComponent.WHEN_FOCUSED);
        BrunoUi.onText(input, () -> SwingUtilities.invokeLater(() -> {
            if (input.getText().substring(0, input.getCaretPosition()).endsWith("{{")) show.run();
        }));
    }
    static void install(EditorTextField input, Project project, Disposable owner, Supplier<List<String>> names) {
        Runnable show = () -> {
            var editor = input.getEditor();
            if (editor == null || !input.isShowing() || !editor.getContentComponent().isFocusOwner()) return;
            String original = input.getText();
            int caret = editor.getCaretModel().getOffset();
            var found = range(original, caret);
            final Range range = found == null ? new Range(caret, caret, "") : found;
            var values = names.get().stream().filter(n -> n.startsWith(range.prefix())).toList();
            if (values.isEmpty()) return;
            JBPopupFactory.getInstance().createPopupChooserBuilder(values).setTitle("Variables")
                    .setItemChosenCallback(name -> WriteCommandAction.runWriteCommandAction(project, () -> {
                        if (editor.isDisposed() || !input.getText().equals(original)) return;
                        input.getDocument().replaceString(range.start(), range.end(), "{{" + name + "}}");
                        editor.getCaretModel().moveToOffset(range.start() + name.length() + 4);
                    })).createPopup().showUnderneathOf(input);
        };
        input.registerKeyboardAction(e -> show.run(), KeyStroke.getKeyStroke("control SPACE"), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        input.getDocument().addDocumentListener(new com.intellij.openapi.editor.event.DocumentListener() {
            @Override public void documentChanged(com.intellij.openapi.editor.event.DocumentEvent event) {
                if (event.getNewFragment().toString().contains("{")) SwingUtilities.invokeLater(show);
            }
        }, owner);
    }
}
