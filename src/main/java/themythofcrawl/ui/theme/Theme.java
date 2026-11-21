package themythofcrawl.ui.theme;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;

import themythofcrawl.core.AuditLevel;
import themythofcrawl.model.RuleMode;

/**
 * Dynamic semantic design tokens, typography and spacing derived from the active Look &amp; Feel
 * (e.g. Burp Light / Dark / custom themes). Automatically clears caches and refreshes tracked UI
 * trees whenever {@code UIManager}'s {@code lookAndFeel} changes.
 */
public final class Theme {
    /** Spacing scale (4 px grid). */
    public static final int SPACE_1 = 4;
    public static final int SPACE_2 = 8;
    public static final int SPACE_3 = 12;
    public static final int SPACE_4 = 16;
    public static final int SPACE_6 = 24;

    /** Corner radii (Burp Suite 2026 style: sharp rectangular & square geometry, zero rounding). */
    public static final int RADIUS_SM = 0;
    public static final int RADIUS_CARD = 0;
    public static final int RADIUS_PILL = 0;

    /** Default row heights (clean breathing room for Vietnamese diacritics). */
    public static final int ROW_TABLE = 26;
    public static final int ROW_TREE = 24;

    /**
     * Semantic colour token:
     * <ul>
     *   <li>{@code base}: raw hue
     *   <li>{@code fill}: subtle tinted surface (chips, row tints, cards)
     *   <li>{@code fillStrong}: emphasised tinted surface (hover / alternate row / active chip)
     *   <li>{@code border}: 1 px outline matching the token
     *   <li>{@code text}: foreground guaranteed &ge; 4.5:1 on {@code fill}, {@code fillStrong}, and surface
     *   <li>{@code solidFill}/{@code onSolid}: rich fill + foreground (&ge; 4.5:1) for prominent action buttons
     * </ul>
     */
    public record Token(Color base, Color fill, Color fillStrong, Color border, Color text, Color solidFill, Color onSolid) {
    }

    public static final int[][] ACCENT_HUES = {{66, 133, 244}, {234, 88, 12}, {22, 163, 74}, {168, 85, 247}, {219, 39, 119},
        {13, 148, 136}, {202, 138, 4}, {100, 116, 139}};
    public static final int[][] LEVEL_HUES = {{37, 99, 235}, {13, 148, 136}, {147, 51, 234}, {217, 119, 6}, {219, 39, 119},
        {22, 163, 74}, {71, 85, 105}};

    private static final Color DARK_INK = new Color(17, 24, 39);
    private static final Map<String, Theme> CACHE = new HashMap<>();
    private static final List<WeakReference<Component>> TRACKED = new ArrayList<>();
    private static volatile boolean listenerInstalled;
    private static volatile boolean refreshing;
    private static volatile float fontScaleMultiplier = 1.0f;
    private static volatile int generation = 1;
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    public final boolean dark;
    public final float scale;
    public final Color bg;
    public final Color fg;
    public final Color surface;
    public final Color surfaceAlt;
    public final Color hover;
    public final Color border;
    public final Color grid;
    public final Color muted;
    public final Color selectionBg;
    public final Color selectionFg;
    public final Color dotNone;
    public final Color treeLine;
    public final Color[] zebra;

    public final Font baseFont;
    public final Font mediumFont;
    public final Font boldFont;
    public final Font headingFont;
    public final Font smallFont;
    public final Font smallBoldFont;
    public final Font italicFont;
    public final Font monoFont;
    public final Font monoBoldFont;
    public final Font monoSmallFont;

    public final Token success;
    public final Token warning;
    public final Token danger;
    public final Token critical;
    public final Token info;
    public final Token pending;
    public final Token dup;
    public final Token ghost;
    public final Token accent;
    public final Token orig;
    public final Token pink;
    public final Token teal;
    public final Token orange;
    public final Token slate;
    public final Token indigo;
    public final Token cyan;
    public final Token emerald;
    public final Token amber;
    public final Token neutral;

    private final Map<String, Token> customHexTokens = new HashMap<>();

    private final Map<AuditLevel, Token> auditTokens = new EnumMap<>(AuditLevel.class);
    private final Map<String, Token> methodTokens = new HashMap<>();
    private final Token otherMethod;
    private final Token[] levels = new Token[LEVEL_HUES.length];
    private final Token[] accents = new Token[ACCENT_HUES.length];
    private final Map<RuleMode, Token> modeTokens = new EnumMap<>(RuleMode.class);
    private final Map<Object, Border> stripes = new HashMap<>();
    public final Border cellPad = BorderFactory.createEmptyBorder(0, 6, 0, 6);

    static {
        installLafListener();
    }

    private static void installLafListener() {
        if (listenerInstalled) {
            return;
        }
        listenerInstalled = true;
        PropertyChangeListener pcl = evt -> {
            String p = evt.getPropertyName();
            if ("lookAndFeel".equals(p) || "UIDefaults".equals(p)) {
                onThemeChanged();
            }
        };
        UIManager.addPropertyChangeListener(pcl);
    }

    public static void onThemeChanged() {
        clearCache();
        generation++;
        List<Runnable> callbacks;
        synchronized (LISTENERS) {
            callbacks = new ArrayList<>(LISTENERS);
        }
        Runnable r = () -> {
            if (refreshing) {
                return;
            }
            refreshing = true;
            try {
                List<Component> roots = new ArrayList<>();
                synchronized (TRACKED) {
                    Iterator<WeakReference<Component>> it = TRACKED.iterator();
                    while (it.hasNext()) {
                        Component c = it.next().get();
                        if (c == null) {
                            it.remove();
                        } else {
                            roots.add(c);
                        }
                    }
                }
                Theme cur = current();
                for (Component c : roots) {
                    SwingUtilities.updateComponentTreeUI(c);
                    FontManager.applyTreeFonts(c, cur);
                    c.invalidate();
                    c.repaint();
                }
                for (Runnable cb : callbacks) {
                    cb.run();
                }
            } finally {
                refreshing = false;
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    public static int generation() {
        return generation;
    }

    public static synchronized void clearCache() {
        CACHE.clear();
        generation++;
    }

    /** Registers a root component (panel, frame, dialog) to receive {@code updateComponentTreeUI} on theme changes. */
    public static void track(Component root) {
        if (root == null) {
            return;
        }
        synchronized (TRACKED) {
            for (WeakReference<Component> ref : TRACKED) {
                if (ref.get() == root) {
                    return;
                }
            }
            TRACKED.add(new WeakReference<>(root));
        }
        FontManager.applyTreeFonts(root, current());
    }

    public static void addListener(Runnable onChange) {
        if (onChange != null) {
            synchronized (LISTENERS) {
                LISTENERS.add(onChange);
            }
        }
    }

    public static float fontScaleMultiplier() {
        return fontScaleMultiplier;
    }

    public static void setFontScaleMultiplier(float multiplier) {
        float m = Math.max(0.8f, Math.min(1.4f, multiplier));
        if (Math.abs(fontScaleMultiplier - m) > 0.001f) {
            fontScaleMultiplier = m;
            onThemeChanged();
        }
    }

    /** Current theme built from active {@code UIManager} defaults. */
    public static Theme current() {
        return of(UIManager.getColor("Panel.background"), UIManager.getColor("Label.foreground"),
                UIManager.getColor("Table.background"), UIManager.getColor("Component.accentColor"),
                UIManager.getColor("Table.selectionBackground"), UIManager.getColor("Separator.foreground"),
                UIManager.getColor("Label.disabledForeground"));
    }

    /** Theme adapted to a specific surface background / foreground (e.g. a JTable or JTree). */
    public static Theme of(Color bg, Color fg) {
        return of(bg, fg, bg, UIManager.getColor("Component.accentColor"), UIManager.getColor("Table.selectionBackground"),
                UIManager.getColor("Separator.foreground"), UIManager.getColor("Label.disabledForeground"));
    }

    public static synchronized Theme of(Color panelBg, Color labelFg, Color tableBg, Color accentColor, Color selBg,
                                        Color sepFg, Color disabledFg) {
        LookAndFeel laf = UIManager.getLookAndFeel();
        String lafId = laf != null ? laf.getClass().getName() : "none";
        Font uiFont = UIManager.getFont("Label.font");
        int fontSz = uiFont != null ? uiFont.getSize() : 13;
        Color b = panelBg != null ? panelBg : new Color(255, 255, 255);
        Color f = labelFg != null ? labelFg : (isDark(b) ? new Color(226, 232, 240) : new Color(30, 30, 30));
        Color tb = tableBg != null ? tableBg : b;
        Color sb = selBg != null ? selBg : new Color(37, 99, 235);
        Color ac = accentColor != null ? accentColor : sb;
        String key = lafId + "|" + b.getRGB() + "|" + f.getRGB() + "|" + tb.getRGB() + "|" + ac.getRGB() + "|"
                + sb.getRGB() + "|" + (sepFg != null ? sepFg.getRGB() : 0) + "|"
                + (disabledFg != null ? disabledFg.getRGB() : 0) + "|" + fontSz + "|" + fontScaleMultiplier
                + "|" + FontManager.uiChoice().id + "|" + FontManager.monoChoice().id;
        Theme t = CACHE.get(key);
        if (t == null) {
            if (CACHE.size() > 16) {
                CACHE.clear();
            }
            t = new Theme(b, f, tb, ac, sb, sepFg, disabledFg, uiFont);
            CACHE.put(key, t);
        }
        return t;
    }

    private Theme(Color panelBg, Color labelFg, Color tableBg, Color accentColor, Color selBg, Color sepFg,
                  Color disabledFg, Font labelFont) {
        this.bg = panelBg;
        this.dark = isDark(panelBg);
        this.fg = ensureContrast(labelFg, panelBg, 4.6, !dark);
        this.surface = tableBg;
        this.surfaceAlt = mix(surface, fg, dark ? 0.05 : 0.03);
        this.hover = mix(surface, fg, dark ? 0.09 : 0.05);
        this.zebra = new Color[] {surface, mix(surface, fg, dark ? 0.055 : 0.04)};
        this.grid = mix(surface, fg, 0.14);
        this.border = sepFg != null && contrast(sepFg, surface) >= 1.15 ? sepFg : mix(surface, fg, dark ? 0.20 : 0.15);
        this.treeLine = this.border;
        this.dotNone = mix(surface, fg, 0.35);

        Color candMuted = disabledFg != null ? disabledFg : mix(fg, surface, dark ? 0.36 : 0.42);
        this.muted = ensureContrastAll(candMuted, !dark, 3.3, surface, surfaceAlt, bg);

        this.selectionBg = selBg;
        Color uiSelFg = UIManager.getColor("Table.selectionForeground");
        this.selectionFg = uiSelFg != null && contrast(uiSelFg, selBg) >= 4.5 ? uiSelFg : bestInkOn(selBg);

        Font lf = labelFont != null ? labelFont : new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        float basePt = Math.max(12f, Math.min(18f, Math.max(13f, lf.getSize2D()) * fontScaleMultiplier));
        float smallPt = Math.max(11.5f, basePt - 1f);
        this.scale = Math.max(0.82f, basePt / 13f);
        this.baseFont = FontManager.uiRegular(lf, basePt);
        this.mediumFont = FontManager.uiMedium(lf, basePt);
        this.boldFont = FontManager.uiBold(lf, basePt);
        this.headingFont = FontManager.uiBold(lf, basePt + 2f);
        this.smallFont = FontManager.uiRegular(lf, smallPt);
        this.smallBoldFont = FontManager.uiBold(lf, smallPt);
        this.italicFont = baseFont.deriveFont(Font.ITALIC);

        Font mf = UIManager.getFont("monospaced.font");
        float monoPt = Math.max(11.5f, basePt - 0.5f);
        this.monoFont = FontManager.monoRegular(mf, monoPt);
        this.monoBoldFont = FontManager.monoBold(mf, monoPt);
        this.monoSmallFont = FontManager.monoRegular(mf, Math.max(11f, monoPt - 1f));

        this.success = token(new Color(22, 163, 74));     // #16A34A
        this.warning = token(new Color(217, 119, 6));     // #D97706
        this.danger = token(new Color(220, 38, 38));      // #DC2626
        this.critical = token(new Color(234, 88, 12));    // #EA580C
        this.info = token(new Color(37, 99, 235));        // #2563EB
        this.pending = token(new Color(100, 116, 139));   // #64748B
        this.dup = token(new Color(147, 51, 234));        // #9333EA
        this.ghost = ghostToken();
        this.accent = token(accentColor);
        this.orig = info;
        this.pink = token(new Color(219, 39, 119));       // #DB2777
        this.teal = token(new Color(13, 148, 136));       // #0D9488
        this.orange = critical;
        this.slate = token(new Color(71, 85, 105));       // #475569
        this.indigo = token(new Color(79, 70, 229));      // #4F46E5
        this.cyan = token(new Color(8, 145, 178));        // #0891B2
        this.emerald = token(new Color(5, 150, 105));     // #059669
        this.amber = token(new Color(202, 138, 4));       // #CA8A04
        Color neuSolid = mix(surface, fg, dark ? 0.24 : 0.14);
        Color neuOnSolid = ensureContrast(this.fg, neuSolid, 4.6, !dark);
        this.neutral = new Token(dotNone, surfaceAlt, hover, border, this.fg, neuSolid, neuOnSolid);

        auditTokens.put(AuditLevel.OK, success);
        auditTokens.put(AuditLevel.INFO, warning);
        auditTokens.put(AuditLevel.WARN, critical);
        auditTokens.put(AuditLevel.ERROR, danger);
        auditTokens.put(AuditLevel.REDIRECT, info);
        auditTokens.put(AuditLevel.PENDING, pending);
        auditTokens.put(AuditLevel.NONE, neutral);

        methodTokens.put("GET", success);
        methodTokens.put("POST", info);
        methodTokens.put("PUT", warning);
        methodTokens.put("PATCH", dup);
        methodTokens.put("DELETE", danger);
        otherMethod = pending;

        modeTokens.put(RuleMode.REPLACE, info);
        modeTokens.put(RuleMode.ADD, success);
        modeTokens.put(RuleMode.REMOVE, danger);
        modeTokens.put(RuleMode.COOKIE_SET, dup);
        modeTokens.put(RuleMode.COOKIE_REMOVE, critical);

        for (int i = 0; i < LEVEL_HUES.length; i++) {
            levels[i] = token(rgb(LEVEL_HUES[i]));
        }
        for (int i = 0; i < ACCENT_HUES.length; i++) {
            accents[i] = token(rgb(ACCENT_HUES[i]));
        }
    }

    private Token token(Color base) {
        // Muted tints: Burp keeps colour for meaning only (status, severity), never for decoration.
        Color fill = mix(surface, base, dark ? 0.16 : 0.07);
        Color fillStrong = mix(surface, base, dark ? 0.26 : 0.14);
        Color edge = mix(surface, base, dark ? 0.46 : 0.36);
        Color startText = dark ? mix(base, Color.WHITE, 0.46) : mix(base, Color.BLACK, 0.20);
        Color txt = ensureContrastAll(startText, !dark, 4.6, fill, fillStrong, surface, surfaceAlt, bg);
        Color solid = base;
        Color onSolid = bestInkOn(solid);
        if (contrast(onSolid, solid) < 4.6) {
            solid = ensureContrast(solid, onSolid, 4.6, onSolid.equals(Color.WHITE));
        }
        return new Token(base, fill, fillStrong, edge, txt, solid, onSolid);
    }

    private Token ghostToken() {
        Color base = mix(fg, surface, dark ? 0.48 : 0.52);
        Color fill = mix(surface, base, dark ? 0.14 : 0.08);
        Color fillStrong = mix(surface, base, dark ? 0.22 : 0.14);
        Color edge = mix(surface, base, 0.38);
        Color txt = ensureContrastAll(base, !dark, 4.6, fill, fillStrong, surface, surfaceAlt, bg);
        Color solid = mix(surface, fg, dark ? 0.28 : 0.20);
        Color onSolid = ensureContrast(this.fg, solid, 4.6, !dark);
        return new Token(base, fill, fillStrong, edge, txt, solid, onSolid);
    }

    public int scaled(int px) {
        return Math.max(1, Math.round(px * scale));
    }

    public Token level(int depth) {
        return levels[(Math.max(depth, 1) - 1) % levels.length];
    }

    public Token audit(AuditLevel lvl) {
        return lvl == null ? neutral : auditTokens.getOrDefault(lvl, neutral);
    }

    public Token method(String m) {
        if (m == null) {
            return otherMethod;
        }
        return methodTokens.getOrDefault(m.toUpperCase(Locale.ROOT), otherMethod);
    }

    public Token mode(RuleMode m) {
        return m == null ? neutral : modeTokens.getOrDefault(m, neutral);
    }

    public Color statusColor(int code) {
        if (code >= 200 && code < 300) {
            return success.text();
        }
        if (code >= 300 && code < 400) {
            return info.text();
        }
        if (code == 401 || code == 403) {
            return warning.text();
        }
        if (code >= 400 && code < 500) {
            return critical.text();
        }
        return code >= 500 ? danger.text() : fg;
    }

    public boolean tinted(AuditLevel lvl) {
        return lvl != null && lvl != AuditLevel.NONE;
    }

    public Color rowBackground(AuditLevel lvl, int viewRow) {
        if (!tinted(lvl)) {
            return zebra[viewRow & 1];
        }
        // Very light severity wash over the zebra stripe: readable at a glance, not decorative.
        Token t = audit(lvl);
        return mix(zebra[viewRow & 1], t.base(), dark ? 0.10 : 0.045);
    }

    public Color branchAccent(Object key) {
        if (key == null) {
            return null;
        }
        if ("ORIG".equals(key)) {
            return orig.base();
        }
        int k = key instanceof Number n ? n.intValue() : Math.abs(key.hashCode());
        return accents[Math.floorMod(k - 1, accents.length)].base();
    }

    /** Palette token for a 0-based role / category index. */
    public Token accent(int index) {
        return accents[Math.floorMod(index, accents.length)];
    }

    /** Generates a WCAG-verified semantic {@link Token} from an arbitrary {@code #RRGGBB} hex string. */
    public synchronized Token tokenForHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        String clean = hex.trim().toUpperCase(Locale.ROOT);
        if (clean.startsWith("#")) {
            clean = clean.substring(1);
        }
        if (clean.length() != 6) {
            return null;
        }
        Token cached = customHexTokens.get(clean);
        if (cached != null) {
            return cached;
        }
        try {
            int rgb = Integer.parseInt(clean, 16);
            Color base = new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            Token made = token(base);
            if (customHexTokens.size() > 64) {
                customHexTokens.clear();
            }
            customHexTokens.put(clean, made);
            return made;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Resolves one of the 12 semantic color keys (or an optional custom {@code #RRGGBB} override). */
    public Token tokenForColorKey(String colorKey, String customHex) {
        Token hexTok = tokenForHex(customHex);
        if (hexTok != null) {
            return hexTok;
        }
        if (colorKey == null || colorKey.isBlank()) {
            return info;
        }
        return switch (colorKey.trim().toLowerCase(Locale.ROOT)) {
            case "accent", "orange" -> accent;
            case "warn", "warning" -> warning;
            case "amber" -> amber;
            case "purple", "dup" -> dup;
            case "indigo" -> indigo;
            case "teal" -> teal;
            case "cyan" -> cyan;
            case "bad", "danger", "red" -> danger;
            case "critical", "high" -> critical;
            case "pink", "rose" -> pink;
            case "ok", "success", "green" -> success;
            case "emerald" -> emerald;
            case "muted", "slate", "neutral" -> slate;
            default -> info;
        };
    }

    public static String normalizeHex(String raw) {
        if (raw == null) {
            return "";
        }
        String clean = raw.trim().toUpperCase(Locale.ROOT);
        if (clean.startsWith("#")) {
            clean = clean.substring(1);
        }
        if (clean.length() == 6 && clean.matches("[0-9A-F]{6}")) {
            return "#" + clean;
        }
        return "";
    }

    public Border stripe(Object key) {
        return stripes.computeIfAbsent(key == null ? "none" : key, k -> {
            Color c = branchAccent(key);
            return c == null ? BorderFactory.createEmptyBorder(0, 10, 0, 6)
                    : BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, c),
                            BorderFactory.createEmptyBorder(0, 6, 0, 6));
        });
    }

    public static boolean isDark(Color bg) {
        if (bg == null) {
            return false;
        }
        return (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) < 128000;
    }

    public static Color rgb(int[] c) {
        return new Color(c[0], c[1], c[2]);
    }

    public static Color mix(Color a, Color b, double t) {
        double k = Math.max(0.0, Math.min(1.0, t));
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * k),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * k),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * k));
    }

    /** WCAG 2.1 relative luminance (0.0 = black, 1.0 = white). */
    public static double relativeLuminance(Color c) {
        double r = linearize(c.getRed() / 255.0);
        double g = linearize(c.getGreen() / 255.0);
        double b = linearize(c.getBlue() / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double linearize(double srgb) {
        return srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }

    /** WCAG 2.1 contrast ratio between two colours (1.0 .. 21.0). */
    public static double contrast(Color a, Color b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** Picks either white or dark ink for maximum contrast against {@code bg}. */
    public static Color bestInkOn(Color bg) {
        return contrast(Color.WHITE, bg) >= contrast(DARK_INK, bg) ? Color.WHITE : DARK_INK;
    }

    /** Shifts {@code fg} toward black or white until {@code contrast(fg, bg) >= minRatio}. */
    public static Color ensureContrast(Color fg, Color bg, double minRatio, boolean preferDarker) {
        if (contrast(fg, bg) >= minRatio) {
            return fg;
        }
        Color target = preferDarker ? Color.BLACK : Color.WHITE;
        if (contrast(target, bg) < minRatio) {
            target = contrast(Color.WHITE, bg) >= contrast(Color.BLACK, bg) ? Color.WHITE : Color.BLACK;
        }
        double lo = 0.0;
        double hi = 1.0;
        for (int i = 0; i < 16; i++) {
            double mid = (lo + hi) * 0.5;
            if (contrast(mix(fg, target, mid), bg) >= minRatio) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return mix(fg, target, hi);
    }

    private static Color ensureContrastAll(Color fg, boolean preferDarker, double minRatio, Color... bgs) {
        Color cur = fg;
        for (Color b : bgs) {
            if (b != null) {
                cur = ensureContrast(cur, b, minRatio, preferDarker);
            }
        }
        // Verify after last adjustment that every surface passes
        for (Color b : bgs) {
            if (b != null && contrast(cur, b) < minRatio) {
                cur = ensureContrast(cur, b, minRatio, preferDarker);
            }
        }
        return cur;
    }
}
