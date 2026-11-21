package themythofcrawl.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.geom.AffineTransform;

import javax.swing.LookAndFeel;
import javax.swing.UIManager;

import themythofcrawl.core.Text;

/**
 * Version, Look &amp; Feel and theme diagnostics shown in tooltips, Settings (Section 8) and the
 * Burp output load line so users can confirm immediately which jar and theme are active.
 */
public final class Diagnostics {
    public static final String VERSION = resolveVersion();

    private Diagnostics() {
    }

    private static String resolveVersion() {
        Package pkg = Diagnostics.class.getPackage();
        String v = pkg != null ? pkg.getImplementationVersion() : null;
        return v != null && !v.isBlank() ? v : "7.2-beta";
    }

    public static String lafName() {
        LookAndFeel laf = UIManager.getLookAndFeel();
        return laf != null ? laf.getName() : "(null)";
    }

    public static String lafClass() {
        LookAndFeel laf = UIManager.getLookAndFeel();
        return laf != null ? laf.getClass().getName() : "(null)";
    }

    public static boolean isDark() {
        return themythofcrawl.ui.theme.Theme.current().dark;
    }

    public static String fontSummary() {
        Font f = themythofcrawl.ui.theme.Theme.current().baseFont;
        if (f == null) {
            f = UIManager.getFont("Label.font");
        }
        return f != null ? f.getFamily() + ", " + f.getSize() : "(null)";
    }

    public static String colorHex(String key) {
        Color c = UIManager.getColor(key);
        return c == null ? "(null)" : String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    public static String uiScale() {
        String prop = System.getProperty("sun.java2d.uiScale");
        String measured = null;
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                AffineTransform tx = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform();
                measured = String.format(java.util.Locale.ROOT, "%.2fx", tx.getScaleX());
            }
        } catch (Throwable ignored) {
            // headless or no display device
        }
        if (prop != null && !prop.isBlank()) {
            return measured != null ? prop + " (hệ thống: " + measured + ")" : prop;
        }
        return measured != null ? measured + " (sun.java2d.uiScale chưa đặt)" : "(mặc định)";
    }

    /** Shown in Workspace / status tooltips: version + LAF + dark + font. */
    public static String summary() {
        return Text.EXT_NAME + " " + VERSION + " · LAF: " + lafName() + " · dark=" + isDark() + " · font=" + fontSummary();
    }

    /** Logged to Burp Extension Output when loaded. */
    public static String loadLogLine() {
        return Text.EXT_NAME + " " + VERSION + " loaded · LAF=" + lafName() + " · dark=" + isDark();
    }

    /** Detailed multi-line report copied from Settings Section 8. */
    public static String fullReport(String workspaceName, String workspaceLocation) {
        return String.join("\n",
                "Extension: " + Text.EXT_NAME + " " + VERSION,
                "LAF: " + lafName() + " (" + lafClass() + ")",
                "dark: " + isDark(),
                "Label.font: " + fontSummary(),
                "Panel.background: " + colorHex("Panel.background"),
                "Table.background: " + colorHex("Table.background"),
                "Component.accentColor: " + colorHex("Component.accentColor"),
                "sun.java2d.uiScale: " + uiScale(),
                "Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
                "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                "Workspace: " + Text.nz(workspaceName) + " — " + Text.nz(workspaceLocation));
    }
}
