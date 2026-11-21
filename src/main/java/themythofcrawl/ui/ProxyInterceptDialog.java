package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.ProxyInterceptQueue;
import themythofcrawl.core.ProxyInterceptQueue.PendingRequest;
import themythofcrawl.core.ProxyInterceptQueue.ResolutionKind;
import themythofcrawl.core.Text;
import themythofcrawl.model.ActionInterceptRule;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.ui.kit.MethodChipBar;
import themythofcrawl.ui.kit.ModernToggleSwitch;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SearchField;
import themythofcrawl.ui.kit.SectionHeader;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.kit.WrappedText;
import themythofcrawl.ui.theme.Theme;

/**
 * Floating, draggable, resizable Detailed Proxy Intercept Queue (Mode #5 — Chặn chờ duyệt trực tiếp).
 * <p>
 * In V_3.5_beta, requests held by Proxy Intercept also appear live right inside their target Branch
 * on the main Tree ({@code [CHỜ DUYỆT · CHƯA GỬI]}). This detailed window is opened on demand when
 * the user wants full filtering by Branch/Host, batch Forward/Drop, or side-by-side raw editing.
 */
public final class ProxyInterceptDialog {
    private static final Pattern CONTENT_LENGTH_HEADER =
            Pattern.compile("(?im)^Content-Length\\s*:\\s*\\d+\\r?$");
    private static final String FILTER_ALL_BRANCHES = "Mọi nhánh";
    private static final String FILTER_ALL_HOSTS = "Mọi Host";

    private static FloatingRef activeFloating;

    private record FloatingRef(
            WeakReference<MainPanel> ownerUi,
            WeakReference<JDialog> dialog,
            Runnable refresh) {
    }

    /** Headless-testable view bundle of the Live Proxy Intercept Queue. */
    public record View(
            JPanel panel,
            JTable queueTable,
            JTextArea requestEditor,
            JToggleButton pinButton,
            Runnable refresh,
            Runnable dispose) {
    }

    private ProxyInterceptDialog() {
    }

    /** Refreshes the floating window in real time if it is currently open (without stealing focus). */
    public static void refreshIfOpen(MainPanel ui) {
        FloatingRef ref = activeFloating;
        if (ref == null) {
            return;
        }
        MainPanel prevUi = ref.ownerUi().get();
        JDialog prevDlg = ref.dialog().get();
        if ((ui == null || prevUi == ui) && prevDlg != null && prevDlg.isDisplayable() && prevDlg.isVisible()) {
            Runnable r = ref.refresh();
            if (r != null) {
                if (SwingUtilities.isEventDispatchThread()) {
                    r.run();
                } else {
                    SwingUtilities.invokeLater(r);
                }
            }
        }
    }

    /** Opens (or raises &amp; refreshes) the floating, draggable, resizable Live Proxy Intercept window. */
    public static void showFloating(MainPanel ui) {
        if (ui == null) {
            return;
        }
        FloatingRef ref = activeFloating;
        if (ref != null) {
            MainPanel prevUi = ref.ownerUi().get();
            JDialog prevDlg = ref.dialog().get();
            if (prevUi == ui && prevDlg != null && prevDlg.isDisplayable() && prevDlg.isVisible()) {
                ref.refresh().run();
                prevDlg.toFront();
                prevDlg.requestFocus();
                return;
            }
        }

        Prefs prefs = UiPrefs.resolve(null, ui.engine);
        Window owner = SwingUtilities.getWindowAncestor(ui.root());
        JDialog dlg = new JDialog(Ui.ownerWindow(owner), "Hàng chờ Chặn Proxy chi tiết — " + Text.EXT_NAME,
                JDialog.ModalityType.MODELESS);
        dlg.setResizable(true);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        View view = buildView(ui.engine, ui, dlg::dispose, pinned -> {
            try {
                dlg.setAlwaysOnTop(pinned);
            } catch (SecurityException ignored) {
                // Restricted environments may disallow alwaysOnTop
            }
        });

        dlg.setContentPane(view.panel());

        dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeInterceptQueue");
        dlg.getRootPane().getActionMap().put("closeInterceptQueue", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dlg.dispose();
            }
        });

        dlg.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (dlg.isVisible()) {
                    UiPrefs.setInterceptSize(prefs, dlg.getWidth(), dlg.getHeight());
                }
            }
        });
        dlg.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                UiPrefs.setInterceptSize(prefs, dlg.getWidth(), dlg.getHeight());
            }

            @Override
            public void windowClosed(WindowEvent e) {
                view.dispose().run();
                FloatingRef cur = activeFloating;
                if (cur != null && cur.dialog().get() == dlg) {
                    activeFloating = null;
                }
            }
        });

        Dimension pref = UiPrefs.interceptSize(prefs);
        dlg.setMinimumSize(new Dimension(920, 560));
        dlg.setSize(new Dimension(Math.max(pref.width, 1020), Math.max(pref.height, 620)));
        dlg.setLocationRelativeTo(owner);
        Theme.track(dlg);

        activeFloating = new FloatingRef(new WeakReference<>(ui), new WeakReference<>(dlg), view.refresh());
        Ui.fastScroll(dlg.getContentPane());
        dlg.setVisible(true);
    }

    /** Closes any open floating window owned by {@code ui} (called on {@link MainPanel#dispose()}). */
    static void closeFor(MainPanel ui) {
        FloatingRef ref = activeFloating;
        if (ref != null && ref.ownerUi().get() == ui) {
            JDialog dlg = ref.dialog().get();
            activeFloating = null;
            if (dlg != null && dlg.isDisplayable()) {
                dlg.dispose();
            }
        }
    }

    /** Builds the pure-Swing view (also used by headless tests &amp; screenshot verifiers). */
    public static View buildView(
            Engine engine,
            MainPanel ui,
            Runnable onClose,
            Consumer<Boolean> onPinToggle) {
        Theme t0 = Theme.current();
        ActionEngine ae = engine.actionEngine();
        ProxyInterceptQueue queue = engine.proxyQueue();

        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setOpaque(true);
        root.setBackground(t0.bg);
        root.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.SPACE_2, 0));

        // ------------------------------------------------------------------
        // 1. HEADER + PIN TOGGLE + LIVE COUNT PILL
        // ------------------------------------------------------------------
        Pill liveStatusPill = new Pill("Đang tắt", t -> t.neutral, t -> Icons.strategyShield(t.muted, 12));

        JToggleButton pinBtn = new JToggleButton("Ghim nổi");
        pinBtn.setIcon(Icons.themed(t -> Icons.pin(t.muted, 12)));
        pinBtn.setFocusPainted(false);
        pinBtn.setMargin(new Insets(3, 10, 3, 10));
        pinBtn.setFont(t0.smallBoldFont);
        pinBtn.setToolTipText("Ghim cửa sổ nổi phía trên Burp Suite để vừa thao tác trình duyệt vừa bấm Gửi");
        pinBtn.addActionListener(Ui.safe(e -> {
            boolean pinned = pinBtn.isSelected();
            Theme t = Theme.current();
            pinBtn.setIcon(Icons.themed(th -> Icons.pin(pinned ? th.accent.text() : th.muted, 12)));
            pinBtn.setForeground(pinned ? t.accent.text() : t.fg);
            if (onPinToggle != null) {
                onPinToggle.accept(pinned);
            }
        }));

        SectionHeader headerInner = new SectionHeader(
                "Hàng chờ Chặn Proxy chi tiết",
                "Xem đầy đủ Nhánh đích, Host, Method và chỉnh Raw HTTP trước khi bấm Gửi lên Server. (Mọi request chờ cũng hiện trực tiếp trên Cây!)",
                Ui.flow(FlowLayout.RIGHT, liveStatusPill, pinBtn),
                null);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(true);
        header.setBackground(t0.surfaceAlt);
        header.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
        header.add(headerInner, BorderLayout.CENTER);

        // ------------------------------------------------------------------
        // 2. QUICK-SCOPE CONTROL BAR (ModernToggleSwitch + MethodChipBar + Pin Path/API)
        // ------------------------------------------------------------------
        JPanel controlsCard = new JPanel();
        controlsCard.setLayout(new BoxLayout(controlsCard, BoxLayout.Y_AXIS));
        controlsCard.setOpaque(true);
        controlsCard.setBackground(t0.surface);
        controlsCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, t0.border),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        final boolean[] syncingUi = {false};

        ModernToggleSwitch masterSwitch = new ModernToggleSwitch(ae.isInterceptWaitEnabled(), true)
                .labels("ĐANG CHẶN", "Đang tắt");
        masterSwitch.setToolTipText("Bật hoặc tắt Chặn chờ duyệt (Mode #5 — giữ request lại đợi thao tác)");

        JCheckBox allScopeChk = new JCheckBox("Toàn bộ nhánh", ae.isInterceptWaitAllScope());
        allScopeChk.setOpaque(false);
        allScopeChk.setToolTipText("Chặn tất cả request khớp các HTTP Method bên cạnh trên mọi đường dẫn / nhánh");

        MethodChipBar methodBar = new MethodChipBar(ae.getInterceptWaitMethods(), true);

        JCheckBox autoOpenChk = new JCheckBox("Tự mở cửa sổ khi có chặn", queue.isAutoOpenWindow());
        autoOpenChk.setOpaque(false);
        autoOpenChk.setFont(t0.smallFont);
        autoOpenChk.setToolTipText("Mặc định tắt — mọi request chờ duyệt đã hiện ngay trên Cây. Tích chọn nếu muốn tự bật cửa sổ chi tiết khi có chặn mới.");
        autoOpenChk.addActionListener(Ui.safe(e -> queue.setAutoOpenWindow(autoOpenChk.isSelected())));

        JPanel row1 = new JPanel(new BorderLayout(8, 0));
        row1.setOpaque(false);
        row1.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Chặn Proxy:"), masterSwitch, allScopeChk, methodBar), BorderLayout.WEST);
        row1.add(autoOpenChk, BorderLayout.EAST);

        // Row 2: Quick Pin buttons + Open Rules + Active Pinned Chips
        JPanel pinnedChipsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        pinnedChipsRow.setOpaque(false);

        JButton pinBranchBtn = Ui.secondaryBtn("+ Chặn nhánh hiện tại", t -> Icons.branch(t.accent.text()), null);
        JButton pinApiBtn = Ui.secondaryBtn("+ Chặn API đang chọn", t -> Icons.api(t.warning.text()), null);
        JButton rulesBtn = Ui.secondaryBtn("Sổ cái luật (CRUD)...", t -> Icons.sliders(t.fg), null);

        JPanel row2 = new JPanel(new BorderLayout(8, 0));
        row2.setOpaque(false);
        row2.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        row2.add(Ui.flow(FlowLayout.LEFT, pinBranchBtn, pinApiBtn, rulesBtn), BorderLayout.WEST);
        row2.add(pinnedChipsRow, BorderLayout.CENTER);

        controlsCard.add(row1);
        controlsCard.add(row2);

        JPanel topWrap = new JPanel(new BorderLayout());
        topWrap.setOpaque(false);
        topWrap.add(header, BorderLayout.NORTH);
        topWrap.add(controlsCard, BorderLayout.SOUTH);
        root.add(topWrap, BorderLayout.NORTH);

        // ------------------------------------------------------------------
        // 3. CENTER SPLIT: FILTERABLE 8-COL QUEUE TABLE (LEFT) + EDITABLE RAW HTTP (RIGHT)
        // ------------------------------------------------------------------
        JComboBox<String> branchFilter = new JComboBox<>(new String[]{FILTER_ALL_BRANCHES});
        branchFilter.setPreferredSize(new Dimension(170, 26));
        branchFilter.setToolTipText("Lọc request đang chờ theo Nhánh đích trên Cây");

        JComboBox<String> hostFilter = new JComboBox<>(new String[]{FILTER_ALL_HOSTS});
        hostFilter.setPreferredSize(new Dimension(150, 26));
        hostFilter.setToolTipText("Lọc request đang chờ theo Host đích");

        SearchField filterSearch = new SearchField("Tìm path, method, lý do...", 14);

        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        filterBar.setOpaque(true);
        filterBar.setBackground(t0.surfaceAlt);
        filterBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, t0.border));
        filterBar.add(Ui.muted("Lọc:"));
        filterBar.add(branchFilter);
        filterBar.add(hostFilter);
        filterBar.add(filterSearch);

        DefaultTableModel tm = new DefaultTableModel(
                new Object[]{"#", "Trạng thái", "Nhánh đích", "Host", "Method", "Đường dẫn", "Lý do chặn", "Lúc"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        ThemedTable queueTable = new ThemedTable(tm);
        Ui.style(queueTable);
        queueTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        queueTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        queueTable.setRowHeight(Theme.current().scaled(28));
        int[] widths = {40, 86, 150, 130, 68, 195, 165, 72};
        for (int i = 0; i < widths.length; i++) {
            queueTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            queueTable.getColumnModel().getColumn(i).setCellRenderer(new QueueCellRenderer(i));
        }
        queueTable.getColumnModel().getColumn(0).setMaxWidth(52);
        queueTable.getColumnModel().getColumn(4).setMinWidth(60);

        JPanel emptyHintBar = new JPanel(new BorderLayout());
        emptyHintBar.setOpaque(true);
        emptyHintBar.setBackground(t0.info.fill());
        emptyHintBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, t0.info.border()),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        WrappedText emptyHintText = WrappedText.body(
                "Chưa có request nào đang đợi. Bật công tắc ĐANG CHẶN ở trên (hoặc ghim nhánh/API cần chặn), sau đó thao tác trên trình duyệt (VD: bấm Save) — request sẽ dừng ngay trên Cây và tại bảng này đợi bạn bấm Gửi lên Server.",
                460);
        emptyHintBar.add(emptyHintText, BorderLayout.CENTER);

        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setOpaque(true);
        leftPanel.setBackground(t0.surface);
        leftPanel.setBorder(BorderFactory.createLineBorder(t0.border));
        JScrollPane tableScroll = new JScrollPane(queueTable);
        tableScroll.setColumnHeaderView(queueTable.getTableHeader());
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        leftPanel.add(filterBar, BorderLayout.NORTH);
        leftPanel.add(tableScroll, BorderLayout.CENTER);
        leftPanel.add(emptyHintBar, BorderLayout.SOUTH);

        // Right: editable raw HTTP request viewer
        JLabel reqMetaLbl = Ui.bold("Chưa chọn request nào trong hàng chờ");
        JLabel reqSubLbl = Ui.muted("Chọn 1 dòng bên trái để xem hoặc chỉnh sửa header/body trước khi bấm Gửi lên Server.");
        JButton fmtJsonBtn = Ui.secondaryBtn("Format JSON", Icons::codeBraces, null);
        fmtJsonBtn.setEnabled(false);
        JButton resetRawBtn = Ui.secondaryBtn("Khôi phục gốc", Icons::undo, null);
        resetRawBtn.setEnabled(false);

        JPanel rightMeta = new JPanel(new BorderLayout(8, 2));
        rightMeta.setOpaque(true);
        rightMeta.setBackground(t0.surfaceAlt);
        rightMeta.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, t0.border),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        JPanel metaTexts = new JPanel(new BorderLayout(0, 2));
        metaTexts.setOpaque(false);
        metaTexts.add(reqMetaLbl, BorderLayout.NORTH);
        metaTexts.add(reqSubLbl, BorderLayout.SOUTH);
        rightMeta.add(metaTexts, BorderLayout.CENTER);
        rightMeta.add(Ui.flow(FlowLayout.RIGHT, fmtJsonBtn, resetRawBtn), BorderLayout.EAST);

        JTextArea requestEditor = new JTextArea();
        requestEditor.setFont(t0.monoFont);
        requestEditor.setBackground(t0.surface);
        requestEditor.setForeground(t0.fg);
        requestEditor.setCaretColor(t0.fg);
        requestEditor.setLineWrap(false);
        requestEditor.setMargin(new Insets(8, 10, 8, 10));
        requestEditor.setEditable(false);

        JScrollPane editorScroll = new JScrollPane(requestEditor);
        editorScroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setOpaque(true);
        rightPanel.setBackground(t0.surface);
        rightPanel.setBorder(BorderFactory.createLineBorder(t0.border));
        rightPanel.add(rightMeta, BorderLayout.NORTH);
        rightPanel.add(editorScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setResizeWeight(0.54);
        split.setDividerLocation(560);
        split.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
        root.add(split, BorderLayout.CENTER);

        // ------------------------------------------------------------------
        // 4. BOTTOM ACTION BAR (Unified Burp-LAF-proof buttons)
        // ------------------------------------------------------------------
        final List<PendingRequest> displayed = new ArrayList<>();
        final long[] selectedId = {-1L};
        final Map<Long, String> draftEdits = new LinkedHashMap<>();

        JButton forwardBtn = Ui.solidPrimary("Gửi lên Server (Forward)",
                t -> Icons.send(t.accent.onSolid()), null);
        JButton holdSaveBtn = Ui.tonalBtn("Lưu nháp vào Cây & Giữ",
                t -> Icons.strategyShield(t.warning.text(), 14), t -> t.warning, null);
        JButton dropBtn = Ui.dangerBtn("Hủy bỏ (Drop)",
                t -> Icons.stop(t.danger.text()), null);

        JButton forwardAllBtn = Ui.secondaryBtn("Gửi tất cả", t -> Icons.sendAll(t.success.text()), null);
        JButton dropAllBtn = Ui.dangerBtn("Hủy tất cả", t -> Icons.trash(t.danger.text()), null);
        JButton closeBtn = Ui.secondaryBtn("Đóng", null, onClose != null ? onClose : () -> { });

        JPanel bottomBar = new JPanel(new BorderLayout(8, 0));
        bottomBar.setOpaque(false);
        bottomBar.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
        bottomBar.add(Ui.flow(FlowLayout.LEFT, forwardBtn, holdSaveBtn, dropBtn), BorderLayout.WEST);
        bottomBar.add(Ui.flow(FlowLayout.RIGHT, forwardAllBtn, dropAllBtn, closeBtn), BorderLayout.EAST);
        root.add(bottomBar, BorderLayout.SOUTH);

        // ------------------------------------------------------------------
        // 5. STATE SYNC & ACTIONS
        // ------------------------------------------------------------------
        Runnable syncSelectionEditor = () -> {
            int row = queueTable.getSelectedRow();
            if (row < 0 || row >= displayed.size()) {
                selectedId[0] = -1L;
                reqMetaLbl.setText("Chưa chọn request nào trong hàng chờ");
                reqSubLbl.setText("Chọn 1 dòng bên trái để xem hoặc chỉnh sửa header/body trước khi bấm Gửi lên Server.");
                requestEditor.setText("");
                requestEditor.setEditable(false);
                fmtJsonBtn.setEnabled(false);
                resetRawBtn.setEnabled(false);
                forwardBtn.setEnabled(false);
                holdSaveBtn.setEnabled(false);
                dropBtn.setEnabled(false);
                return;
            }
            PendingRequest pr = displayed.get(row);
            if (selectedId[0] != pr.id()) {
                selectedId[0] = pr.id();
                String draft = draftEdits.get(pr.id());
                String rawText = draft != null ? draft : new String(pr.originalRequest(), StandardCharsets.ISO_8859_1);
                requestEditor.setText(rawText);
                requestEditor.setCaretPosition(0);
            }
            requestEditor.setEditable(true);
            String hostLbl = pr.service() != null ? pr.service().protocol() + "://" + pr.service().hostLabel() : "";
            reqMetaLbl.setText("#" + pr.id() + "  " + pr.method() + " " + pr.path());
            reqSubLbl.setText("Nhánh đích: " + pr.branchName()
                    + (hostLbl.isEmpty() ? "" : "  ·  " + hostLbl)
                    + "  ·  " + pr.reason());
            fmtJsonBtn.setEnabled(true);
            resetRawBtn.setEnabled(true);
            forwardBtn.setEnabled(true);
            holdSaveBtn.setEnabled(true);
            dropBtn.setEnabled(true);
        };

        requestEditor.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void saveDraft() {
                if (selectedId[0] > 0 && requestEditor.isEditable()) {
                    draftEdits.put(selectedId[0], requestEditor.getText());
                }
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { saveDraft(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { saveDraft(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { saveDraft(); }
        });

        final Runnable[] refreshHolder = new Runnable[1];
        Runnable refresh = () -> {
            syncingUi[0] = true;
            try {
                boolean on = ae.isInterceptWaitEnabled();
                masterSwitch.setOn(on, false);
                allScopeChk.setSelected(ae.isInterceptWaitAllScope());
                autoOpenChk.setSelected(queue.isAutoOpenWindow());

                methodBar.setSelectedMethods(ae.getInterceptWaitMethods());

                // Refresh pinned branches & APIs chips
                pinnedChipsRow.removeAll();
                for (String b : ae.getInterceptWaitBranches()) {
                    pinnedChipsRow.add(makeRemovableChip("Nhánh: " + Text.shortPath(b, 26), t -> t.accent, () -> {
                        engine.toggleBranchInterceptWait(b, false);
                        if (refreshHolder[0] != null) refreshHolder[0].run();
                        if (ui != null) ui.syncHeader();
                    }));
                }
                for (String ep : ae.getInterceptWaitEndpoints()) {
                    pinnedChipsRow.add(makeRemovableChip("API: " + Text.shortPath(ep, 28), t -> t.warning, () -> {
                        int sp = ep.indexOf(' ');
                        if (sp > 0) {
                            ae.setEndpointInterceptWait(ep.substring(0, sp), ep.substring(sp + 1), false);
                        }
                        if (refreshHolder[0] != null) refreshHolder[0].run();
                        if (ui != null) ui.syncHeader();
                    }));
                }
                if (ae.getInterceptWaitBranches().isEmpty() && ae.getInterceptWaitEndpoints().isEmpty()) {
                    pinnedChipsRow.add(Ui.muted(ae.isInterceptWaitAllScope()
                            ? "Đang áp dụng: Toàn bộ nhánh theo các Method đã chọn"
                            : "Chưa ghim nhánh/API nào — bấm '+ Chặn nhánh hiện tại' hoặc tick 'Toàn bộ nhánh'"));
                }
                pinnedChipsRow.revalidate();
                pinnedChipsRow.repaint();

                ApiGroup curApi = (ui != null && ui.api != null) ? ui.api.currentGroup() : null;
                pinApiBtn.setEnabled(curApi != null);
                pinApiBtn.setToolTipText(curApi != null
                        ? "Ghim chặn chờ duyệt cho " + curApi.method() + " " + ActionEngine.normalizePathTemplate(curApi.path())
                        : "Chọn 1 API trong bảng để ghim chặn chờ duyệt riêng cho API đó");

                // Sync Branch & Host filter dropdown options without losing selection
                String prevBranchSel = branchFilter.getSelectedItem() instanceof String s ? s : FILTER_ALL_BRANCHES;
                branchFilter.removeAllItems();
                branchFilter.addItem(FILTER_ALL_BRANCHES);
                for (String b : queue.pendingBranches()) {
                    branchFilter.addItem(b);
                }
                branchFilter.setSelectedItem(prevBranchSel);
                if (branchFilter.getSelectedIndex() < 0) {
                    branchFilter.setSelectedIndex(0);
                }

                String prevHostSel = hostFilter.getSelectedItem() instanceof String s ? s : FILTER_ALL_HOSTS;
                hostFilter.removeAllItems();
                hostFilter.addItem(FILTER_ALL_HOSTS);
                for (String h : queue.pendingHosts()) {
                    hostFilter.addItem(h);
                }
                hostFilter.setSelectedItem(prevHostSel);
                if (hostFilter.getSelectedIndex() < 0) {
                    hostFilter.setSelectedIndex(0);
                }

                // Refresh filtered queue table
                List<PendingRequest> allPending = queue.pending();
                String bSel = branchFilter.getSelectedItem() instanceof String s ? s : FILTER_ALL_BRANCHES;
                String hSel = hostFilter.getSelectedItem() instanceof String s ? s : FILTER_ALL_HOSTS;
                String qText = filterSearch.getText().trim().toLowerCase(Locale.ROOT);

                long prevId = selectedId[0];
                displayed.clear();
                for (PendingRequest pr : allPending) {
                    if (!FILTER_ALL_BRANCHES.equals(bSel) && !bSel.equalsIgnoreCase(pr.branchName())) {
                        continue;
                    }
                    String host = pr.service() != null ? pr.service().hostLabel() : "";
                    if (!FILTER_ALL_HOSTS.equals(hSel) && !hSel.equalsIgnoreCase(host)) {
                        continue;
                    }
                    if (!qText.isEmpty()) {
                        String hay = (pr.method() + " " + pr.path() + " " + pr.branchName() + " " + host + " " + pr.reason())
                                .toLowerCase(Locale.ROOT);
                        if (!hay.contains(qText)) {
                            continue;
                        }
                    }
                    displayed.add(pr);
                }
                draftEdits.keySet().removeIf(id -> allPending.stream().noneMatch(p -> p.id() == id));

                tm.setRowCount(0);
                int selRow = -1;
                for (int i = 0; i < displayed.size(); i++) {
                    PendingRequest pr = displayed.get(i);
                    String host = pr.service() != null ? pr.service().hostLabel() : "-";
                    tm.addRow(new Object[]{
                            "#" + pr.id(),
                            "CHỜ DUYỆT",
                            pr.branchName(),
                            host,
                            pr.method(),
                            pr.path(),
                            pr.reason(),
                            pr.timeHms()
                    });
                    if (pr.id() == prevId) {
                        selRow = i;
                    }
                }
                if (selRow < 0 && !displayed.isEmpty()) {
                    selRow = 0;
                }
                if (selRow >= 0) {
                    queueTable.setRowSelectionInterval(selRow, selRow);
                } else {
                    queueTable.clearSelection();
                }

                int totalPending = allPending.size();
                emptyHintBar.setVisible(totalPending == 0);
                forwardAllBtn.setEnabled(totalPending > 0);
                dropAllBtn.setEnabled(totalPending > 0);
                if (totalPending > 0) {
                    liveStatusPill.setText("Đang chờ duyệt: " + totalPending);
                    liveStatusPill.setToken(t -> t.danger);
                    liveStatusPill.setIcon(t -> Icons.strategyShield(t.danger.text(), 12));
                } else if (on) {
                    liveStatusPill.setText("Đang BẬT (0 chờ)");
                    liveStatusPill.setToken(t -> t.success);
                    liveStatusPill.setIcon(t -> Icons.strategyShield(t.success.text(), 12));
                } else {
                    liveStatusPill.setText("Đang TẮT");
                    liveStatusPill.setToken(t -> t.neutral);
                    liveStatusPill.setIcon(t -> Icons.strategyShield(t.muted, 12));
                }
            } finally {
                syncingUi[0] = false;
            }
            syncSelectionEditor.run();
        };
        refreshHolder[0] = refresh;

        branchFilter.addActionListener(Ui.safe(e -> {
            if (!syncingUi[0]) refresh.run();
        }));
        hostFilter.addActionListener(Ui.safe(e -> {
            if (!syncingUi[0]) refresh.run();
        }));
        filterSearch.onChange(s -> {
            if (!syncingUi[0]) refresh.run();
        });

        queueTable.getSelectionModel().addListSelectionListener(ev -> {
            if (!ev.getValueIsAdjusting() && !syncingUi[0]) {
                syncSelectionEditor.run();
            }
        });

        masterSwitch.onChange(on -> {
            if (syncingUi[0]) {
                return;
            }
            engine.setInterceptWaitEnabled(on);
            refresh.run();
            if (ui != null) ui.syncHeader();
        });

        allScopeChk.addActionListener(Ui.safe(e -> {
            if (syncingUi[0]) {
                return;
            }
            ae.setInterceptWaitAllScope(allScopeChk.isSelected());
            refresh.run();
            if (ui != null) ui.syncHeader();
        }));

        methodBar.onChange(selMethods -> {
            if (syncingUi[0]) {
                return;
            }
            for (String m : ActionInterceptRule.ALL_METHODS) {
                ae.toggleInterceptWaitMethod(m, selMethods.contains(m));
            }
            if (ui != null) ui.syncHeader();
        });

        pinBranchBtn.addActionListener(Ui.safe(e -> {
            String curBranch = engine.activeBranch().name();
            engine.toggleBranchInterceptWait(curBranch, true);
            refresh.run();
            if (ui != null) {
                ui.syncHeader();
                ui.flash("Đã bật Chặn chờ duyệt cho nhánh: " + curBranch);
            }
        }));

        pinApiBtn.addActionListener(Ui.safe(e -> {
            ApiGroup curApi = (ui != null && ui.api != null) ? ui.api.currentGroup() : null;
            if (curApi == null) {
                return;
            }
            engine.toggleEndpointInterceptWait(curApi, true);
            refresh.run();
            if (ui != null) {
                ui.syncHeader();
                ui.flash("Đã bật Chặn chờ duyệt cho API: " + curApi.method() + " "
                        + ActionEngine.normalizePathTemplate(curApi.path()));
            }
        }));

        rulesBtn.addActionListener(Ui.safe(e -> {
            ApiGroup curApi = (ui != null && ui.api != null) ? ui.api.currentGroup() : null;
            ActionWorkbenchDialog.show(root, engine, curApi, null, ActionWorkbenchDialog.TAB_LEDGER, () -> {
                refresh.run();
                if (ui != null) ui.syncHeader();
            });
        }));

        fmtJsonBtn.addActionListener(Ui.safe(e -> {
            String formatted = RequestComposerDialog.formatJsonBodyInRawHttp(requestEditor.getText());
            if (formatted != null) {
                requestEditor.setText(formatted);
                requestEditor.setCaretPosition(0);
            }
        }));

        resetRawBtn.addActionListener(Ui.safe(e -> {
            int row = queueTable.getSelectedRow();
            if (row >= 0 && row < displayed.size()) {
                PendingRequest pr = displayed.get(row);
                draftEdits.remove(pr.id());
                requestEditor.setText(new String(pr.originalRequest(), StandardCharsets.ISO_8859_1));
                requestEditor.setCaretPosition(0);
            }
        }));

        forwardBtn.addActionListener(Ui.safe(e -> resolveSelected(queue, displayed, queueTable, requestEditor, draftEdits,
                ResolutionKind.FORWARD, ui, refresh)));
        holdSaveBtn.addActionListener(Ui.safe(e -> resolveSelected(queue, displayed, queueTable, requestEditor, draftEdits,
                ResolutionKind.HOLD_AND_SPOOF, ui, refresh)));
        dropBtn.addActionListener(Ui.safe(e -> resolveSelected(queue, displayed, queueTable, requestEditor, draftEdits,
                ResolutionKind.DROP, ui, refresh)));

        forwardAllBtn.addActionListener(Ui.safe(e -> {
            int n = queue.releaseAll(ResolutionKind.FORWARD);
            draftEdits.clear();
            refresh.run();
            if (ui != null) {
                ui.syncHeader();
                if (n > 0) ui.flash("Đã gửi " + n + " request lên Server.");
            }
        }));
        dropAllBtn.addActionListener(Ui.safe(e -> {
            int n = queue.releaseAll(ResolutionKind.DROP);
            draftEdits.clear();
            refresh.run();
            if (ui != null) {
                ui.syncHeader();
                if (n > 0) ui.flash("Đã hủy " + n + " request trong hàng chờ.");
            }
        }));

        Runnable queueListener = () -> SwingUtilities.invokeLater(refresh);
        queue.addListener(queueListener);
        ae.policyLedger().addListener(queueListener);

        refresh.run();
        return new View(root, queueTable, requestEditor, pinBtn, refresh, () -> {
            queue.removeListener(queueListener);
            ae.policyLedger().removeListener(queueListener);
        });
    }

    private static final class QueueCellRenderer extends DefaultTableCellRenderer {
        private final int colIndex;

        QueueCellRenderer(int colIndex) {
            this.colIndex = colIndex;
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, sel, focus, row, col);
            Theme th = Theme.of(t.getBackground(), t.getForeground());
            String s = value == null ? "" : value.toString();
            l.setHorizontalAlignment(colIndex == 0 || colIndex == 1 || colIndex == 4 || colIndex == 7
                    ? SwingConstants.CENTER : SwingConstants.LEFT);
            l.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            if (sel) {
                l.setBackground(th.selectionBg);
                l.setForeground(th.selectionFg);
                l.setFont(colIndex == 1 || colIndex == 2 || colIndex == 4 ? th.smallBoldFont : th.smallFont);
                return l;
            }
            l.setBackground(th.zebra[row & 1]);
            switch (colIndex) {
                case 0, 7 -> {
                    l.setFont(th.monoSmallFont);
                    l.setForeground(th.muted);
                }
                case 1 -> {
                    l.setFont(th.smallBoldFont);
                    l.setBackground(th.danger.fill());
                    l.setForeground(th.danger.text());
                }
                case 2 -> {
                    l.setFont(th.smallBoldFont);
                    l.setForeground(th.accent.text());
                }
                case 4 -> {
                    Theme.Token mTok = th.method(s);
                    l.setFont(th.smallBoldFont);
                    l.setBackground(mTok.fill());
                    l.setForeground(mTok.text());
                }
                case 5 -> {
                    l.setFont(th.monoSmallFont);
                    l.setForeground(th.fg);
                }
                default -> {
                    l.setFont(th.smallFont);
                    l.setForeground(th.fg);
                }
            }
            return l;
        }
    }

    private static void resolveSelected(
            ProxyInterceptQueue queue,
            List<PendingRequest> displayed,
            JTable table,
            JTextArea editor,
            Map<Long, String> draftEdits,
            ResolutionKind kind,
            MainPanel ui,
            Runnable refresh) {
        int row = table.getSelectedRow();
        if (row < 0 || row >= displayed.size()) {
            return;
        }
        PendingRequest pr = displayed.get(row);
        byte[] payload = pr.originalRequest();
        if (draftEdits.containsKey(pr.id()) || kind == ResolutionKind.FORWARD) {
            String edited = editor.getText();
            if (edited != null && !edited.isBlank()) {
                payload = normalizeRawHttpRequest(edited);
            }
        }
        draftEdits.remove(pr.id());
        queue.resolve(pr.id(), kind, payload);
        refresh.run();
        if (ui != null) {
            ui.syncHeader();
        }
    }

    /**
     * Normalizes edited HTTP text back to wire-safe CRLF bytes and recalculates {@code Content-Length}
     * when present so edited JSON/form bodies forward cleanly to the Server.
     */
    static byte[] normalizeRawHttpRequest(String edited) {
        if (edited == null) {
            return new byte[0];
        }
        String unified = edited.replace("\r\n", "\n").replace("\r", "\n");
        int splitIdx = unified.indexOf("\n\n");
        if (splitIdx < 0) {
            return unified.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        }
        String headers = unified.substring(0, splitIdx);
        String body = unified.substring(splitIdx + 2);
        byte[] bodyBytes = body.getBytes(StandardCharsets.ISO_8859_1);

        Matcher m = CONTENT_LENGTH_HEADER.matcher(headers);
        if (m.find()) {
            headers = m.replaceFirst("Content-Length: " + bodyBytes.length);
        }
        String wireHead = headers.replace("\n", "\r\n") + "\r\n\r\n";
        byte[] headBytes = wireHead.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[headBytes.length + bodyBytes.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(bodyBytes, 0, out, headBytes.length, bodyBytes.length);
        return out;
    }

    private static JPanel makeRemovableChip(String text, java.util.function.Function<Theme, Theme.Token> tokenFn, Runnable onRemove) {
        Theme t = Theme.current();
        Theme.Token tok = tokenFn.apply(t);
        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        chip.setOpaque(true);
        chip.setBackground(tok.fill());
        chip.setBorder(BorderFactory.createLineBorder(tok.border()));

        JLabel lbl = new JLabel(text);
        lbl.setFont(t.smallBoldFont);
        lbl.setForeground(tok.text());

        JButton rm = new JButton("x");
        rm.setFont(t.smallBoldFont);
        rm.setForeground(tok.text());
        rm.setOpaque(false);
        rm.setContentAreaFilled(false);
        rm.setBorderPainted(false);
        rm.setFocusPainted(false);
        rm.setMargin(new Insets(0, 2, 0, 2));
        rm.setToolTipText("Gỡ khỏi danh sách chặn chờ duyệt");
        rm.addActionListener(Ui.safe(e -> onRemove.run()));

        chip.add(lbl);
        chip.add(rm);
        return chip;
    }
}
