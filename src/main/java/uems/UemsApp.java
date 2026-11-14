package uems;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.*;



// ======== ui ========

/** Application entry point: {@code java UemsApp [config-file]}. */
public final class UemsApp {
    private UemsApp() {}

    public static void main(String[] args) {
        Path cfgPath = Path.of(args.length > 0 ? args[0] : System.getProperty("uems.config", "uems.properties"));
        SwingUtilities.invokeLater(() -> start(cfgPath));
    }

    private static void start(Path cfgPath) {
        try {
            for (UIManager.LookAndFeelInfo i : UIManager.getInstalledLookAndFeels()) if ("Nimbus".equals(i.getName())) { UIManager.setLookAndFeel(i.getClassName()); break; }
        } catch (Exception ignored) { }
        UIManager.put("defaultFont", new java.awt.Font("Dialog", java.awt.Font.PLAIN, 14));
        for (Object k : java.util.Collections.list(UIManager.getDefaults().keys()))
            if (k.toString().endsWith(".font")) UIManager.put(k, new javax.swing.plaf.FontUIResource("Dialog", java.awt.Font.PLAIN, 14));
        AppContext app;
        try {
            if (!Files.exists(cfgPath)) throw new IllegalStateException("Configuration file not found: " + cfgPath.toAbsolutePath() + "\nCopy uems.properties.example to uems.properties.");
            app = AppContext.create(Config.load(cfgPath));
            app.persistence.tx.runInTransaction(() -> app.persistence.catalog.schemaVersion());   // fail fast if the database is unreachable
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "UEMS cannot start:\n" + e.getMessage(), "UEMS", JOptionPane.ERROR_MESSAGE);
            return;
        }
        UiContext ctx = new UiContext(app);
        new MainFrame(ctx).setVisible(true);
    }
}
