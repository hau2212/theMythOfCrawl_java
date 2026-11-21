package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import java.awt.Color;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.JTextComponent;

import themythofcrawl.app.Changes;
import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Config;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.excel.TargetEntry;
import themythofcrawl.export.Reports;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.place.PlacementBook;
import themythofcrawl.send.SendDispatcher;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.kit.Breadcrumb;
import themythofcrawl.ui.kit.ModernToggleSwitch;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SearchField;
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.kit.ToastPane;
import themythofcrawl.ui.kit.ToolbarGroup;
import themythofcrawl.ui.theme.Theme;

/**
 * The extension's tab: crisp single-row adaptive header with Live Action Status & Quick Toggle +
 * ToastPane overlay + the tabs. A single Swing timer drains the engine's changes every 200 ms.
 */
public final class MainPanel {
    public static final int TICK_MS = 200;
    public static final String[] REPEATER_MODES = {"Repeater Gọn: G01•01_Admin (+Copy tên Group)",
        "Repeater: Chỉ đẩy Role Lỗi/Lệch (Vàng/Cam)", "Repeater Đầy đủ: [G01:METHOD /path] 01_Admin"};

    /** The APIs an action applies to, and how to call them. */
    public record Scope(List<ApiGroup> groups, String label) {
    }

    final Engine engine;
    final Workspaces workspaces;
    final UiHost host;
    final String projectId;
    final Prefs uiPrefs;
    private SegmentedToggle captureToggle;
    private SegmentedToggle sendModeToggle;
    private final Pill queue = new Pill("Hàng đợi: 0", t -> t.neutral, t -> Icons.clock(t.muted, 12));
    final Breadcrumb dest = new Breadcrumb();
    private final Pill todo = new Pill("Cần xử lý: 0", t -> t.pending, t -> Icons.hourglass(t.pending.text(), 12));
    private static final java.util.WeakHashMap<Engine, java.lang.ref.WeakReference<MainPanel>> BY_ENGINE =
            new java.util.WeakHashMap<>();

    /** The panel showing {@code engine}, or null (headless / not built yet). */
    static MainPanel forEngine(Engine engine) {
        if (engine == null) {
            return null;
        }
        synchronized (BY_ENGINE) {
            var ref = BY_ENGINE.get(engine);
            return ref != null ? ref.get() : null;
        }
    }

    static void openInterceptQueue(Engine engine) {
        if (engine == null) {
            return;
        }
        MainPanel mp;
        synchronized (BY_ENGINE) {
            var ref = BY_ENGINE.get(engine);
            mp = ref != null ? ref.get() : null;
        }
        if (mp != null) {
            ProxyInterceptDialog.showFloating(mp);
        }
    }

    private final JButton spotlightBtn = new JButton();
    private final JButton actionStatusBtn = new JButton();
    private final ModernToggleSwitch actionQuickSwitch = new ModernToggleSwitch(false, true);
    private JPanel actionControlBar;
    private final JButton options = new JButton();
    private final JButton detach = new JButton();
    private final JButton workspace = new JButton();
    private final JButton topChromeToggleBtn = new JButton();
    private final JButton compactControlsBtn = new JButton();
    private Runnable proxyQueueListener;
    private Runnable policyLedgerListener;
    private JPanel headerRow1;
    private JPanel headerDrawerRow;
    private JPanel headerLeftZone;
    private JPanel headerRightFull;
    private JPanel headerRightCompact;
    private JPanel rightWrapper;
    private boolean userTopCompact;
    private boolean effectiveHeaderCompact;
    private boolean syncingActionSwitch;
    private int lastChromeWidth = 1280;

    private final Pill rolesChip = new Pill("Roles 0/0", t -> t.neutral, t -> Icons.role(t.muted, 12));
    private final Pill branchChip = new Pill("Nhánh 1", t -> t.neutral, t -> Icons.branch(t.muted));
    final Pill status = new Pill("API 0", t -> t.neutral, t -> Icons.api(t.fg, 12));
    private final Pill infoChip = new Pill("0 lệch", t -> t.warning, t -> Icons.warn(t.warning.text(), 12));
    private final Pill hiddenChip = new Pill("0 ẩn", t -> t.pending, t -> Icons.hide(t.muted, 12));
    private final Pill skippedChip = new Pill("Bỏ qua 0", t -> t.neutral, t -> Icons.filter(t.muted, 12));
    private final Pill excelChip = new Pill("Excel 0/0", t -> t.info, t -> Icons.excel(t.info.text(), 12));
    private final JLabel diagBadge = Ui.muted("");

    private final JPanel main = new JPanel(new BorderLayout());
    final ToastPane toastPane = new ToastPane(main);
    final JTabbedPane tabs = new JTabbedPane();
    /** V_7.0 grouped tabs: Báo cáo = Lịch sử + Excel; Cài đặt = Cấu hình + Log. */
    final JTabbedPane reportTabs = new JTabbedPane();
    final JTabbedPane settingsTabs = new JTabbedPane();
    private final Timer timer;
    private JFrame window;
    private JPanel headerPanel;
    private int queuePeak;
    private int tickCount;
    private boolean pulseDim;
    private Predicate<String> confirmHook;
    private final JPanel root = new JPanel(new BorderLayout()) {
        @Override
        public void updateUI() {
            super.updateUI();
            if (MainPanel.this.engine != null && MainPanel.this.timer != null) {
                syncHeader();
            }
        }
    };
    int repeaterMode;
    PushDialog.Choice pushMemory;
    Object source;   // the JTable or BranchTree used last (what "selected" means)

    final SpotlightDialog spotlight;
    final ApiTab api;
    final TreeTab treeTab;
    final HistoryTab history;
    final RolesTab roles;
    final SettingsTab settings;
    final LogTab log;
    final ExcelTab excel;
    final AuditTab audit;
    final AccessMatrixTab matrix;
    final BugsTab bugs;

    public MainPanel(Engine engine, Workspaces workspaces, UiHost host, String projectId) {
        this.engine = engine;
        this.workspaces = workspaces;
        this.host = host;
        this.projectId = projectId;
        this.uiPrefs = UiPrefs.resolve(workspaces, engine);
        UiPrefs.applyStartup(this.uiPrefs);
        TreeColumns.load(this.uiPrefs);
        Ui.errorSink = msg -> engine.log("Lỗi giao diện: " + msg, null);
        this.userTopCompact = UiPrefs.topCompact(this.uiPrefs);
        this.headerPanel = buildHeader();
        main.add(headerPanel, BorderLayout.NORTH);
        spotlight = new SpotlightDialog(this);
        toastPane.setModalOverlay(spotlight);
        api = new ApiTab(this);
        String savedLayout = uiPrefs.get(ApiTab.KEY_LAYOUT);
        if (savedLayout != null && savedLayout.contains("header=0")) {
            headerPanel.setVisible(false);
        }
        history = new HistoryTab(this);
        roles = new RolesTab(this);
        settings = new SettingsTab(this);
        log = new LogTab(this);
        excel = new ExcelTab(this);
        treeTab = new TreeTab(this);
        audit = new AuditTab(this);
        matrix = new AccessMatrixTab(this);
        bugs = new BugsTab(this);
        // V_7.0 tab groups: Cây first (where most of the work happens), reports and settings grouped
        reportTabs.addTab("Lịch sử", null, history.panel, "Cây lịch sử theo nhánh và báo cáo trực quan");
        reportTabs.addTab("Excel", null, excel.panel, "Đối chiếu file Excel: dòng nào đã bắt, số lần gặp, ở đâu");
        settingsTabs.addTab("Cấu hình", null, settings.panel, "Bộ lọc bắt, tốc độ gửi, Excel, copy, workspace");
        settingsTabs.addTab("Log", null, log.panel, "Nhật ký theo nhánh / API, xuất Excel");
        Object[][] t = {
            {"Cây", treeTab.panel, "Cây nhánh: mọi API thẳng cột Method | Path | Status | Length | Kết quả Role (Alt+1)"},
            {"API", api.panel, "Bảng API kiểu Burp HTTP History, kết quả từng vai trò (Alt+2)"},
            {"Phân quyền", matrix.panel, "Ma trận vai trò: Thấy trong UI x Gọi được -> nghi lỗi phân quyền (Alt+3)"},
            {"Lỗi", bugs.panel, "Các API đã đánh dấu lỗi: thống kê theo loại / mức độ / trạng thái, xuất báo cáo (Alt+4)"},
            {"Báo cáo", reportTabs, "Lịch sử trực quan và độ phủ Excel (Alt+5)"},
            {"Vai trò", roles.panel, "Tài khoản test: token / cookie / JWT của từng vai trò (Alt+6)"},
            {"Duyệt", audit.panel, "API mới cần xác nhận vị trí, giữ chỗ, API trùng & ẩn (Alt+7)"},
            {"Cài đặt", settingsTabs, "Cấu hình và Log (Alt+8)"}};
        for (Object[] x : t) {
            tabs.addTab((String) x[0], null, (Component) x[1], (String) x[2]);
        }
        tabs.addChangeListener(e -> tick(true));
        reportTabs.addChangeListener(e -> tick(true));
        settingsTabs.addChangeListener(e -> tick(true));
        main.add(tabs, BorderLayout.CENTER);
        root.add(toastPane, BorderLayout.CENTER);
        Ui.fastScroll(root);
        root.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                updateResponsiveChrome(root.getWidth());
            }
        });
        installShortcuts();
        Theme.track(root);
        try {
            host.applyTheme(root);
        } catch (RuntimeException ignored) {
            // outside Burp
        }
        settings.load();
        updateResponsiveChrome(lastChromeWidth);
        synchronized (BY_ENGINE) {
            BY_ENGINE.put(engine, new java.lang.ref.WeakReference<>(this));
        }
        proxyQueueListener = () -> javax.swing.SwingUtilities.invokeLater(() -> {
            syncProxyInterceptButton();
            boolean treeActive = selectedTab() == treeTab.panel;
            boolean apiActive = selectedTab() == api.panel;
            treeTab.onProxyQueueChanged(treeActive);
            api.onProxyQueueChanged(apiActive);
            ProxyInterceptDialog.refreshIfOpen(this);
            if (engine.proxyQueue().pendingCount() > 0
                    && engine.proxyQueue().isAutoOpenWindow()
                    && !java.awt.GraphicsEnvironment.isHeadless()) {
                ProxyInterceptDialog.showFloating(this);
            }
        });
        engine.proxyQueue().addListener(proxyQueueListener);

        policyLedgerListener = () -> javax.swing.SwingUtilities.invokeLater(() -> {
            syncProxyInterceptButton();
            treeTab.syncHeaderBreadcrumb();
            ProxyInterceptDialog.refreshIfOpen(this);
        });
        engine.actionEngine().policyLedger().addListener(policyLedgerListener);

        syncHeader();
        api.reloadAll();
        timer = new Timer(TICK_MS, e -> tick(false));
        timer.setCoalesce(true);
        timer.start();
    }

    public JPanel root() {
        return root;
    }

    JPanel headerPanel() {
        return headerPanel;
    }

    /** V_7.0: hide / show the whole extension header to give the tables more room (Ctrl+Alt+H). */
    void setHeaderVisible(boolean on) {
        if (headerPanel == null || headerPanel.isVisible() == on) {
            return;
        }
        headerPanel.setVisible(on);
        main.revalidate();
        main.repaint();
    }

    boolean isTreeTabActive() {
        return treeTab != null && isShowing(treeTab.panel);
    }

    /** True when {@code c} is the selected tab (directly or inside a grouped tab). */
    boolean isShowing(Component c) {
        return c != null && selectedTab() == c;
    }

    /** The leaf tab on screen: the selected top tab, or the selected page of a grouped tab. */
    Component selectedTab() {
        Component sel = tabs.getSelectedComponent();
        if (sel instanceof JTabbedPane sub && (sub == reportTabs || sub == settingsTabs)) {
            return sub.getSelectedComponent();
        }
        return sel;
    }

    /** Selects the tab showing {@code c}, opening its group (Báo cáo / Cài đặt) when needed. */
    void selectTab(Component c) {
        if (c == null) {
            return;
        }
        if (tabs.indexOfComponent(c) >= 0) {
            tabs.setSelectedComponent(c);
            return;
        }
        for (JTabbedPane sub : new JTabbedPane[] {reportTabs, settingsTabs}) {
            if (sub.indexOfComponent(c) >= 0) {
                sub.setSelectedComponent(c);
                tabs.setSelectedComponent(sub);
                return;
            }
        }
    }

    boolean headerVisible() {
        return headerPanel != null && headerPanel.isVisible();
    }

    public void dispose() {
        timer.stop();
        if (proxyQueueListener != null) {
            engine.proxyQueue().removeListener(proxyQueueListener);
            proxyQueueListener = null;
        }
        if (policyLedgerListener != null) {
            engine.actionEngine().policyLedger().removeListener(policyLedgerListener);
            policyLedgerListener = null;
        }
        ProxyInterceptDialog.closeFor(this);
        synchronized (BY_ENGINE) {
            var ref = BY_ENGINE.get(engine);
            if (ref != null && ref.get() == this) {
                BY_ENGINE.remove(engine);
            }
        }
        toastPane.dispose();
        ColumnMemory.disposeIn(root);
        if (window != null) {
            window.dispose();
        }
        treeTab.dispose();
    }

    // =================================================================== header
    private static JSeparator vSep() {
        JSeparator s = new JSeparator(SwingConstants.VERTICAL);
        s.setPreferredSize(new Dimension(2, 18));
        return s;
    }

    private JPanel buildHeader() {
        Config c = engine.config();
        int h24 = Theme.current().scaled(24);
        captureToggle = new SegmentedToggle(
                new SegmentedToggle.Option("Dừng", "Tạm dừng Capture Proxy", t -> t.slate,
                        (t, sel) -> Icons.circle(sel ? t.slate.onSolid() : t.muted, false), false),
                new SegmentedToggle.Option("Capture", "Bật / tắt bắt request từ Proxy", t -> t.danger,
                        (t, sel) -> {
                            Color dot = sel ? (pulseDim ? t.danger.border() : t.danger.base()) : t.muted;
                            return Icons.statusDot(dot, 10);
                        }, true));
        captureToggle.setFixedHeight(24);
        captureToggle.setSelectedIndex(c.captureEnabled ? 1 : 0);
        captureToggle.onChange(idx -> update(x -> x.captureEnabled = idx == 1));

        sendModeToggle = new SegmentedToggle(
                new SegmentedToggle.Option("Crawl", "Chỉ ghi lại API từ Proxy, không tự gửi Role", t -> t.slate,
                        (t, sel) -> Icons.hide(sel ? t.slate.onSolid() : t.muted, 12), false),
                new SegmentedToggle.Option("Gửi", "Tự động gửi ngay 1..N Roles khi bắt được API", t -> t.success,
                        (t, sel) -> Icons.send(sel ? t.success.onSolid() : t.success.text(), 12), false));
        sendModeToggle.setFixedHeight(24);
        sendModeToggle.setSelectedIndex(c.autoSend ? 1 : 0);
        sendModeToggle.onChange(idx -> setSendMode(idx == 1));

        queue.setPadding(2, 3, 2, 3);
        queue.setFixedHeight(24);
        queue.onClick(() -> {
            if (queue.isEnabled()) {
                engine.cancelPending("Đã hủy");
            }
        });

        dest.setMaxSegments(2);
        dest.setCellWidth(36);
        dest.setCellHeight(24);
        dest.setIconResolver(segPath -> Icons.nodeIcon(engine.nodeTypeOf(segPath), Theme.current(), 12));
        dest.onSegmentClick(seg -> {
            if (Hierarchy.normalize(seg).equals(Hierarchy.normalize(engine.activeBranch().name()))) {
                destinationMenu();
            } else {
                setDestination(seg);
            }
        });
        dest.onDropdown(this::destinationMenu);
        dest.onQuickAdd(() -> addSubfolder(engine.activeBranch().name()));

        todo.setPadding(2, 3, 2, 3);
        todo.setFixedHeight(24);
        todo.onClick(this::todoMenu);

        status.setPadding(2, 3, 2, 3);
        status.setFixedHeight(24);
        status.onClick(this::statusMenu);

        for (Pill chip : new Pill[] {rolesChip, branchChip, infoChip, hiddenChip, skippedChip, excelChip}) {
            chip.setPadding(1, 7, 1, 7);
        }
        rolesChip.onClick(() -> selectTab(roles.panel));
        branchChip.onClick(() -> selectTab(history.panel));
        infoChip.onClick(() -> {
            selectTab(api.panel);
            api.filterLevel(AuditLevel.INFO);
        });
        hiddenChip.onClick(() -> showAudit(2));
        excelChip.onClick(() -> selectTab(excel.panel));

        spotlightBtn.setIcon(Icons.themed(t -> Icons.search(t.fg)));
        spotlightBtn.setMargin(new Insets(0, 0, 0, 0));
        spotlightBtn.setPreferredSize(new Dimension(h24, h24));
        spotlightBtn.addActionListener(Ui.safe(e -> spotlight.open()));
        themythofcrawl.ui.kit.RichHoverCard.install(spotlightBtn, new themythofcrawl.ui.kit.RichHoverCard.Spec(
                "Tìm kiếm toàn cục (Spotlight)",
                "Nhảy nhanh tới bất kỳ API (#, method, path), Nhánh hoặc Role nào trong toàn bộ workspace.",
                "Ctrl+K",
                t -> Icons.search(t.info.text()),
                t -> t.info,
                null));

        actionStatusBtn.setIcon(Icons.themed(t -> Icons.strategyShield(t.muted, 12)));
        actionStatusBtn.setHorizontalTextPosition(SwingConstants.TRAILING);
        actionStatusBtn.setIconTextGap(2);
        actionStatusBtn.setFocusPainted(false);
        actionStatusBtn.setBorderPainted(false);
        actionStatusBtn.setContentAreaFilled(false);
        actionStatusBtn.setMargin(new Insets(0, 2, 0, 1));
        actionStatusBtn.setFont(Theme.current().smallBoldFont);
        actionStatusBtn.addActionListener(Ui.safe(e -> ProxyGateDialog.show(this)));
        themythofcrawl.ui.kit.RichHoverCard.install(actionStatusBtn, new themythofcrawl.ui.kit.RichHoverCard.Spec(
                "Cổng chặn Proxy (bấm để thiết lập)",
                "Chọn rõ: chặn request nào (mọi host / 1 host / host + path / nhánh), method nào, và làm gì với request bị chặn"
                        + " (Hỏi tôi trước / Giữ lại + trả 200 giả cho trình duyệt / Chặn hẳn). Có sơ đồ và ô Thử với 1 URL.",
                null,
                t -> {
                    int n = engine.proxyQueue().pendingCount();
                    boolean on = engine.isAnyActionControlActive();
                    return Icons.strategyShield(n > 0 ? t.danger.text() : (on ? t.warning.text() : t.muted), 14);
                },
                t -> {
                    int n = engine.proxyQueue().pendingCount();
                    boolean on = engine.isAnyActionControlActive();
                    return n > 0 ? t.danger : (on ? t.warning : t.neutral);
                },
                () -> engine.proxyGate().sentence()));

        actionQuickSwitch.onToken(t -> engine.proxyQueue().pendingCount() > 0 ? t.danger : t.accent);
        actionQuickSwitch.setToolTipText("Bật / Tắt Cổng chặn Proxy (BẬT = dùng thiết lập đã lưu; TẮT = tắt mọi chặn, request đi thẳng)");
        actionQuickSwitch.onChange(on -> {
            if (syncingActionSwitch) {
                return;
            }
            if (on) {
                engine.setProxyGate(engine.proxyGate().withOn(true));
            } else {
                engine.toggleQuickActionFromHeader(false);
            }
            syncHeader();
            if (settings != null) {
                settings.load();
            }
            flash(on ? "Đã BẬT cổng chặn: " + engine.proxyGate().sentence()
                    : "Đã TẮT chặn — mọi request đi thẳng lên Server");
        });

        actionControlBar = new JPanel() {
            @Override
            public void updateUI() {
                super.updateUI();
                if (MainPanel.this.engine != null) {
                    syncProxyInterceptButton();
                }
            }
        };
        actionControlBar.setLayout(new BoxLayout(actionControlBar, BoxLayout.X_AXIS));
        actionControlBar.setOpaque(true);
        actionControlBar.setPreferredSize(null);
        actionControlBar.setMinimumSize(new Dimension(Theme.current().scaled(104), h24));
        actionControlBar.setMaximumSize(new Dimension(Theme.current().scaled(160), h24));
        actionStatusBtn.setAlignmentY(Component.CENTER_ALIGNMENT);
        actionQuickSwitch.setAlignmentY(Component.CENTER_ALIGNMENT);
        actionControlBar.add(actionStatusBtn);
        actionControlBar.add(Box.createHorizontalStrut(1));
        actionControlBar.add(actionQuickSwitch);
        actionControlBar.add(Box.createHorizontalStrut(2));

        JButton displayModeBtn = new JButton();
        displayModeBtn.setIcon(Icons.themed(Icons::displayMode));
        displayModeBtn.setMargin(new Insets(0, 0, 0, 0));
        displayModeBtn.setPreferredSize(new Dimension(h24, h24));
        displayModeBtn.setMaximumSize(new Dimension(h24, h24));
        displayModeBtn.addActionListener(Ui.safe(e -> {
            UiDisplayMode next = UiDisplayMode.cycle();
            UiPrefs.setDisplayMode(uiPrefs, next);
            if (settings != null) settings.load();
            flash("Chế độ nút công cụ: " + next.label);
        }));
        themythofcrawl.ui.kit.RichHoverCard.install(displayModeBtn, new themythofcrawl.ui.kit.RichHoverCard.Spec(
                "Đổi chế độ hiển thị nút (3 trạng thái)",
                "Chuyển nhanh giữa: Chỉ Icon (+ Thẻ mô tả khi rê chuột), Icon + Chữ (mặc định), hoặc Chỉ Chữ (tối giản).",
                null,
                t -> Icons.displayMode(t.info.text(), 14),
                t -> t.info,
                () -> "Hiện tại: " + UiDisplayMode.current().label));

        options.setIcon(Icons.themed(t -> Icons.withChevron(Icons.gear(t.fg), t.fg)));
        options.setMargin(new Insets(0, 1, 0, 1));
        options.setPreferredSize(new Dimension(Theme.current().scaled(26), h24));
        options.setMaximumSize(new Dimension(Theme.current().scaled(26), h24));
        options.addActionListener(Ui.safe(e -> optionsMenu()));
        themythofcrawl.ui.kit.RichHoverCard.install(options, new themythofcrawl.ui.kit.RichHoverCard.Spec(
                "Tùy chọn nhanh & Danh mục",
                "Bật/tắt nhanh bộ lọc bắt, giữ chỗ, quản lý Loại Node & Icon, quản lý API Tag [REFRESH] và trợ giúp.",
                null,
                t -> Icons.gear(t.fg),
                t -> t.neutral,
                null));

        detach.setIcon(Icons.themed(t -> Icons.window(t.fg)));
        detach.setMargin(new Insets(0, 0, 0, 0));
        detach.setPreferredSize(new Dimension(h24, h24));
        detach.setMaximumSize(new Dimension(h24, h24));
        detach.addActionListener(Ui.safe(e -> toggleDetach()));
        themythofcrawl.ui.kit.RichHoverCard.install(detach, new themythofcrawl.ui.kit.RichHoverCard.Spec(
                "Tách cửa sổ riêng",
                "Mở toàn bộ theMythOf-JCrawl ra cửa sổ độc lập để xem toàn màn hình hoặc kéo sang màn hình thứ hai.",
                null,
                t -> Icons.window(t.fg),
                t -> t.neutral,
                () -> window != null ? "Đang mở ở cửa sổ riêng" : "Đang gắn trong tab Burp"));

        workspace.setIcon(Icons.themed(t -> Icons.withChevron(Icons.folder(t.fg), t.fg)));
        workspace.setHorizontalTextPosition(SwingConstants.LEADING);
        workspace.setMargin(new Insets(0, 1, 0, 1));
        workspace.setPreferredSize(new Dimension(Theme.current().scaled(42), h24));
        workspace.setMaximumSize(new Dimension(Theme.current().scaled(54), h24));
        workspace.addActionListener(Ui.safe(e -> workspaceMenu()));
        themythofcrawl.ui.kit.RichHoverCard.install(workspace, new themythofcrawl.ui.kit.RichHoverCard.Spec(
                "Workspace (Phiên làm việc)",
                "Tạo mới, chuyển đổi hoặc mở thư mục lưu dữ liệu crawl & roles của dự án hiện tại.",
                null,
                t -> Icons.folder(t.info.text()),
                t -> t.info,
                () -> "Đang mở: " + engine.workspaceName()));

        topChromeToggleBtn.setMargin(new Insets(0, 0, 0, 0));
        topChromeToggleBtn.setPreferredSize(new Dimension(h24, h24));
        topChromeToggleBtn.setMaximumSize(new Dimension(h24, h24));
        topChromeToggleBtn.addActionListener(Ui.safe(e -> setTopCompact(!effectiveHeaderCompact)));
        themythofcrawl.ui.kit.RichHoverCard.install(topChromeToggleBtn, new themythofcrawl.ui.kit.RichHoverCard.Spec(
                "Thu gọn / Mở rộng thanh công cụ phía trên",
                "Rút gọn các nút điều khiển vào menu thả xuống để tối ưu diện tích làm việc (hữu ích cho cả màn hình thường và chia đôi màn hình).",
                null,
                t -> effectiveHeaderCompact ? Icons.chevronDown(t.accent.text(), 12) : Icons.chevronUp(t.accent.text(), 12),
                t -> t.accent,
                () -> effectiveHeaderCompact ? "Đang ở chế độ thu gọn (bấm để mở rộng)" : "Đang hiện đầy đủ (bấm để thu gọn)"));

        compactControlsBtn.setText("Điều khiển");
        compactControlsBtn.setIcon(Icons.themed(t -> Icons.withChevron(Icons.sliders(t.fg), t.fg)));
        compactControlsBtn.setHorizontalTextPosition(SwingConstants.LEADING);
        compactControlsBtn.setMargin(new Insets(0, 5, 0, 5));
        compactControlsBtn.setPreferredSize(new Dimension(Theme.current().scaled(96), h24));
        compactControlsBtn.setMaximumSize(new Dimension(Theme.current().scaled(104), h24));
        compactControlsBtn.setToolTipText("Mở menu điều khiển nhanh (Trạng thái, Action Workbench, Tùy chọn, Workspace...)");
        compactControlsBtn.addActionListener(Ui.safe(e -> compactControlsMenu().show(compactControlsBtn, 0, compactControlsBtn.getHeight())));

        // Strict single-row horizontal BoxLayout — NEVER wraps onto 2-3 broken lines on Burp!
        headerLeftZone = new JPanel();
        headerLeftZone.setLayout(new BoxLayout(headerLeftZone, BoxLayout.X_AXIS));
        headerLeftZone.setOpaque(false);
        captureToggle.setAlignmentY(Component.CENTER_ALIGNMENT);
        sendModeToggle.setAlignmentY(Component.CENTER_ALIGNMENT);
        queue.setAlignmentY(Component.CENTER_ALIGNMENT);
        headerLeftZone.add(captureToggle);
        headerLeftZone.add(Box.createHorizontalStrut(2));
        headerLeftZone.add(sendModeToggle);
        headerLeftZone.add(Box.createHorizontalStrut(2));
        headerLeftZone.add(queue);

        // Crisp 24px height-matched vertically centered Dich prefix aligned with Doi and Breadcrumb
        JLabel destLabel = new JLabel("Đích", SwingConstants.CENTER) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setFont(t.smallBoldFont);
                setForeground(t.accent.text());
                setBackground(t.accent.fill());
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(t.accent.border()),
                        BorderFactory.createEmptyBorder(0, 4, 0, 4)));
            }
        };
        destLabel.setOpaque(true);
        destLabel.setFont(Theme.current().smallBoldFont);
        destLabel.setForeground(Theme.current().accent.text());
        destLabel.setBackground(Theme.current().accent.fill());
        destLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.current().accent.border()),
                BorderFactory.createEmptyBorder(0, 4, 0, 4)));
        destLabel.setPreferredSize(new Dimension(destLabel.getPreferredSize().width, h24));
        destLabel.setMinimumSize(new Dimension(destLabel.getPreferredSize().width, h24));
        destLabel.setMaximumSize(new Dimension(destLabel.getPreferredSize().width + 4, h24));
        destLabel.setAlignmentY(Component.CENTER_ALIGNMENT);

        JSeparator destSep = vSep();
        destSep.setAlignmentY(Component.CENTER_ALIGNMENT);
        JPanel destPrefix = new JPanel();
        destPrefix.setLayout(new BoxLayout(destPrefix, BoxLayout.X_AXIS));
        destPrefix.setOpaque(false);
        destPrefix.setPreferredSize(new Dimension(destLabel.getPreferredSize().width + 5, h24));
        destPrefix.add(destSep);
        destPrefix.add(Box.createHorizontalStrut(2));
        destPrefix.add(destLabel);
        destPrefix.add(Box.createHorizontalStrut(2));

        JPanel centerZone = new JPanel(new BorderLayout(0, 0));
        centerZone.setOpaque(false);
        centerZone.add(destPrefix, BorderLayout.WEST);
        centerZone.add(dest, BorderLayout.CENTER);

        spotlightBtn.setMaximumSize(new Dimension(h24, h24));
        status.setAlignmentY(Component.CENTER_ALIGNMENT);
        todo.setAlignmentY(Component.CENTER_ALIGNMENT);
        spotlightBtn.setAlignmentY(Component.CENTER_ALIGNMENT);
        displayModeBtn.setAlignmentY(Component.CENTER_ALIGNMENT);
        options.setAlignmentY(Component.CENTER_ALIGNMENT);
        detach.setAlignmentY(Component.CENTER_ALIGNMENT);
        workspace.setAlignmentY(Component.CENTER_ALIGNMENT);

        headerRightFull = new JPanel();
        headerRightFull.setLayout(new BoxLayout(headerRightFull, BoxLayout.X_AXIS));
        headerRightFull.setOpaque(false);
        headerRightFull.add(status);
        headerRightFull.add(Box.createHorizontalStrut(2));
        headerRightFull.add(todo);
        headerRightFull.add(Box.createHorizontalStrut(2));
        JSeparator rSep = vSep();
        rSep.setAlignmentY(Component.CENTER_ALIGNMENT);
        headerRightFull.add(rSep);
        headerRightFull.add(Box.createHorizontalStrut(2));
        headerRightFull.add(spotlightBtn);
        headerRightFull.add(Box.createHorizontalStrut(1));
        headerRightFull.add(displayModeBtn);
        headerRightFull.add(Box.createHorizontalStrut(1));
        headerRightFull.add(options);
        headerRightFull.add(Box.createHorizontalStrut(1));
        headerRightFull.add(detach);
        headerRightFull.add(Box.createHorizontalStrut(1));
        headerRightFull.add(workspace);

        headerRightCompact = new JPanel();
        headerRightCompact.setLayout(new BoxLayout(headerRightCompact, BoxLayout.X_AXIS));
        headerRightCompact.setOpaque(false);
        compactControlsBtn.setAlignmentY(Component.CENTER_ALIGNMENT);
        headerRightCompact.add(compactControlsBtn);
        headerRightCompact.setVisible(false);

        rightWrapper = new JPanel();
        rightWrapper.setLayout(new BoxLayout(rightWrapper, BoxLayout.X_AXIS));
        rightWrapper.setOpaque(false);
        actionControlBar.setAlignmentY(Component.CENTER_ALIGNMENT);
        headerRightFull.setAlignmentY(Component.CENTER_ALIGNMENT);
        headerRightCompact.setAlignmentY(Component.CENTER_ALIGNMENT);
        topChromeToggleBtn.setAlignmentY(Component.CENTER_ALIGNMENT);
        rightWrapper.add(actionControlBar);
        rightWrapper.add(Box.createHorizontalStrut(3));
        rightWrapper.add(headerRightFull);
        rightWrapper.add(headerRightCompact);
        rightWrapper.add(Box.createHorizontalStrut(2));
        rightWrapper.add(topChromeToggleBtn);

        headerRow1 = new JPanel(new BorderLayout(2, 0));
        headerRow1.setOpaque(false);
        headerRow1.add(headerLeftZone, BorderLayout.WEST);
        headerRow1.add(centerZone, BorderLayout.CENTER);
        headerRow1.add(rightWrapper, BorderLayout.EAST);

        headerDrawerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        headerDrawerRow.setOpaque(false);
        headerDrawerRow.setVisible(false);

        JPanel top = new JPanel(new BorderLayout(0, 2)) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, t.border),
                        BorderFactory.createEmptyBorder(3, 4, 3, 4)));
            }
        };
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.current().border),
                BorderFactory.createEmptyBorder(3, 4, 3, 4)));
        top.add(headerRow1, BorderLayout.NORTH);
        top.add(headerDrawerRow, BorderLayout.SOUTH);
        return top;
    }

    private JPopupMenu compactControlsMenu() {
        JPopupMenu m = new JPopupMenu();
        Engine.Stats st = engine.stats();
        SendDispatcher.Snapshot q = engine.queue();
        int waiting = q.queued() + q.inFlight();
        m.add(Ui.item("Trạng thái: " + status.getText() + " · " + todo.getText()
                + (waiting > 0 ? " · Đợi: " + waiting : ""), Icons::api, this::statusMenu));
        if (st.pending() > 0) {
            m.add(Ui.item("Mở danh sách chờ xử lý (" + st.pending() + ")", Icons::hourglass, this::todoMenu));
        }
        if (waiting > 0) {
            m.add(Ui.item("Hủy hàng đợi gửi (" + waiting + ")", Icons::stop, () -> engine.cancelPending("Đã hủy")));
        }
        m.addSeparator();
        m.add(Ui.item("Tìm kiếm toàn cục (Spotlight · Ctrl+K)", Icons::search, () -> spotlight.open()));
        int pendingIntercept = engine.proxyQueue().pendingCount();
        boolean waitOn = engine.actionEngine().isInterceptWaitEnabled();
        String waitLabel = pendingIntercept > 0
                ? "Hàng chờ Chặn Proxy trực tiếp (" + pendingIntercept + " đang đợi)..."
                : (waitOn ? "Hàng chờ Chặn Proxy trực tiếp (Đang BẬT)..." : "Hàng chờ Chặn Proxy trực tiếp (Đang TẮT)...");
        m.add(Ui.item(waitLabel,
                t -> Icons.strategyShield(pendingIntercept > 0 ? t.danger.text() : (waitOn ? t.success.text() : t.fg), 14),
                () -> ProxyInterceptDialog.showFloating(this)));
        int blockRules = engine.actionEngine().activeBlockRuleCount();
        m.add(Ui.item("Action Workbench & Luật Chặn Proxy" + (blockRules > 0 ? " (" + blockRules + " luật bật)" : "") + "...",
                Icons::workbench, () -> ActionWorkbenchDialog.show(root, engine,
                        api != null ? api.currentGroup() : null, null, ActionWorkbenchDialog.TAB_STRATEGY, () -> {
                            syncHeader();
                            if (settings != null) settings.load();
                        })));
        m.add(Ui.item("Chế độ nút công cụ: " + UiDisplayMode.current().label + " (Đổi)", Icons::displayMode, () -> {
            UiDisplayMode next = UiDisplayMode.cycle();
            UiPrefs.setDisplayMode(uiPrefs, next);
            if (settings != null) settings.load();
            flash("Chế độ nút công cụ: " + next.label);
        }));
        m.add(Ui.item("Tùy chọn nhanh & Danh mục...", Icons::gear, this::optionsMenu));
        m.add(Ui.item("Workspace: " + engine.workspaceName() + "...", Icons::folder, this::workspaceMenu));
        m.add(Ui.item(window != null ? "Gắn lại vào tab Burp" : "Tách cửa sổ riêng", Icons::window, this::toggleDetach));
        m.addSeparator();
        m.add(Ui.item(effectiveHeaderCompact ? "Mở rộng thanh công cụ phía trên" : "Thu gọn thanh công cụ phía trên",
                t -> effectiveHeaderCompact ? Icons.chevronDown(t.fg, 12) : Icons.chevronUp(t.fg, 12),
                () -> setTopCompact(!effectiveHeaderCompact)));
        return m;
    }

    private boolean narrowExpanded;

    boolean isHeaderCompact() {
        return effectiveHeaderCompact;
    }

    void setTopCompact(boolean compact) {
        int w = root.getWidth() > 0 ? root.getWidth() : lastChromeWidth;
        if (w < 1140) {
            this.narrowExpanded = !compact;
        } else {
            this.userTopCompact = compact;
            UiPrefs.setTopCompact(uiPrefs, compact);
        }
        updateResponsiveChrome(w);
    }

    void updateResponsiveChrome(int width) {
        if (width > 0) {
            lastChromeWidth = width;
        }
        int w = width > 0 ? width : lastChromeWidth;
        boolean narrow = w < 1140;
        boolean compact = narrow ? !narrowExpanded : userTopCompact;
        effectiveHeaderCompact = compact;

        if (headerRightFull != null && headerRightCompact != null) {
            if (!narrow && !userTopCompact) {
                // Full wide mode: single guaranteed horizontal row (zero wrapping)
                headerDrawerRow.setVisible(false);
                if (queue.getParent() != headerLeftZone) {
                    headerLeftZone.add(queue);
                }
                if (headerRightFull.getParent() == headerDrawerRow) {
                    headerDrawerRow.remove(headerRightFull);
                    rightWrapper.add(headerRightFull, 2);
                }
                headerRightFull.setVisible(true);
                headerRightCompact.setVisible(false);
                dest.setMaxSegments(2);
                dest.setCellWidth(36);
            } else if (narrow && narrowExpanded) {
                // Split-screen explicitly expanded: cleanly show drawer row 2 (zero collision!)
                headerRightCompact.setVisible(false);
                headerRightFull.setVisible(true);
                if (queue.getParent() == headerLeftZone) {
                    headerLeftZone.remove(queue);
                }
                if (headerRightFull.getParent() != headerDrawerRow) {
                    if (headerRightFull.getParent() != null) {
                        headerRightFull.getParent().remove(headerRightFull);
                    }
                    headerDrawerRow.removeAll();
                    headerDrawerRow.add(queue);
                    headerDrawerRow.add(headerRightFull);
                }
                headerDrawerRow.setVisible(true);
                dest.setMaxSegments(1);
                dest.setCellWidth(Math.max(48, Math.min(72, (w - 360) / 2)));
            } else {
                // Collapsed compact mode (split-screen default OR user collapsed): crisp 1-row bar keeping Action unit visible!
                headerDrawerRow.setVisible(false);
                if (queue.getParent() != headerLeftZone && !narrow) {
                    headerLeftZone.add(queue);
                } else if (narrow && queue.getParent() == headerLeftZone) {
                    headerLeftZone.remove(queue);
                }
                headerRightFull.setVisible(false);
                headerRightCompact.setVisible(true);
                dest.setMaxSegments(narrow ? 1 : 2);
                dest.setCellWidth(narrow ? 54 : 68);
            }
            topChromeToggleBtn.setIcon(Icons.themed(t -> effectiveHeaderCompact
                    ? Icons.chevronDown(t.fg, 12) : Icons.chevronUp(t.fg, 12)));
            headerPanel.revalidate();
            headerPanel.repaint();
        }
        if (api != null) {
            api.applyResponsiveChrome(effectiveHeaderCompact, w);
        }
    }

    void update(java.util.function.Consumer<Config> f) {
        Config c = engine.config().copy();
        f.accept(c);
        String err = engine.applyConfig(c);
        if (err != null && c.apiRegexEnabled) {
            flash("Regex API lỗi: " + err);
        }
        syncHeader();
        settings.load();
    }

    private void setSendMode(boolean on) {
        if (on == engine.config().autoSend) {
            return;
        }
        update(c -> c.autoSend = on);
        if (on) {
            int n = engine.sendAllUnsent();
            engine.log("Bật GỬI: bắt xong gửi luôn" + (n > 0 ? "; xếp hàng " + n + " request chưa gửi." : "."), null);
        } else {
            engine.log("CHỈ CRAWL: chỉ ghi lại API, không gửi.", null);
        }
    }

    void syncProxyInterceptButton() {
        if (actionControlBar == null) {
            return;
        }
        Theme th = Theme.current();
        int h24 = th.scaled(24);
        int pending = engine.proxyQueue().pendingCount();
        boolean active = engine.isAnyActionControlActive();
        String label = engine.proxyGateHeaderLabel();

        actionStatusBtn.setText(label);
        actionStatusBtn.setFont(th.smallBoldFont);

        if (pending > 0) {
            actionControlBar.setBackground(th.danger.fill());
            actionControlBar.setBorder(BorderFactory.createLineBorder(th.danger.border()));
            actionStatusBtn.setForeground(th.danger.text());
            actionStatusBtn.setIcon(Icons.themed(t -> Icons.strategyShield(t.danger.text(), 12)));
        } else if (active) {
            actionControlBar.setBackground(th.warning.fill());
            actionControlBar.setBorder(BorderFactory.createLineBorder(th.warning.border()));
            actionStatusBtn.setForeground(th.warning.text());
            actionStatusBtn.setIcon(Icons.themed(t -> Icons.strategyShield(t.warning.text(), 12)));
        } else {
            actionControlBar.setBackground(th.surfaceAlt);
            actionControlBar.setBorder(BorderFactory.createLineBorder(th.border));
            actionStatusBtn.setForeground(th.fg);
            actionStatusBtn.setIcon(Icons.themed(t -> Icons.strategyShield(t.muted, 12)));
        }

        syncingActionSwitch = true;
        try {
            actionQuickSwitch.setSelected(active);
            actionQuickSwitch.onToken(t -> pending > 0 ? t.danger : (active ? t.warning : t.accent));
        } finally {
            syncingActionSwitch = false;
        }

        // Compute exact compact natural width so text never clips AND header stays <= 1100px
        actionStatusBtn.setPreferredSize(null);
        Dimension btnPref = actionStatusBtn.getPreferredSize();
        int btnW = Math.max(th.scaled(70), Math.min(th.scaled(124), btnPref.width + th.scaled(2)));
        actionStatusBtn.setPreferredSize(new Dimension(btnW, h24 - 2));
        actionStatusBtn.setMaximumSize(new Dimension(btnW, h24 - 2));
        Dimension swPref = actionQuickSwitch.getPreferredSize();
        int totalW = btnW + swPref.width + th.scaled(6);
        actionControlBar.setPreferredSize(new Dimension(totalW, h24));
        actionControlBar.setMinimumSize(new Dimension(totalW, h24));
        actionControlBar.setMaximumSize(new Dimension(totalW, h24));
        actionControlBar.revalidate();
        actionControlBar.repaint();
    }

    void syncHeader() {
        Theme th = Theme.current();
        Config c = engine.config();
        captureToggle.setSelectedIndex(c.captureEnabled ? 1 : 0);
        sendModeToggle.setSelectedIndex(c.autoSend ? 1 : 0);
        syncProxyInterceptButton();
        if (treeTab != null) {
            treeTab.syncHeaderBreadcrumb();
        }
        ProxyInterceptDialog.refreshIfOpen(this);

        Branch b = engine.activeBranch();
        String name = b.name();
        if (!dest.path().equals(name)) {
            dest.setPath(name);
            dest.setToolTipText("Request mới sẽ vào: " + name + "  (bấm ô cấp để chọn, bấm nút mũi tên mở menu, bấm + thêm mục con)");
        } else {
            dest.refresh();
        }

        Engine.Stats st = engine.stats();
        todo.setText("Xử lý " + st.pending());
        todo.setActive(st.pending() > 0);
        todo.setTrailingDot(st.dupWaiting() > 0 ? t -> t.dup.base() : null);
        todo.setToolTipText(st.pending() > 0
                ? st.pending() + " API đang chờ xác nhận" + (st.dupWaiting() > 0 ? " (" + st.dupWaiting() + " API trùng)" : "")
                : "Không có API chờ xác nhận");

        SendDispatcher.Snapshot q = engine.queue();
        int waiting = q.queued() + q.inFlight();
        if (waiting > queuePeak) {
            queuePeak = waiting;
        } else if (waiting == 0) {
            queuePeak = 0;
        }
        queue.setText(q.waitSeconds() > 0.5 ? String.format("Gửi %d (%.0fs)", waiting, q.waitSeconds())
                : "Gửi " + waiting);
        queue.setEnabled(waiting > 0);
        queue.setActive(waiting > 0);
        queue.setToken(waiting > 0 ? (q.waitSeconds() > 0.5 ? t -> t.critical : t -> t.info) : t -> t.neutral);
        queue.setIcon(waiting > 0 ? t -> Icons.bolt(q.waitSeconds() > 0.5 ? t.critical.text() : t.info.text())
                : t -> Icons.clock(t.muted));
        if (waiting > 0 && queuePeak > 0) {
            double doneRatio = Math.max(0.08, Math.min(1.0, (double) Math.max(1, queuePeak - q.queued()) / queuePeak));
            queue.setProgress(doneRatio);
            queue.setToolTipText("Đang gửi " + q.inFlight() + ", chờ " + q.queued() + " — bấm để hủy hàng đợi");
        } else {
            queue.setProgress(-1);
            queue.setToolTipText("Hàng đợi gửi đang trống");
        }

        String diag = Diagnostics.summary();
        String wsName = engine.workspaceName();
        workspace.setText(wsName.length() <= 8 ? wsName : wsName.substring(0, 7) + "..");
        workspace.setToolTipText(diag + " | Workspace: " + engine.workspaceLocation());

        rolesChip.setText("Roles " + engine.roles().enabledCount() + "/" + engine.roles().count());
        branchChip.setText("Nhánh " + st.branches());
        status.setText("API " + st.apis() + " · " + st.branches() + " nhánh");
        status.setToolTipText(st.apis() + " API · " + st.branches() + " nhánh · "
                + engine.roles().enabledCount() + "/" + engine.roles().count()
                + " Roles — Bấm để xem thống kê chi tiết & nhảy nhanh · " + diag);

        infoChip.setVisible(st.info() > 0);
        if (st.info() > 0) {
            infoChip.setText(st.info() + " lệch");
        }
        hiddenChip.setVisible(st.hidden() > 0);
        if (st.hidden() > 0) {
            hiddenChip.setText(st.hidden() + " ẩn");
        }
        int sk = engine.skippedCount();
        skippedChip.setVisible(sk > 0);
        if (sk > 0) {
            skippedChip.setText("Bỏ qua " + sk);
            skippedChip.setToolTipText("Lý do gần nhất: " + engine.lastSkipReason());
        }
        boolean hasTargets = engine.targets() != null;
        excelChip.setVisible(hasTargets);
        if (hasTargets) {
            int seen = engine.hits().rowsSeen();
            int total = Math.max(1, engine.targets().size());
            excelChip.setText("Excel " + seen + "/" + engine.targets().size());
            excelChip.setProgress((double) seen / total);
            excelChip.setToken(seen >= engine.targets().size() ? t -> t.success : t -> t.info);
            excelChip.setIcon(seen >= engine.targets().size()
                    ? t -> Icons.excel(t.success.text(), 12) : t -> Icons.excel(t.info.text(), 12));
        }

        diagBadge.setFont(th.smallFont);
        diagBadge.setForeground(th.muted);
        diagBadge.setText(Text.EXT_NAME + " " + Diagnostics.VERSION + " · " + Diagnostics.lafName() + (th.dark ? " (dark)" : ""));
        diagBadge.setToolTipText(diag);
    }

    public void flash(String text) {
        toastPane.showToast(text);
    }

    private void destinationMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Chọn đích từ danh sách...", Icons::folder, this::pickDestination));
        m.add(Ui.item("Lấy nhánh đang chọn trong cây", Icons::branch, this::destinationFromTree));
        m.add(Ui.item("Thêm mục con (Folder / Link / Button / Icon...)...", Icons::plus,
                () -> addSubfolder(engine.activeBranch().name())));
        m.add(Ui.item("Thêm mục cùng cấp (action / nút tiếp theo)...", Icons::plus, this::nextSibling));
        m.add(Ui.item("Lên 1 cấp", Icons::arrowUp,
                () -> setDestination(themythofcrawl.core.Hierarchy.parent(engine.activeBranch().name()))));
        m.addSeparator();
        m.add(Ui.item("Đổi loại & Icon của đích hiện tại...", Icons::sliders,
                () -> editNodeTypeDialog(engine.activeBranch().name())));
        m.add(Ui.item("Đổi tên / sửa đường dẫn đích...", Icons::edit, () -> {
            Branch b = engine.activeBranch();
            String n = ask("Sửa đường dẫn của đích (các API trong nhánh này đổi theo):", b.name());
            if (n != null && !n.isBlank()) {
                engine.renameBranch(b.id(), n);
            }
        }));
        JMenu more = new JMenu("Khác");
        more.add(Ui.item("Quản lý các cấp của đích (thêm / xóa / sửa / đổi thứ tự)...", Icons::gear,
                () -> LevelsDialog.show(root, engine)));
        more.add(Ui.item("Quản lý danh mục Loại Node & Icon...", Icons::sliders, this::openNodeTypeManager));
        more.add(Ui.item("Xóa nhánh đích...", t -> Icons.trash(t.danger.text()),
                () -> deleteBranchDialog(engine.activeBranch().name())));
        m.add(more);
        List<String> recent = new ArrayList<>(engine.recentDestinations());
        recent.remove(engine.activeBranch().name());
        if (!recent.isEmpty()) {
            m.addSeparator();
            m.add(Ui.item("Gần đây:", false, () -> { }));
            for (String p : recent.subList(0, Math.min(8, recent.size()))) {
                m.add(Ui.item("   " + p, t -> Icons.nodeIcon(engine.nodeTypeOf(p), t, 12), () -> setDestination(p)));
            }
        }
        m.show(dest, 0, dest.getHeight());
    }

    void setDestination(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        engine.setDestination(path);
        engine.teamSync().updateLocalPresence(engine.activeBranch().name(), "");
        syncHeader();
        flash("Request mới sẽ vào: " + engine.activeBranch().name());
    }

    void pickDestination() {
        String p = PathPicker.pick(root, "Chọn đích cho request mới", engine.knownPaths(), engine.activeBranch().name(),
                "Mọi request mới sẽ vào đường dẫn này cho tới khi bạn chọn lại.");
        setDestination(p);
    }

    void destinationFromTree() {
        String p = selectedBranch();
        if (p == null) {
            info("Chọn 1 nhánh trong cây trước.");
            return;
        }
        setDestination(p);
    }

    void nextSibling() {
        NodeEditorDialog.AddResult res = NodeEditorDialog.showAdd(root, engine, engine.activeBranch().name(), true, true);
        if (res == null) {
            return;
        }
        if (res.setAsDestination()) {
            engine.setDestination(res.fullPath());
        } else {
            engine.findOrCreateBranch(res.fullPath(), false);
        }
        engine.setPathNodeType(res.fullPath(), res.typeId());
        syncHeader();
        tick(true);
        flash((res.setAsDestination() ? "Đích mới: " : "Đã tạo nhánh: ") + res.fullPath());
    }

    private void todoMenu() {
        List<ApiGroup> pending = engine.pendingGroups();
        List<ApiGroup> dups = pending.stream().filter(ApiGroup::dupPending).toList();
        int n = pending.size();
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Mở danh sách Cần xử lý (giải thích + nút cho từng API)...", Icons::compass, () -> showAudit(0)));
        m.addSeparator();
        m.add(Ui.item("Giữ chỗ hiện tại cho cả " + n + " API", t -> Icons.check(t.success.text()), n > 0,
                () -> engine.confirm(engine.pendingGroups(), "xác nhận tất cả")));
        m.add(Ui.item("Chuyển cả " + n + " API sang path khác...", Icons::move, n > 0, () -> moveDialog(engine.pendingGroups())));
        m.add(Ui.item("Không giữ " + dups.size() + " API trùng (ẩn, vẫn lưu ở Audit)", Icons::hide, !dups.isEmpty(),
                () -> engine.hide(engine.pendingGroups().stream().filter(ApiGroup::dupPending).toList(), "không giữ")));
        m.add(Ui.item("Lọc bảng API: chỉ API đang chờ", Icons::search, n > 0, () -> {
            selectTab(api.panel);
            api.filterPending();
        }));
        m.addSeparator();
        m.add(Ui.item("Xem API trùng & đang ẩn (" + engine.hiddenGroups().size() + " ẩn)", Icons::hide, () -> showAudit(2)));
        m.add(Ui.item("Xóa " + n + " API đang chờ...", t -> Icons.trash(t.danger.text()), n > 0, () -> {
            if (confirmDialog("Xóa " + engine.pendingGroups().size() + " API đang chờ xác nhận?")) {
                engine.remove(engine.pendingGroups(), "bỏ API chờ xác nhận");
            }
        }));
        m.show(todo, 0, todo.getHeight());
    }

    private void optionsMenu() {
        Config c = engine.config();
        JPopupMenu m = new JPopupMenu();
        Object[][] toggles = {
            {"In-Scope Only (Chỉ bắt API trong Target Scope)", c.inScopeOnly, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.inScopeOnly = v},
            {"API Path Regex (Chỉ bắt URL khớp Regex API)", c.apiRegexEnabled, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.apiRegexEnabled = v},
            null,
            {"Cho phép Tự động Gửi ngầm (Auto-Send 1..N Roles)", c.autoSend, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.autoSend = v},
            {"Tự động đẩy sang Burp Repeater khi bắt", c.autoRepeater, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.autoRepeater = v},
            null,
            {"Hỏi xác nhận vị trí API mới (màu xám)", c.confirmNewApis, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.confirmNewApis = v},
            {"API đã xóa quay về chỗ cũ (giữ chỗ)", c.restoreKnownPlace, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.restoreKnownPlace = v},
            {"Gặp API trùng ở chỗ mới: hỏi giữ thêm bản (màu tím)", c.dupNewPlace, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.dupNewPlace = v},
            {"Hiện API đã xóa (giữ chỗ) trong cây", c.showGhosts, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.showGhosts = v},
            {"Tự phát hiện API GET-sau-hành-động (gợi ý Tag REFRESH)", c.autoDetectRefresh, (java.util.function.BiConsumer<Config, Boolean>) (x, v) -> x.autoDetectRefresh = v},
        };
        for (Object[] t : toggles) {
            if (t == null) {
                m.addSeparator();
                continue;
            }
            JCheckBoxMenuItem it = new JCheckBoxMenuItem((String) t[0], (Boolean) t[1]);
            @SuppressWarnings("unchecked")
            java.util.function.BiConsumer<Config, Boolean> set = (java.util.function.BiConsumer<Config, Boolean>) t[2];
            it.addActionListener(Ui.safe(e -> update(x -> set.accept(x, it.isSelected()))));
            m.add(it);
        }
        m.addSeparator();
        m.add(Ui.item("Quản lý Loại Node & Icon (Folder / Link / Button...)...", Icons::sliders, this::openNodeTypeManager));
        m.add(Ui.item("Quản lý API Tag & Chính sách REFRESH...", Icons::tag, this::openTagManager));
        JMenu helpMenu = new JMenu("Hướng dẫn & Trợ giúp");
        helpMenu.add(Ui.item("Bắt & gửi (Capture / Auto-Send)", Icons::info, () -> Ui.popover(options, "Bắt & gửi", Help.CAPTURE)));
        helpMenu.add(Ui.item("Đích của request mới (Sub-folder)", Icons::info, () -> Ui.popover(options, "Đích của request mới", Help.DEST)));
        helpMenu.add(Ui.item("Cần xử lý & Giữ chỗ", Icons::info, () -> Ui.popover(options, "Cần xử lý", Help.TODO)));
        helpMenu.add(Ui.item("Ý nghĩa Màu & Nhãn Audit", Icons::info, () -> Ui.popover(options, "Màu & nhãn", Help.COLORS)));
        helpMenu.add(Ui.item("Workspace & Lưu trữ", Icons::info, () -> Ui.popover(options, "Workspace", Help.WORKSPACE)));
        m.add(helpMenu);
        m.add(Ui.item("Mở tab Cấu hình chi tiết...", Icons::gear, () -> selectTab(settings.panel)));
        m.add(Ui.item("Reset Dedup Cache (Cho phép bắt lại API cũ)", Icons::refresh, engine::resetDedup));
        m.addSeparator();
        m.add(Ui.item(diagBadge.getText(), false, () -> { }));
        m.show(options, 0, options.getHeight());
    }

    private void statusMenu() {
        Engine.Stats st = engine.stats();
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Tổng API đã bắt: " + st.apis() + "  (mở tab API)", Icons::api, () -> selectTab(api.panel)));
        m.add(Ui.item("Nhánh Sub-folder: " + st.branches() + "  (mở tab Lịch sử)", Icons::branch, () -> selectTab(history.panel)));
        m.add(Ui.item("Roles đang bật: " + engine.roles().enabledCount() + "/" + engine.roles().count() + "  (mở tab Roles)",
                t -> Icons.role(t.fg, 12), () -> selectTab(roles.panel)));
        if (st.info() > 0) {
            m.add(Ui.item("API lệch / lỗi quyền: " + st.info() + "  (lọc bảng API)", t -> Icons.warn(t.warning.text(), 12), () -> {
                selectTab(api.panel);
                api.filterLevel(AuditLevel.INFO);
            }));
        }
        if (st.hidden() > 0) {
            m.add(Ui.item("API trùng đang ẩn: " + st.hidden() + "  (mở Audit > Trùng & ẩn)", t -> Icons.hide(t.muted, 12),
                    () -> showAudit(2)));
        }
        int sk = engine.skippedCount();
        if (sk > 0) {
            m.add(Ui.item("Đã bỏ qua: " + sk + " (" + engine.lastSkipReason() + ")", t -> Icons.filter(t.muted, 12),
                    () -> selectTab(log.panel)));
        }
        if (engine.targets() != null) {
            int seen = engine.hits().rowsSeen();
            m.add(Ui.item("Độ phủ Excel: " + seen + "/" + engine.targets().size() + "  (mở tab Excel)",
                    t -> Icons.excel(t.info.text(), 12), () -> selectTab(excel.panel)));
        }
        m.addSeparator();
        m.add(Ui.item("Bảng chú giải Màu & Nhãn Audit...", Icons::info, () -> Ui.popover(status, "Màu & nhãn", Help.COLORS)));
        m.add(Ui.item(diagBadge.getText(), false, () -> { }));
        m.show(status, 0, status.getHeight());
    }

    // =================================================================== workspace
    private void workspaceMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Đang mở: " + engine.workspaceName() + " - " + engine.workspaceLocation(), false, () -> { }));
        m.addSeparator();
        m.add(Ui.item("Workspace mới...", Icons::plus, () -> {
            String n = ask("Tên workspace mới (thuộc project '" + host.projectName() + "'):", "Workspace mới");
            if (n != null && !n.isBlank()) {
                openWorkspace(workspaces.create(host.projectName(), n.trim()));
            }
        }));
        JMenu open = new JMenu("Mở workspace khác");
        open.setIcon(Icons.themed(t -> Icons.folder(t.fg)));
        try {
            for (Workspaces.Info i : workspaces.list()) {
                open.add(Ui.item(i.project() + " / " + i.name() + "  (" + i.apis() + " API)", () -> openWorkspace(workspaces.open(i.dir()))));
            }
        } catch (RuntimeException e) {
            open.add(Ui.item("(không đọc được thư mục: " + e.getMessage() + ")", false, () -> { }));
        }
        if (open.getItemCount() == 0) {
            open.add(Ui.item("(chưa có)", false, () -> { }));
        }
        m.add(open);
        m.add(Ui.item("Mở thư mục workspace trong máy", Icons::folder, () -> {
            try {
                Desktop.getDesktop().open(new File(engine.workspaceLocation()));
            } catch (Exception e) {
                Ui.copyToClipboard(engine.workspaceLocation());
                flash("Đã copy đường dẫn workspace: " + engine.workspaceLocation());
            }
        }));
        m.add(Ui.item("Đổi thư mục gốc (nơi tạo theMythOfCrawl_workspace)...", Icons::pin, () -> {
            JFileChooser fc = new JFileChooser(workspaces.root().toFile());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
                workspaces.setRoot(fc.getSelectedFile().toPath());
                openWorkspace(workspaces.start(projectId, host.projectName()));
            }
        }));
        m.add(Ui.item("Xóa một workspace khác...", t -> Icons.trash(t.danger.text()), () -> {
            List<Workspaces.Info> list = workspaces.list().stream()
                    .filter(i -> !i.dir().toAbsolutePath().toString().equals(engine.workspaceLocation())).toList();
            if (list.isEmpty()) {
                info("Không có workspace nào khác.");
                return;
            }
            Object pick = JOptionPane.showInputDialog(Ui.ownerWindow(root), "Xóa workspace (không xóa được workspace đang mở):", Text.EXT_NAME,
                    JOptionPane.WARNING_MESSAGE, null, list.stream().map(i -> i.project() + " / " + i.name()).toArray(), null);
            if (pick != null) {
                Workspaces.Info i = list.stream().filter(x -> (x.project() + " / " + x.name()).equals(pick)).findFirst().get();
                if (confirmDialog("Xóa vĩnh viễn workspace '" + pick + "' (" + i.apis() + " API)?")) {
                    workspaces.delete(i.dir());
                    flash("Đã xóa workspace " + pick);
                }
            }
        }));
        m.show(workspace, 0, workspace.getHeight());
    }

    private void openWorkspace(FileStorage st) {
        engine.open(st);
        workspaces.remember(projectId, st);
        api.reloadAll();
        tick(true);
        flash("Đang dùng workspace " + st.name() + " (" + engine.groups().size() + " API)");
    }

    // =================================================================== detach
    void toggleDetach() {
        if (window != null) {
            attach();
            return;
        }
        window = new JFrame(Text.EXT_NAME);
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                attach();
            }
        });
        Dimension size = root.getSize();
        root.remove(toastPane);
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 60));
        holder.add(new JLabel(Text.EXT_NAME + " đang mở ở cửa sổ riêng."));
        holder.add(Ui.button("Đưa cửa sổ lên trước", () -> {
            window.setState(JFrame.NORMAL);
            window.toFront();
        }));
        holder.add(Ui.button("Gắn lại vào tab", this::attach));
        root.add(holder, BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
        window.getContentPane().add(toastPane);
        Theme.track(window);
        window.setSize(new Dimension(Math.max(1100, size.width), Math.max(760, size.height)));
        window.setLocationRelativeTo(null);
        detach.setText("Gắn lại");
        window.setVisible(true);
    }

    void attach() {
        if (window == null) {
            return;
        }
        JFrame w = window;
        window = null;
        w.getContentPane().removeAll();
        w.dispose();
        root.removeAll();
        root.add(toastPane, BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
        detach.setText("");
    }

    public boolean detached() {
        return window != null;
    }

    // =================================================================== the UI tick
    void tick(boolean force) {
        Changes.Batch b = engine.changes().drain();
        if (!b.log().isEmpty()) {
            log.append(b.log());
        }
        if (!b.added().isEmpty() || !b.dirty().isEmpty() || b.structure()) {
            api.apply(b);
        }
        if (b.structure() || !b.added().isEmpty()) {
            treeTab.stale = true;
            history.stale = true;
            excel.stale = true;
            audit.stale = true;
            log.stale = true;
            matrix.stale = true;
            bugs.stale = true;
        }
        if (!b.dirty().isEmpty()) {
            treeTab.stale = true;
            treeTab.dirty(b.dirty());
            excel.stale |= b.dirty().stream().anyMatch(g -> g.target() != null);
            audit.stale = true;
            matrix.stale = true;
            bugs.stale = true;
        }
        api.refreshVisible(force);
        treeTab.refreshIfVisible();
        history.refreshIfVisible();
        excel.refreshIfVisible();
        audit.refreshIfVisible();
        log.refreshIfVisible();
        matrix.refreshIfVisible();
        bugs.refreshIfVisible();
        boolean queueActive = queuePeak > 0 || engine.queue().queued() + engine.queue().inFlight() > 0;
        if (b.status() || b.structure() || force || !b.added().isEmpty()) {
            syncHeader();
            roles.refreshCounts();
        } else if (queueActive) {
            syncHeader();
        }
        if (engine.config().captureEnabled) {
            if (captureToggle.isShowing() && tickCount++ % 2 == 0) {
                pulseDim = !pulseDim;
                captureToggle.repaint();
            }
        } else if (pulseDim) {
            pulseDim = false;
            captureToggle.repaint();
        }
    }

    // =================================================================== scope (what "selected" means)
    void useSource(Object src) {
        source = src;
    }

    Scope scope() {
        List<ApiGroup> fromTable = api.selectedGroups();
        if (!fromTable.isEmpty() && source == api.table) {
            return new Scope(fromTable, fromTable.size() > 1 ? fromTable.size() + " API đang chọn" : Text.apiLabel(fromTable.get(0).gid()));
        }
        BranchTree t = source instanceof BranchTree bt ? bt : api.tree;
        if (t.selectionCount() > 1) {
            List<ApiGroup> many = t.selectedGroups();
            return new Scope(many, t.selectionCount() + " mục chọn trong cây (" + many.size() + " API)");
        }
        Node n = t.selectedNode();
        if (n != null) {
            switch (n.kind) {
                case ROOT:
                    return new Scope(engine.groups(), "Tất cả API");
                case FOLDER:
                    return new Scope(engine.groupsUnder((String) n.payload), "Nhánh \"" + n.payload + "\"");
                case APIS:
                    return new Scope(BranchTree.directGroups(n), "API trực tiếp của nhánh \"" + n.payload + "\"");
                case CLUSTER: {
                    String cname = n.payload instanceof themythofcrawl.model.GroupCluster cl ? cl.name() : "Cụm";
                    return new Scope(BranchTree.directGroups(n), "Cụm \"" + cname + "\"");
                }
                default:
                    ApiGroup g = BranchTree.groupOf(n);
                    if (g != null) {
                        return new Scope(List.of(g), Text.apiLabel(g.gid()));
                    }
            }
        }
        if (!fromTable.isEmpty()) {
            return new Scope(fromTable, fromTable.size() + " API đang chọn");
        }
        return new Scope(List.of(), "");
    }

    String selectedBranch() {
        BranchTree t = source instanceof BranchTree bt ? bt : api.tree;
        String b = t.selectedBranch();
        if (b == null) {
            Branch br = api.selectedBranchRow();
            if (br != null) {
                return br.name();
            }
        }
        return b;
    }

    // =================================================================== shared actions
    void send(Collection<ApiGroup> gs) {
        for (ApiGroup g : gs) {
            engine.replay(g, engine.defaultTargets(g, false));
        }
        if (!gs.isEmpty()) {
            flash("Xếp hàng gửi " + gs.size() + " API");
        } else {
            flash("Không có API nào để gửi — chọn API (bảng / cây) hoặc 1 nhánh trước.");
        }
    }

    /** Push with the Repeater mode chosen in the 'Burp Repeater' menu. */
    void push(ApiGroup g) {
        if (g == null) {
            return;
        }
        String title;
        if (repeaterMode == 1) {
            List<RoleResult> flagged = g.results().stream().filter(r -> themythofcrawl.core.Audit.level(g, r).flagged()).toList();
            title = engine.toRepeater(g, flagged.isEmpty() ? engine.defaultTargets(g, false) : flagged, true);
            Ui.copyToClipboard(title);
        } else if (repeaterMode == 0) {
            title = engine.toRepeater(g, engine.defaultTargets(g, false), true);
            Ui.copyToClipboard(title);
            flash("Đã đẩy sang Repeater (copy tên Group '" + title + "')");
        } else {
            engine.toRepeater(g, engine.defaultTargets(g, false), false);
        }
    }

    void push(Collection<ApiGroup> gs) {
        if (gs == null || gs.isEmpty()) {
            flash("Không có API nào để đẩy sang Repeater — chọn API (bảng / cây) hoặc 1 nhánh trước.");
            return;
        }
        for (ApiGroup g : gs) {
            push(g);
        }
    }

    void pushDialog(boolean send, List<ApiGroup> gs, String label) {
        if (gs == null) {
            Scope s = scope();
            gs = s.groups();
            label = s.label();
        }
        if (gs.isEmpty()) {
            info("Chọn API (bảng) hoặc nhánh / API (cây) trước.");
            return;
        }
        PushDialog.Choice c = PushDialog.show(root, gs, label, pushMemory, send);
        if (c == null) {
            return;
        }
        pushMemory = c;
        int total = 0;
        int apis = 0;
        for (ApiGroup g : gs) {
            List<RoleResult> t = Engine.selectTargets(g, c.original(), c.roles(), c.onlyFlagged());
            if (t.isEmpty()) {
                continue;
            }
            apis++;
            total += t.size();
            if (c.send()) {
                engine.replay(g, t);
            } else {
                String title = engine.toRepeater(g, t, c.compact());
                if (c.compact() && gs.size() == 1) {
                    Ui.copyToClipboard(title);
                }
            }
        }
        List<String> parts = new ArrayList<>();
        if (c.original()) {
            parts.add("API gốc");
        }
        parts.addAll(c.roles().stream().sorted().toList());
        engine.log(label + ": " + (c.send() ? "gửi " + total + " request" : "đẩy " + total + " tab sang Repeater") + " cho " + apis
                + " API (" + (parts.isEmpty() ? "-" : String.join(", ", parts)) + (c.onlyFlagged() ? ", chỉ role lỗi / lệch" : "") + ").", null);
    }

    void copy(Collection<ApiGroup> gs) {
        if (gs.isEmpty()) {
            info("Chọn API (bảng) hoặc nhánh / API (cây) trước.");
            return;
        }
        String text = engine.copy(gs);
        Ui.copyToClipboard(text);
        flash("Đã copy " + gs.size() + " dòng: " + text.split("\n")[0]);
    }

    void copyBranchPath() {
        String b = selectedBranch();
        if (b != null) {
            Ui.copyToClipboard(b);
            flash("Đã copy path nhánh: " + b);
        }
    }

    int moveDialog(Collection<ApiGroup> gs) {
        if (gs.isEmpty()) {
            info("Chọn API (bảng hoặc cây) trước.");
            return 0;
        }
        ApiGroup first = gs.iterator().next();
        String p = PathPicker.pick(root, "Chuyển " + gs.size() + " API sang đường dẫn khác", engine.knownPaths(), first.branch(),
                gs.size() + " API sẽ được chuyển và xác nhận vị trí (ghi vào nhật ký, hoàn tác được).");
        if (p == null) {
            return 0;
        }
        int n = engine.moveToPath(gs, p, "");
        flash("Đã chuyển " + n + " API sang " + p);
        return n;
    }

    void revert(Collection<ApiGroup> gs) {
        int n = engine.revert(gs);
        flash(n > 0 ? n + " API đã về vị trí trước" : "Không có vị trí trước để quay về");
    }

    void delete(Collection<ApiGroup> gs) {
        if (gs.isEmpty()) {
            return;
        }
        if (gs.size() > 1 && !confirmDialog("Xóa " + gs.size() + " API? (vị trí của chúng vẫn được giữ chỗ)")) {
            return;
        }
        engine.remove(gs, "");
        api.showGroup(null);
    }

    void rebuild(Collection<ApiGroup> gs) {
        for (ApiGroup g : gs) {
            engine.rebuild(g);
        }
    }

    void roleScopeDialog(boolean enable, RoleScopes.Scope scope, List<String> branches, List<ApiGroup> apis, List<String> roleNames) {
        RoleScopeDialog.Result r = RoleScopeDialog.show(root, engine, enable, scope, branches, apis, roleNames);
        if (r != null) {
            int changed = engine.applyRoleScope(r.roles(), r.enable(), r.scope(), r.targets(), r.applyExisting());
            roles.reload();
            flash((r.enable() ? "Bật " : "Tắt ") + r.roles().size() + " role · " + changed + " API cập nhật");
        }
    }

    void addSubfolder(String base) {
        String b = base != null ? base : engine.activeBranch().name();
        NodeEditorDialog.AddResult res = NodeEditorDialog.showAdd(root, engine, b, false, true);
        if (res == null) {
            return;
        }
        if (res.setAsDestination()) {
            engine.setDestination(res.fullPath());
        } else {
            engine.findOrCreateBranch(res.fullPath(), false);
        }
        engine.setPathNodeType(res.fullPath(), res.typeId());
        syncHeader();
        tick(true);
        flash((res.setAsDestination() ? "Đích mới: " : "Đã tạo mục: ") + res.fullPath());
    }

    void editNodeTypeDialog(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        String typeId = NodeEditorDialog.showEditType(root, engine, path);
        if (typeId != null) {
            engine.setPathNodeType(path, typeId);
            syncHeader();
            tick(true);
            flash("Đã đổi loại nhánh '" + Hierarchy.leaf(path) + "' sang " + engine.nodeTypes().byId(typeId).label());
        }
    }

    void openNodeTypeManager() {
        if (NodeTypeManagerDialog.show(root, engine)) {
            syncHeader();
            tick(true);
        }
    }

    void openTagManager() {
        TagManagerDialog.show(this);
    }

    void openLineage(ApiGroup g, Component anchor, int x, int y) {
        if (g != null) {
            LineageDialog.show(this, g);
        }
    }

    void createClusterDialog(List<ApiGroup> gs) {
        if (gs == null || gs.isEmpty()) {
            info("Chọn ít nhất 1 API trước khi gom Cụm hiển thị.");
            return;
        }
        String branch = gs.get(0).branch();
        String name = ask("Tên Cụm hiển thị mới trong nhánh '" + branch + "'\n"
                + "(Cụm chỉ gom gọn để dễ nhìn, KHÔNG đổi đường dẫn nhánh / Copy STT / Excel):",
                "Cụm " + (engine.clustersInBranch(branch).size() + 1));
        if (name == null || name.isBlank()) {
            return;
        }
        themythofcrawl.model.GroupCluster cl = engine.createCluster(branch, name.trim(), "accent", gs);
        if (cl != null) {
            tick(true);
            flash("Đã gom " + cl.size() + " API vào Cụm [" + cl.name() + "] (giữ nguyên đường dẫn nhánh)");
        } else {
            info("Các API cần thuộc cùng một nhánh để gom vào cùng Cụm hiển thị.");
        }
    }

    void editClusterDialog(themythofcrawl.model.GroupCluster cl) {
        if (cl == null) {
            return;
        }
        String name = ask("Đổi tên Cụm hiển thị (nhánh " + cl.branchPath() + "):", cl.name());
        if (name != null && !name.isBlank()) {
            engine.renameCluster(cl.id(), name.trim(), null);
            tick(true);
            flash("Đã đổi tên Cụm thành [" + name.trim() + "]");
        }
    }

    JMenu clusterMenu(List<ApiGroup> gs) {
        JMenu sub = new JMenu("Cụm hiển thị (Cluster)");
        sub.setIcon(Icons.themed(Icons::cluster));
        if (gs == null || gs.isEmpty()) {
            sub.setEnabled(false);
            return sub;
        }
        String branch = gs.get(0).branch();
        sub.add(Ui.item("Gom " + gs.size() + " API vào Cụm mới...", Icons::plus, () -> createClusterDialog(gs)));
        List<themythofcrawl.model.GroupCluster> existing = engine.clustersInBranch(branch);
        if (!existing.isEmpty()) {
            sub.addSeparator();
            for (themythofcrawl.model.GroupCluster cl : existing) {
                sub.add(Ui.item("Thêm vào Cụm [" + cl.name() + "] (" + cl.size() + " API)", Icons::cluster, () -> {
                    if (engine.addToCluster(cl.id(), gs)) {
                        tick(true);
                        flash("Đã thêm vào Cụm [" + cl.name() + "]");
                    }
                }));
            }
        }
        boolean anyInCluster = gs.stream().anyMatch(g -> engine.clusterOf(g.gid()) != null);
        sub.addSeparator();
        sub.add(Ui.item("Tách khỏi Cụm hiển thị", Icons::undo, anyInCluster, () -> {
            int n = engine.removeFromCluster(gs);
            if (n > 0) {
                tick(true);
                flash("Đã tách " + n + " API khỏi Cụm hiển thị");
            }
        }));
        return sub;
    }

    JMenu tagMenu(List<ApiGroup> gs) {
        JMenu sub = new JMenu("Gắn Tag & REFRESH");
        sub.setIcon(Icons.themed(Icons::tag));
        if (gs == null || gs.isEmpty()) {
            sub.setEnabled(false);
            return sub;
        }
        for (themythofcrawl.model.ApiTagCatalog.TagDef def : engine.tagCatalog().all()) {
            boolean allHave = gs.stream().allMatch(g -> g.hasTag(def.id()));
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(def.id() + " — " + def.label(), allHave);
            item.addActionListener(Ui.safe(e -> {
                boolean present = item.isSelected();
                engine.setApiTag(gs, def.id(), present);
                tick(true);
                flash((present ? "Đã gắn" : "Đã gỡ") + " Tag [" + def.id() + "] cho " + gs.size() + " API");
            }));
            sub.add(item);
        }
        sub.addSeparator();
        sub.add(Ui.item("Gắn Tag tùy chỉnh...", Icons::plus, () -> {
            String raw = ask("Nhập mã Tag mới (VD: AUTH, PAYMENT, NOISE):", "");
            if (raw != null && !raw.isBlank()) {
                String id = themythofcrawl.model.ApiTagCatalog.normalizeId(raw);
                if (engine.tagCatalog().byId(id) == null) {
                    engine.tagCatalog().upsert(new themythofcrawl.model.ApiTagCatalog.TagDef(id, raw.trim(), "info", "", false));
                    engine.saveTagCatalog();
                }
                engine.setApiTag(gs, id, true);
                tick(true);
                flash("Đã gắn Tag [" + id + "]");
            }
        }));
        sub.add(Ui.item("Quản lý Tag & Chính sách REFRESH...", Icons::gear, this::openTagManager));
        return sub;
    }

    void renameBranchDialog(String prefix) {
        String n = ask("Đổi tên nhánh '" + prefix + "' (tự động cập nhật toàn bộ các sub-folder con bên dưới):", prefix);
        if (n != null && !n.isBlank()) {
            engine.renamePrefix(prefix, n);
        }
    }

    void deleteBranchDialog(String prefix) {
        if (prefix == null) {
            return;
        }
        int n = engine.groupsUnder(prefix).size();
        Object[] opts = {"Chỉ xóa Nhánh (Chuyển API lên cấp cha)", "Xóa cả Nhánh lẫn " + n + " API", "Hủy"};
        int c = JOptionPane.showOptionDialog(Ui.ownerWindow(root), "Bạn muốn xóa nhánh Sub-folder '" + prefix + "' (" + n + " API bên trong) như thế nào?",
                Text.EXT_NAME, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        if (c == 0 || c == 1) {
            engine.deletePrefix(prefix, c == 1);
        }
    }

    void moveViaDragDrop(List<ApiGroup> gs, String targetPath) {
        if (gs == null || gs.isEmpty() || targetPath == null || targetPath.isBlank()) {
            return;
        }
        int n = engine.moveToPath(gs, targetPath, "kéo thả");
        if (n <= 0) {
            return;
        }
        tick(true);
        List<ApiGroup> moved = List.copyOf(gs);
        toastPane.showUndoToast("Đã chuyển " + n + " API sang " + Hierarchy.normalize(targetPath),
                "Hoàn tác", () -> {
                    revert(moved);
                    tick(true);
                });
    }

    void jumpToTreeBranchOrApi(ApiGroup g, String branchPath, boolean focusOnBranch) {
        if (g != null && g.hidden()) {
            engine.show(List.of(g));
            tick(true);
        }
        selectTab(treeTab.panel);
        treeTab.jumpTo(g, branchPath, focusOnBranch);
        String dest = g != null ? g.branch() : branchPath;
        if (!Text.blank(dest)) {
            flash("Đã đi tới nhánh: " + dest + (focusOnBranch ? " (Đang Tập trung kèm nhánh con)" : ""));
        }
    }

    void jumpToGroup(ApiGroup g) {
        jumpToGroup(g, null);
    }

    void jumpToGroup(ApiGroup g, RoleResult r) {
        if (g == null) {
            return;
        }
        if (g.hidden()) {
            engine.show(List.of(g));
            tick(true);
        }
        selectTab(api.panel);
        api.select(g, r);
    }

    void jumpToBranch(String branchPath) {
        if (branchPath == null || branchPath.isBlank()) {
            return;
        }
        selectTab(api.panel);
        api.selectBranch(branchPath);
    }

    void jumpToRole(int roleIndex) {
        selectTab(roles.panel);
        if (roleIndex < 0) {
            roles.selectOriginal();
        } else {
            roles.selectRole(roleIndex);
        }
    }

    void jumpToTarget(TargetEntry e) {
        selectTab(excel.panel);
        excel.select(e);
    }

    void showAudit(int section) {
        selectTab(audit.panel);
        audit.show(section, null);
    }

    void showPlaceOf(String key) {
        selectTab(audit.panel);
        audit.show(1, key);
    }

    // =================================================================== global shortcuts
    private void installShortcuts() {
        registerRootShortcut("ctrl K", "tmoc.spotlight", () -> spotlight.toggle());
        registerRootShortcut("ctrl F", "tmoc.find", () -> {
            if (spotlight.isVisible()) {
                spotlight.close();
            }
            focusActiveSearch();
        });
        registerRootShortcut("ctrl shift C", "tmoc.copy", () -> {
            List<ApiGroup> gs = shortcutTargetGroups();
            if (!gs.isEmpty()) {
                copy(gs);
            }
        });
        registerRootShortcut("ctrl ENTER", "tmoc.send", () -> {
            if (spotlight.isVisible()) {
                return;
            }
            List<ApiGroup> gs = shortcutTargetGroups();
            if (!gs.isEmpty()) {
                send(gs);
            }
        });
        registerRootShortcut("ctrl R", "tmoc.repeater", () -> {
            if (spotlight.isVisible()) {
                return;
            }
            List<ApiGroup> gs = shortcutTargetGroups();
            if (!gs.isEmpty()) {
                push(gs);
            }
        });
        registerRootShortcut("DELETE", "tmoc.delete", () -> {
            if (spotlight.isVisible() || isTextEditingFocus()) {
                return;
            }
            List<ApiGroup> gs = shortcutTargetGroups();
            if (!gs.isEmpty() && confirmDialog("Xóa " + gs.size() + " API? (vị trí của chúng vẫn được giữ chỗ)")) {
                engine.remove(gs, "");
                api.showGroup(null);
                tick(true);
            }
        });
        // V_7.0 hideable layout
        registerRootShortcut("ctrl alt 1", "tmoc.layout.tree", () -> api.togglePane(ApiTab.Pane.TREE));
        registerRootShortcut("ctrl alt 2", "tmoc.layout.detail", () -> {
            if (isTreeTabActive()) {
                treeTab.toggleDetail();
            } else {
                api.togglePane(ApiTab.Pane.DETAIL);
            }
        });
        registerRootShortcut("ctrl alt 3", "tmoc.layout.toolbar", () -> {
            if (isTreeTabActive()) {
                treeTab.setToolbarVisible(!treeTab.toolbarVisible());
            } else {
                api.togglePane(ApiTab.Pane.TOOLBAR);
            }
        });
        registerRootShortcut("ctrl alt H", "tmoc.layout.header", () -> {
            setHeaderVisible(!headerVisible());
            api.saveLayout();
        });
        registerRootShortcut("ctrl alt 0", "tmoc.layout.max", () -> {
            if (isTreeTabActive()) {
                treeTab.toggleMaximized();
            } else {
                api.toggleMaximized();
            }
        });
        for (int i = 0; i < tabs.getTabCount() && i < 9; i++) {
            int idx = i;
            registerRootShortcut("alt " + (i + 1), "tmoc.tab." + (i + 1), () -> {
                if (spotlight.isVisible()) {
                    spotlight.close();
                }
                tabs.setSelectedIndex(idx);
            });
        }
    }

    private void registerRootShortcut(String stroke, String key, Runnable action) {
        KeyStroke ks = KeyStroke.getKeyStroke(stroke);
        if (ks == null) {
            return;
        }
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(ks, key);
        root.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    SearchField activeSearchField() {
        Component sel = selectedTab();
        if (sel == api.panel) {
            return api.searchField();
        }
        if (sel == settings.panel) {
            return settings.searchField();
        }
        if (sel == log.panel) {
            return log.searchField();
        }
        if (sel == audit.panel) {
            return audit.activeSearchField();
        }
        return null;
    }

    boolean focusActiveSearch() {
        SearchField sf = activeSearchField();
        if (sf != null) {
            sf.focusField();
            return true;
        }
        spotlight.open();
        return false;
    }

    private List<ApiGroup> shortcutTargetGroups() {
        Component sel = selectedTab();
        if (sel == api.panel) {
            List<ApiGroup> fromTable = api.selectedGroups();
            if (!fromTable.isEmpty()) {
                return fromTable;
            }
            return scope().groups();
        }
        if (sel == history.panel) {
            useSource(history.tree);
            return scope().groups();
        }
        if (sel == treeTab.panel) {
            useSource(treeTab.tree);
            return scope().groups();
        }
        return scope().groups();
    }

    private static boolean isTextEditingFocus() {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus instanceof JTextComponent tc && tc.isEditable()) {
            return true;
        }
        if (focus instanceof JTable t && t.isEditing()) {
            return true;
        }
        return false;
    }

    /** Invokes a registered root shortcut by KeyStroke string (for tests). */
    boolean dispatchShortcut(String stroke) {
        KeyStroke ks = KeyStroke.getKeyStroke(stroke);
        if (ks == null) {
            return false;
        }
        Object actionKey = root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(ks);
        if (actionKey == null) {
            return false;
        }
        javax.swing.Action act = root.getActionMap().get(actionKey);
        if (act == null || !act.isEnabled()) {
            return false;
        }
        act.actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, String.valueOf(actionKey)));
        return true;
    }

    /** Click on a copy glyph / Excel badge / duplicate badge (table or tree). */
    void chip(BranchTree.Chip c, ApiGroup g, Component anchor, int x, int y) {
        switch (c) {
            case COPY -> copy(List.of(g));
            case EXCEL -> {
                if (g.target() != null) {
                    jumpToTarget(g.target());
                }
            }
            case DUP -> {
                if (g.dupTotal() > 1) {
                    LineageDialog.showFloating(this, g);
                }
            }
            default -> { }
        }
    }

    /** Confirm / move / revert / history items of placement menus. */
    void placeItems(JPopupMenu m, List<ApiGroup> gs) {
        List<ApiGroup> pending = gs.stream().filter(ApiGroup::pending).toList();
        m.add(Ui.item("Xác nhận vị trí (" + pending.size() + " API đang chờ)", t -> Icons.check(t.success.text()),
                !pending.isEmpty(), () -> engine.confirm(pending, "")));
        m.add(Ui.item("Chuyển " + gs.size() + " API sang đường dẫn khác...", Icons::move, !gs.isEmpty(), () -> moveDialog(gs)));
        m.add(Ui.item("Đưa " + gs.size() + " API về vị trí trước", Icons::undo,
                gs.stream().anyMatch(g -> !g.placeHistory().isEmpty()), () -> revert(gs)));
        if (gs.size() == 1) {
            m.add(Ui.item("Lịch sử vị trí API #" + gs.get(0).gid(), Icons::compass, () -> showPlaceOf(gs.get(0).bookKey())));
        }
    }

    // =================================================================== exports
    void exportExcel() {
        File f = save(Text.EXT_NAME + "_API_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")) + ".xlsx",
                "Excel (.xlsx)", "xlsx");
        if (f == null) {
            return;
        }
        try {
            List<ApiGroup> all = new ArrayList<>(engine.groups());
            all.addAll(engine.hiddenGroups());
            Config c = engine.config();
            byte[] data = Reports.workbook(all, engine.targets(), c.copyFormat, c.excelPositionCopyFormat,
                    g -> engine.copyLabel(g, false), engine.hits(), engine.book());
            Files.write(f.toPath(), data);
            flash("Đã xuất " + f.getName());
            engine.log("Đã xuất Excel: " + f.getAbsolutePath(), null);
        } catch (Exception e) {
            error("Xuất Excel lỗi: " + e.getMessage());
        }
    }

    void exportMatrix() {
        File f = save(Text.EXT_NAME + "_matrix.json", "JSON", "json");
        if (f != null) {
            write(f.toPath(), Reports.matrixJson(engine.groups(), engine.branches()));
        }
    }

    void exportHistory() {
        File f = save(Text.EXT_NAME + "_history.md", "Text / Markdown", "md", "txt");
        if (f != null) {
            write(f.toPath(), Reports.history(engine.branches(), engine.groups()));
        }
    }

    private void write(Path p, String text) {
        try {
            Files.writeString(p, text, StandardCharsets.UTF_8);
            flash("Đã xuất " + p.getFileName());
        } catch (Exception e) {
            error("Ghi file lỗi: " + e.getMessage());
        }
    }

    File save(String name, String desc, String... ext) {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter(desc, ext));
        fc.setSelectedFile(new File(name));
        if (fc.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File f = fc.getSelectedFile();
        if (!f.getName().contains(".")) {
            f = new File(f.getParentFile(), f.getName() + "." + ext[0]);
        }
        return f;
    }

    // =================================================================== dialogs
    String ask(String question, String initial) {
        Object v = JOptionPane.showInputDialog(Ui.ownerWindow(root), question, initial);
        return v == null ? null : v.toString();
    }

    void setConfirmHook(Predicate<String> hook) {
        this.confirmHook = hook;
    }

    boolean confirmDialog(String q) {
        if (confirmHook != null) {
            return confirmHook.test(q);
        }
        return JOptionPane.showConfirmDialog(Ui.ownerWindow(root), q, Text.EXT_NAME, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    void info(String msg) {
        JOptionPane.showMessageDialog(Ui.ownerWindow(root), msg, Text.EXT_NAME, JOptionPane.INFORMATION_MESSAGE);
    }

    void error(String msg) {
        JOptionPane.showMessageDialog(Ui.ownerWindow(root), msg, Text.EXT_NAME, JOptionPane.ERROR_MESSAGE);
    }

    static JScrollPane scroll(JComponent c) {
        JScrollPane s = new JScrollPane(c);
        s.getVerticalScrollBar().setUnitIncrement(16);
        return s;
    }

    static void selectRow(JTable t, int modelRow) {
        if (modelRow < 0) {
            return;
        }
        int v = t.convertRowIndexToView(modelRow);
        if (v >= 0) {
            t.setRowSelectionInterval(v, v);
            t.scrollRectToVisible(t.getCellRect(v, 0, true));
        }
    }

    static boolean in(Set<?> s, Object o) {
        return s.contains(o);
    }

    static PlacementBook.Action action(String a) {
        return PlacementBook.Action.valueOf(a);
    }
}
