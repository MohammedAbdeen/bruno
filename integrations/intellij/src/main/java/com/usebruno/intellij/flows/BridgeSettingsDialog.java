package com.usebruno.intellij.flows;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.net.http.HttpClient;
import java.time.Duration;

final class BridgeSettingsDialog extends DialogWrapper {
    private final Project project;
    private final JTextField url = new JTextField();
    private final JPasswordField token = new JPasswordField();
    private final JLabel status = new JLabel("Token is stored in the IDE credential store.");
    BridgeSettingsDialog(Project project) {
        super(project); this.project = project; setTitle("Bruno Development Bridge"); setOKButtonText("Save Bridge Connection");
        var prefs = BrunoPreferences.bridge(project); url.setText(prefs.bridgeUrl); init();
        if (!prefs.bridgeTokenRef.isBlank()) AppExecutorUtil.getAppExecutorService().execute(() -> {
            try { String value = SecretVault.read(prefs.bridgeTokenRef); SwingUtilities.invokeLater(() -> { if (!isDisposed() && token.getPassword().length == 0) token.setText(value); }); }
            catch (Exception ignored) { SwingUtilities.invokeLater(() -> status.setText("Saved token unavailable. Enter a replacement.")); }
        });
    }
    @Override protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout()); panel.setPreferredSize(JBUI.size(650,300)); int y=0;
        for (JComponent row : new JComponent[]{
                new JLabel("<html>Install the exported starter in your development application, then set:<br>bruno.bridge.enabled=true<br>bruno.bridge.port=19090<br>bruno.bridge.token=&lt;the token below&gt;<br>The bridge binds to loopback and is disabled by default.</html>"),
                BrunoUi.labeled("Loopback URL",url), BrunoUi.labeled("Token",token),
                BrunoUi.toolbar(BrunoUi.button("Generate token",null,() -> { byte[] random = new byte[32]; new java.security.SecureRandom().nextBytes(random); token.setText(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random)); }),
                        BrunoUi.button("Copy token",null,() -> BrunoUi.copy(new String(token.getPassword()),status,"Bridge token copied")),
                        BrunoUi.button("Test connection",null,this::test),BrunoUi.button("Export starter…",null,this::export)),status}) {
            var c = new GridBagConstraints(); c.gridy=y++; c.gridx=0; c.weightx=1; c.fill=GridBagConstraints.HORIZONTAL; c.insets=JBUI.insets(4); panel.add(row,c);
        }
        return panel;
    }
    private void test() {
        String address = url.getText().strip(), password = new String(token.getPassword()); status.setText("Connecting…");
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()) {
                var health = BridgeClient.call(client,address,password,"/health",null).getAsJsonObject();
                if (!health.has("name") || !health.get("name").getAsString().equals("Bruno development bridge")) throw new IllegalArgumentException("This port is not a Bruno development bridge.");
                SwingUtilities.invokeLater(() -> { if (!isDisposed()) status.setText("Connected to " + (health.has("application") ? health.get("application").getAsString() : "the Bruno development bridge") + "."); });
            } catch (Exception error) { SwingUtilities.invokeLater(() -> { if (!isDisposed()) status.setText("Connection failed. Check port, token and app bridge configuration."); }); }
        });
    }
    private void export() {
        JFileChooser chooser = new JFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); chooser.setDialogTitle("Export Bridge Starter and Maven POM");
        if (chooser.showSaveDialog(getContentPanel()) != JFileChooser.APPROVE_OPTION) return;
        Path destination = chooser.getSelectedFile().toPath();
        Path jar = destination.resolve("bruno-spring-bridge.jar"), pom = destination.resolve("bruno-spring-bridge.pom");
        if ((Files.exists(jar) || Files.exists(pom)) && JOptionPane.showConfirmDialog(getContentPanel(),"Replace existing bridge export files?","Export Bridge",JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                Files.createDirectories(destination);
                try (var source = getClass().getResourceAsStream("/bridge/bruno-spring-bridge.jar"); var metadata = getClass().getResourceAsStream("/bridge/bruno-spring-bridge.pom")) {
                    if (source == null || metadata == null) throw new java.io.IOException("Bridge artifacts were not packaged.");
                    Files.copy(source,jar,StandardCopyOption.REPLACE_EXISTING); Files.copy(metadata,pom,StandardCopyOption.REPLACE_EXISTING);
                }
                BrunoNotifications.info(project,"Exported bridge starter and POM to " + destination + ". Install with mvn install:install-file -Dfile=bruno-spring-bridge.jar -DpomFile=bruno-spring-bridge.pom, then add com.usebruno:bruno-spring-bridge:0.4.9 to the application.");
            } catch (Exception error) { BrunoNotifications.error(project,"Could not export bridge files. Check destination permissions."); }
        });
    }
    @Override protected void doOKAction() {
        String address = url.getText().strip(), password = new String(token.getPassword());
        if (password.length() < 24) { setErrorText("Use a random token of at least 24 characters, matching the application property."); return; }
        setOKActionEnabled(false); getCancelAction().setEnabled(false);
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                BridgeClient.base(address); String reference = SecretVault.createReference(project,"","development-bridge"); SecretVault.write(reference,password);
                SwingUtilities.invokeLater(() -> {
                    if (isDisposed() || project.isDisposed()) return;
                    var prefs = BrunoPreferences.bridge(project); String previous = prefs.bridgeTokenRef;
                    prefs.bridgeUrl = address; prefs.bridgeTokenRef = reference;
                    if (!previous.isBlank()) AppExecutorUtil.getAppExecutorService().execute(() -> {
                        try { SecretVault.delete(previous); } catch (Exception ignored) { }
                    });
                    close(OK_EXIT_CODE);
                });
            } catch (Exception error) { SwingUtilities.invokeLater(() -> { if (!isDisposed()) { setOKActionEnabled(true); getCancelAction().setEnabled(true); setErrorText("Could not save bridge configuration. Use a loopback URL and an unlocked credential store."); } }); }
        });
    }
}
