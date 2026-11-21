package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.event.RowSorterEvent;
import javax.swing.event.RowSorterListener;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;

import themythofcrawl.app.Changes;
import themythofcrawl.app.Engine;
import themythofcrawl.core.Audit;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.ProxyInterceptQueue;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ApiTagCatalog;
import themythofcrawl.model.Branch;
import themythofcrawl.model.GroupCluster;
import themythofcrawl.model.ResultState;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SearchField;
import themythofcrawl.ui.kit.SegmentedToggle;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/** 'API' tab: branch tree / table, API list, the selected API's rows (API gốc + roles), grouped Repeater, viewers. */
final class ApiTab {
    /** Column-layout key of the flat API table; V_7.0 bumped it so every user gets the Burp-style layout once. */
    static final String API_COLUMNS_ID = "api.v7";
    final JPanel panel = new JPanel(new BorderLayout());
    private final MainPanel ui;
    private final Engine engine;
    final BranchTree tree;
    final GroupTableModel groupModel;
    final JTable table;
    final BranchTableModel branchModel;
    final JTable branchTable;
    final ResultTableModel resultModel = new ResultTableModel();
    final JTable results;
    private final JTabbedPane leftTabs = new JTabbedPane();
    private final JComboBox<String> viewMode = new JComboBox<>(new String[] {"Tất cả API (Mặc định)", "Theo Nhánh đang chọn"});
    private final EnumSet<AuditLevel> activeFilters = EnumSet.noneOf(AuditLevel.class);
    private final Pill filterAll = new Pill("Tất cả", t -> t.accent).setSolidWhenActive(true).setActive(true);
    private final Map<AuditLevel, Pill> filterPills = new EnumMap<>(AuditLevel.class);
    private final SearchField search = new SearchField("Tìm API (#, method, path)...", 16);
    private final JLabel filterCount = Ui.muted("0 API");
    private final Pill resOkPill = new Pill("OK 0", t -> t.success, t -> Icons.check(t.success.text(), 12));
    private final Pill resInfoPill = new Pill("Lệch 0", t -> t.warning, t -> Icons.alertMark(t.warning.text(), 12));
    private final Pill resWarnPill = new Pill("Bypass 0", t -> t.critical, t -> Icons.warn(t.critical.text(), 12));
    private final Pill resErrPill = new Pill("Lỗi 0", t -> t.danger, t -> Icons.error(t.danger.text(), 12));
    private final JLabel resMetaLabel = Ui.muted("Chưa chọn API");
    private final JButton pill = new JButton("Chưa chọn API Group") {
        @Override
        public void updateUI() {
            super.updateUI();
            Theme t = Theme.current();
            setBackground(t.surfaceAlt);
            setForeground(t.fg);
            setFont(t.boldFont);
        }
    };
    private final JPanel roleTabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1)) {
        @Override
        public void updateUI() {
            super.updateUI();
            if (ApiTab.this.ui != null) {
                refreshBar();
            }
        }
    };
    private final JLabel reqHeader = Ui.bold(" Request");
    private final JLabel respHeader = Ui.bold(" Response");
    private final UiHost.Viewer reqViewer;
    private final UiHost.Viewer respViewer;
    private final JComboBox<String> pathEditor = new JComboBox<>();
    private JButton filterDropdownBtn;
    private JButton focusBtn;
    private JPanel focusBanner;
    private JLabel focusLabel;
    private SegmentedToggle focusScopeToggle;
    private String focusPath;
    private boolean focusRecursive;
    private boolean expandedTabs = false;
    private boolean toolbarCompact = false;
    private JButton compactToolsDropdownBtn;
    private java.awt.Component[] secondaryToolbarBtns = new java.awt.Component[0];
    private boolean inspectorExpanded = false;
    private JPanel inspectorHostBar;
    private JPanel compactInspectorBar;
    private JPanel fullInspectorBar;
    private JPanel heldStrip;
    private JLabel heldStripLabel;
    private ProxyInterceptQueue.PendingRequest shownHeld;
    private int lastKnownHeldCount;
    private JButton compactSummaryBtn;
    private JLabel compactMiniStats;
    private JSplitPane topSplit;
    private JSplitPane masterSplit;
    private JPanel leftPane;
    private JComponent detailPane;
    private JComponent toolbarPane;
    private JPanel restoreStrip;
    private javax.swing.JScrollPane treeScroll;
    private final java.util.EnumMap<Pane, Integer> lastDivider = new java.util.EnumMap<>(Pane.class);
    private boolean treeStale = true;
    private boolean branchStale = true;
    private boolean selecting;

    ApiTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        tree = new BranchTree(engine);
        tree.onMove(ui::moveViaDragDrop);
        groupModel = new GroupTableModel(engine, g -> engine.hits().count(g.target()));
        table = new ThemedTable(groupModel);
        branchModel = new BranchTableModel(engine);
        branchTable = new ThemedTable(branchModel);
        results = new ThemedTable(resultModel);
        reqViewer = ui.host.requestViewer(true);
        respViewer = ui.host.responseViewer();
        initFilterPills();
        JPanel northWrap = new JPanel(new BorderLayout(0, 0));
        toolbarPane = actionBar();
        JPanel toolbarHost = new JPanel(new BorderLayout(0, 0));
        toolbarHost.add(toolbarPane, BorderLayout.NORTH);
        restoreStrip = buildRestoreStrip();
        toolbarHost.add(restoreStrip, BorderLayout.SOUTH);
        northWrap.add(toolbarHost, BorderLayout.NORTH);
        northWrap.add(buildFocusBanner(), BorderLayout.SOUTH);
        panel.add(northWrap, BorderLayout.NORTH);

        // left: branch tree + branch table (compact, no redundant bottom bar)
        tree.onChip((c, g, t, x, y) -> ui.chip(c, g, t, x, y));
        tree.tree.addTreeSelectionListener(e -> onTreeSelected());
        tree.tree.addMouseListener(popup(tree.tree, this::treePopup));
        Ui.key(tree.tree, "ctrl shift C", () -> {
            ui.useSource(tree);
            ui.copy(ui.scope().groups());
        });
        Ui.style(branchTable);
        branchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        branchTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onBranchRowSelected();
            }
        });
        AuditRenderer br = new AuditRenderer(branchModel, null, 3, null).numberCols(2);
        for (int i = 0; i < branchTable.getColumnCount(); i++) {
            branchTable.getColumnModel().getColumn(i).setCellRenderer(br);
        }
        Ui.widths(branchTable, 64, 165, 54, 165, 115);
        ColumnMemory.install(branchTable, "branches", ui.uiPrefs);
        Ui.onPopup(branchTable, e -> branchMenu().show(e.getComponent(), e.getX(), e.getY()));
        TreeColumns.enable(tree.tree);
        treeScroll = MainPanel.scroll(tree.tree);
        leftTabs.addTab("Cây Sub-folder", treeScroll);
        leftTabs.addTab("Bảng Nhánh", MainPanel.scroll(branchTable));
        leftTabs.addChangeListener(e -> refreshVisible(true));
        JPanel left = new JPanel(new BorderLayout(0, 0));
        left.add(leftTabs, BorderLayout.CENTER);
        leftPane = left;
        left.setPreferredSize(new Dimension(Theme.current().scaled(300), 0));
        left.setMinimumSize(new Dimension(Theme.current().scaled(160), 0));
        // V_7.0: the tree is a branch filter (Site-map style) by default; API rows live in the flat table.
        tree.setShowApis("1".equals(ui.uiPrefs.get(KEY_TREE_SHOW_APIS)));
        if (tree.showApis()) {
            TreeColumns.installHeader(tree.tree, treeScroll);
        }

        // right: the API table (filter & search merged into top actionBar)
        Ui.style(table);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !selecting) {
                ui.useSource(table);
                List<ApiGroup> sel = selectedGroups();
                showGroup(sel.isEmpty() ? null : sel.get(0));
            }
        });
        Ui.onPopup(table, e -> groupMenu().show(e.getComponent(), e.getX(), e.getY()));
        Ui.key(table, "ctrl shift C", () -> {
            ui.useSource(table);
            ui.copy(selectedGroups());
        });
        AuditRenderer gr = new AuditRenderer(groupModel, 7, 3, 4, 0)
                .pathCol(6, r -> {
                    ApiGroup g = groupModel.at(r);
                    return g == null ? null : g.url();
                })
                .numberCols(0, 8)
                .timeCols(12);
        int[] widths = {56, 62, 190, 210, 70, 150, 340, 58, 72, 95, 62, 62, 68, 220, 56, 96, 70, 150, 220};
        TableColumnModel cm = table.getColumnModel();
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);   // keep readable widths, scroll sideways
        for (int i = 0; i < GroupTableModel.COLS.length; i++) {
            cm.getColumn(i).setPreferredWidth(widths[i]);
            cm.getColumn(i).setCellRenderer(gr);
        }
        cm.getColumn(GroupTableModel.COPY_COL).setMinWidth(52);
        cm.getColumn(GroupTableModel.COPY_COL).setMaxWidth(60);
        cm.getColumn(GroupTableModel.COPY_COL).setCellRenderer(new AuditRenderer(groupModel, null, null, null, AuditRenderer.Mode.COPY)
                .tooltip(r -> "Bấm để copy: " + engine.copyLabel(groupModel.at(r), false)));
        cm.getColumn(GroupTableModel.EXCEL_COL).setMinWidth(84);
        cm.getColumn(GroupTableModel.EXCEL_COL).setCellRenderer(new AuditRenderer(groupModel, null, null, null, AuditRenderer.Mode.LINK)
                .tooltip(r -> excelTip(groupModel.at(r))));
        cm.getColumn(GroupTableModel.DUP_COL).setMinWidth(62);
        cm.getColumn(GroupTableModel.DUP_COL).setCellRenderer(new AuditRenderer(groupModel, null, null, null, AuditRenderer.Mode.DUP)
                .tooltip(r -> groupModel.at(r) != null && groupModel.at(r).dupTotal() > 1 ? "API trùng: bấm để xem nhanh các bản" : null));
        pathEditor.setEditable(true);
        cm.getColumn(GroupTableModel.PATH_COL).setCellEditor(new DefaultCellEditor(pathEditor));
        cm.getColumn(GroupTableModel.ROLES_COL).setCellRenderer(new RoleStripRenderer(groupModel));
        // V_7.0: Burp HTTP History column order (model indexes unchanged):
        //   # | Method | Host | Path | Status | Length | Kết quả Role | Nhánh | Đánh giá | Time | Excel | Trùng | (ẩn ...)
        applyViewOrder(cm, GroupTableModel.GID_COL, GroupTableModel.METHOD_COL, GroupTableModel.HOST_COL,
                GroupTableModel.URL_COL, GroupTableModel.STATUS_COL, GroupTableModel.LENGTH_COL,
                GroupTableModel.ROLES_COL, GroupTableModel.PATH_COL, GroupTableModel.AUDIT_COL,
                GroupTableModel.RESEND_COL, GroupTableModel.TIME_COL, GroupTableModel.EXCEL_COL, GroupTableModel.DUP_COL);
        ColumnMemory.install(table, API_COLUMNS_ID, ui.uiPrefs,
                GroupTableModel.COPY_COL, GroupTableModel.GROUP_COL, GroupTableModel.ROLES_TEXT_COL,
                GroupTableModel.SENT_COL, GroupTableModel.SOURCE_COL, GroupTableModel.COPIED_COL);
        MouseAdapter cells = new MouseAdapter() {
            private int[] cell(MouseEvent e) {
                int vr = table.rowAtPoint(e.getPoint());
                int vc = table.columnAtPoint(e.getPoint());
                if (vr < 0 || vc < 0) {
                    return null;
                }
                int mc = table.convertColumnIndexToModel(vc);
                return mc == GroupTableModel.COPY_COL || mc == GroupTableModel.EXCEL_COL || mc == GroupTableModel.DUP_COL
                        ? new int[] {table.convertRowIndexToModel(vr), mc} : null;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int[] c = cell(e);
                if (c == null || e.getClickCount() != 1 || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                ApiGroup g = groupModel.at(c[0]);
                BranchTree.Chip chip = c[1] == GroupTableModel.COPY_COL ? BranchTree.Chip.COPY
                        : c[1] == GroupTableModel.EXCEL_COL ? BranchTree.Chip.EXCEL : BranchTree.Chip.DUP;
                ui.chip(chip, g, table, e.getX(), e.getY());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                table.setCursor(Cursor.getPredefinedCursor(cell(e) != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        };
        table.addMouseListener(cells);
        table.addMouseMotionListener(cells);
        JPanel groupPanel = new JPanel(new BorderLayout(0, 0));
        groupPanel.add(MainPanel.scroll(table), BorderLayout.CENTER);
        JSplitPane top = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, groupPanel);
        top.setResizeWeight(0.22);
        topSplit = top;
        installTableDrag();

        // bottom: API gốc + roles, single compact Repeater/summary bar, viewers
        Ui.style(results);
        results.setRowSorter(new PinnedOrigRowSorter(resultModel));
        results.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        results.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showResult(selectedResult());
                refreshBar();
            }
        });
        Ui.onDoubleClick(results, this::pushSelectedRoles);
        Ui.onPopup(results, e -> resultMenu().show(e.getComponent(), e.getX(), e.getY()));
        AuditRenderer rr = new AuditRenderer(resultModel, 4, 2, null).numberCols(5, 7, 8).deltaCol(6);
        for (int i = 0; i < results.getColumnCount(); i++) {
            results.getColumnModel().getColumn(i).setCellRenderer(rr);
        }
        Ui.widths(results, 56, 150, 185, 68, 68, 92, 74, 94, 68, 250);
        ColumnMemory.install(results, "results", ui.uiPrefs);
        JPanel resPanel = new JPanel(new BorderLayout());
        resPanel.add(MainPanel.scroll(results), BorderLayout.CENTER);

        for (Pill p : new Pill[] {resOkPill, resInfoPill, resWarnPill, resErrPill}) {
            p.setPadding(1, 6, 1, 6);
        }
        pill.setIcon(Icons.themed(t -> Icons.pair(Icons.folder(t.fg),
                Icons.chevronDown(t.fg, 10), 4)));
        pill.addActionListener(Ui.safe(e -> pillMenu()));
        JScrollPane tabsScroll = new JScrollPane(roleTabs, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tabsScroll.setBorder(BorderFactory.createEmptyBorder());
        tabsScroll.setPreferredSize(new Dimension(240, 26));
        tabsScroll.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 6));
        tabsScroll.getHorizontalScrollBar().setUnitIncrement(40);
        JButton sendOne = Ui.accent("Send Tab này", t -> Icons.send(t.critical.onSolid()), t -> t.critical, this::sendCurrentTab);
        JButton composerBtn = Ui.button("Sửa & Gửi", t -> Icons.edit(t.info.text()), () -> {
            ApiGroup cur = currentGroup();
            if (cur != null) {
                RequestComposerDialog.showFloating(panel, engine, cur, selectedResult(), () -> {
                    resultModel.refresh();
                    refreshBar();
                });
            }
        });
        composerBtn.setToolTipText("Mở trình soạn thảo Request (Composer) để chỉnh sửa Raw HTTP và gửi lại trực tiếp");
        JButton[] repActionRef = new JButton[1];
        JButton repActionBtn = Ui.dropdown("Bảo vệ dữ liệu", t -> Icons.withChevron(Icons.workbench(t.accent.text(), 14), t.fg),
                "Bảo vệ dữ liệu: chặn / hỏi trước / giữ lại khi crawl, cách gửi lại khi ID đã bị xóa, Thử luật", () -> {
                    JPopupMenu m = new JPopupMenu();
                    ApiGroup cur = currentGroup();
                    if (cur != null) {
                        ActionMenus.populateApiActionItems(m, panel, engine, cur, selectedResults(), () -> {
                            resultModel.refresh();
                            refreshBar();
                        });
                    } else {
                        ActionMenus.populateToolbarDropdown(m, panel, engine, this::currentGroup, this::refreshBar);
                    }
                    m.show(repActionRef[0], 0, repActionRef[0].getHeight());
                });
        repActionRef[0] = repActionBtn;
        JButton[] barMoreRef = new JButton[1];
        JButton barMore = Ui.dropdown("Thao tác", null, "Gửi cả Group, mở Composer, đẩy Repeater hoặc khôi phục Request", () -> {
            JPopupMenu m = new JPopupMenu();
            ApiGroup cur = currentGroup();
            if (cur != null) {
                m.add(Ui.item("Sửa Request & Gửi lại (Composer)...", t -> Icons.edit(t.info.text()),
                        () -> RequestComposerDialog.showFloating(panel, engine, cur, selectedResult(), () -> {
                            resultModel.refresh();
                            refreshBar();
                        })));
            }
            m.add(Ui.item("Gửi toàn bộ Roles của API Group này (Send Group)", Icons::sendAll,
                    () -> ui.send(listOf(currentGroup()))));
            if (cur != null) {
                m.add(ActionMenus.buildForApi(panel, engine, cur, selectedResults(), () -> {
                    resultModel.refresh();
                    refreshBar();
                }));
            }
            m.addSeparator();
            m.add(Ui.item("Đẩy Tab Role hiện tại -> Burp Repeater", Icons::repeater, this::pushSelectedRoles));
            m.add(Ui.item("Đẩy cả API Group -> Burp Repeater", Icons::repeater, () -> ui.push(currentGroup())));
            m.addSeparator();
            m.add(Ui.item(expandedTabs ? "Ẩn dải nút Tab Role ngang (Gọn giao diện)" : "Hiện dải nút Tab Role ngang",
                    Icons::sliders, () -> {
                        expandedTabs = !expandedTabs;
                        refreshBar();
                    }));
            m.add(Ui.item("Khôi phục Request về trạng thái ban đầu", Icons::undo, this::resetRequest));
            m.show(barMoreRef[0], 0, barMoreRef[0].getHeight());
        });
        barMoreRef[0] = barMore;
        JPanel barLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        barLeft.setOpaque(false);
        JButton collapseInspectorBtn = new JButton();
        collapseInspectorBtn.setIcon(Icons.themed(t -> Icons.chevronUp(t.fg, 12)));
        collapseInspectorBtn.setMargin(new Insets(0, 0, 0, 0));
        collapseInspectorBtn.setPreferredSize(new Dimension(22, 22));
        collapseInspectorBtn.setToolTipText("Thu gọn thanh Request / Response thành 1 đường ngang tối giản");
        collapseInspectorBtn.addActionListener(Ui.safe(e -> setInspectorExpanded(false)));
        barLeft.add(collapseInspectorBtn);
        barLeft.add(pill);
        barLeft.add(resOkPill);
        barLeft.add(resInfoPill);
        barLeft.add(resWarnPill);
        barLeft.add(resErrPill);
        barLeft.add(resMetaLabel);
        JPanel barRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 1));
        barRight.setOpaque(false);
        barRight.add(sendOne);
        barRight.add(composerBtn);
        barRight.add(repActionBtn);
        barRight.add(barMore);

        fullInspectorBar = new JPanel(new BorderLayout(4, 0));
        fullInspectorBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.current().border),
                BorderFactory.createEmptyBorder(2, 4, 2, 4)));
        fullInspectorBar.add(barLeft, BorderLayout.CENTER);
        fullInspectorBar.add(barRight, BorderLayout.EAST);

        // Ultra-slim 24px collapsed Request/Response Inspector bar (default state)
        JButton expandInspectorBtn = new JButton();
        expandInspectorBtn.setIcon(Icons.themed(t -> Icons.chevronRight(t.fg, 12)));
        expandInspectorBtn.setMargin(new Insets(0, 0, 0, 0));
        expandInspectorBtn.setPreferredSize(new Dimension(20, 20));
        expandInspectorBtn.setToolTipText("Mở rộng đầy đủ thanh Request / Response & Tab Role");
        expandInspectorBtn.addActionListener(Ui.safe(e -> setInspectorExpanded(true)));

        compactSummaryBtn = new JButton("Chưa chọn API") {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setFont(t.smallBoldFont);
                setForeground(t.fg);
            }
        };
        compactSummaryBtn.setContentAreaFilled(false);
        compactSummaryBtn.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 6));
        compactSummaryBtn.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        compactSummaryBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        compactSummaryBtn.setToolTipText("Bấm để chọn nhanh Role / chuyển API Group");
        compactSummaryBtn.addActionListener(Ui.safe(e -> pillMenu(compactSummaryBtn)));

        compactMiniStats = Ui.muted("");
        compactMiniStats.setFont(Theme.current().smallFont);

        JButton compactSendBtn = Ui.accent("Gửi", t -> Icons.send(t.critical.onSolid(), 11), t -> t.critical, this::sendCurrentTab);
        compactSendBtn.setMargin(new Insets(0, 6, 0, 6));
        compactSendBtn.setPreferredSize(new Dimension(Theme.current().scaled(54), 20));
        compactSendBtn.setToolTipText("Gửi lại Tab Role đang chọn (Send Tab này)");

        JButton[] compactMoreRef = new JButton[1];
        JButton compactMoreBtn = new JButton();
        compactMoreBtn.setIcon(Icons.themed(t -> Icons.withChevron(Icons.menu(t.fg, 12), t.fg)));
        compactMoreBtn.setMargin(new Insets(0, 4, 0, 4));
        compactMoreBtn.setPreferredSize(new Dimension(Theme.current().scaled(32), 20));
        compactMoreBtn.setToolTipText("Mở menu đầy đủ (Action/ID, Send Group, Repeater, Tab Role, Khôi phục)");
        compactMoreBtn.addActionListener(Ui.safe(e -> buildCompactInspectorMenu().show(compactMoreRef[0], 0, compactMoreRef[0].getHeight())));
        compactMoreRef[0] = compactMoreBtn;

        JPanel compactLeft = new JPanel(new BorderLayout(2, 0));
        compactLeft.setOpaque(false);
        compactLeft.add(expandInspectorBtn, BorderLayout.WEST);
        compactLeft.add(compactSummaryBtn, BorderLayout.CENTER);

        JPanel compactRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        compactRight.setOpaque(false);
        compactRight.add(compactMiniStats);
        compactRight.add(compactSendBtn);
        compactRight.add(compactMoreBtn);

        compactInspectorBar = new JPanel(new BorderLayout(6, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBackground(t.surfaceAlt);
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, t.border),
                        BorderFactory.createEmptyBorder(1, 4, 1, 4)));
            }
        };
        compactInspectorBar.setOpaque(true);
        compactInspectorBar.setBackground(Theme.current().surfaceAlt);
        compactInspectorBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.current().border),
                BorderFactory.createEmptyBorder(1, 4, 1, 4)));
        compactInspectorBar.setPreferredSize(new Dimension(0, Theme.current().scaled(24)));
        compactInspectorBar.add(compactLeft, BorderLayout.CENTER);
        compactInspectorBar.add(compactRight, BorderLayout.EAST);

        inspectorHostBar = new JPanel(new BorderLayout(0, 0));
        inspectorHostBar.setOpaque(false);

        heldStrip = buildHeldStrip();

        JButton inlineSaveEditBtn = Ui.secondaryBtn("Lưu sửa", Icons::save, this::saveInlineRequestEdit);
        inlineSaveEditBtn.setToolTipText("Lưu nội dung Request vừa chỉnh sửa vào Role đang chọn (không gửi ngay)");
        JButton inlineFmtJsonBtn = Ui.secondaryBtn("Format JSON", Icons::codeBraces, this::formatInlineRequestJson);
        inlineFmtJsonBtn.setToolTipText("Định dạng lại phần thân JSON trong khung Request cho dễ đọc");
        JPanel reqHeaderBar = new JPanel(new BorderLayout(6, 0));
        reqHeaderBar.setOpaque(false);
        reqHeaderBar.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        reqHeaderBar.add(reqHeader, BorderLayout.CENTER);
        JPanel reqQuickRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        reqQuickRight.setOpaque(false);
        reqQuickRight.add(inlineSaveEditBtn);
        reqQuickRight.add(inlineFmtJsonBtn);
        reqHeaderBar.add(reqQuickRight, BorderLayout.EAST);

        JPanel reqPanel = new JPanel(new BorderLayout());
        reqPanel.add(reqHeaderBar, BorderLayout.NORTH);
        reqPanel.add(reqViewer.component(), BorderLayout.CENTER);
        JPanel respPanel = new JPanel(new BorderLayout());
        respPanel.add(respHeader, BorderLayout.NORTH);
        respPanel.add(respViewer.component(), BorderLayout.CENTER);
        JSplitPane viewers = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqPanel, respPanel);
        viewers.setResizeWeight(0.5);
        JPanel repeaterTop = new JPanel(new BorderLayout(0, 0));
        repeaterTop.setOpaque(false);
        repeaterTop.add(heldStrip, BorderLayout.NORTH);
        repeaterTop.add(inspectorHostBar, BorderLayout.SOUTH);
        JPanel repeater = new JPanel(new BorderLayout(0, 0));
        repeater.add(repeaterTop, BorderLayout.NORTH);
        repeater.add(viewers, BorderLayout.CENTER);

        // Apply saved (default collapsed) inspector state
        this.inspectorExpanded = UiPrefs.inspectorExpanded(ui.uiPrefs);
        applyInspectorBarState(false);

        JSplitPane detail = new JSplitPane(JSplitPane.VERTICAL_SPLIT, resPanel, repeater);
        detail.setResizeWeight(0.30);
        JSplitPane master = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, detail);
        master.setResizeWeight(0.55);
        masterSplit = master;
        detailPane = detail;
        panel.add(master, BorderLayout.CENTER);
        restoreLayout();
    }

    // =================================================================== V_7.0 hideable layout
    /** Parts of the API tab that can be hidden to give the table the whole screen. */
    enum Pane { TREE, DETAIL, TOOLBAR }

    static final String KEY_LAYOUT = "tmoc.ui.layout.v7";
    static final String KEY_TREE_SHOW_APIS = "tmoc.ui.tree.showApis.v7";

    boolean paneVisible(Pane p) {
        JComponent c = paneComponent(p);
        return c != null && c.isVisible();
    }

    private JComponent paneComponent(Pane p) {
        return switch (p) {
            case TREE -> leftPane;
            case DETAIL -> detailPane;
            case TOOLBAR -> toolbarPane;
        };
    }

    private JSplitPane paneSplit(Pane p) {
        return switch (p) {
            case TREE -> topSplit;
            case DETAIL -> masterSplit;
            case TOOLBAR -> null;
        };
    }

    void togglePane(Pane p) {
        setPaneVisible(p, !paneVisible(p));
    }

    void setPaneVisible(Pane p, boolean on) {
        setPaneVisible(p, on, true);
    }

    private void setPaneVisible(Pane p, boolean on, boolean persist) {
        JComponent c = paneComponent(p);
        if (c == null || c.isVisible() == on) {
            return;
        }
        JSplitPane sp = paneSplit(p);
        if (sp != null && !on && sp.getDividerLocation() > 0) {
            lastDivider.put(p, sp.getDividerLocation());
        }
        c.setVisible(on);
        if (p == Pane.TOOLBAR && restoreStrip != null) {
            restoreStrip.setVisible(!on);
        }
        if (sp != null && on) {
            Integer loc = lastDivider.get(p);
            if (loc != null && loc > 0) {
                sp.setDividerLocation(loc);
            } else {
                sp.resetToPreferredSizes();
            }
        }
        panel.revalidate();
        panel.repaint();
        if (persist) {
            saveLayout();
        }
    }

    /** Bố cục sẵn: crawl (cây + bảng), analyze (bảng + chi tiết), max (chỉ bảng), all (hiện hết). */
    void applyPreset(String preset) {
        switch (preset) {
            case "crawl" -> {
                setPaneVisible(Pane.TREE, true, false);
                setPaneVisible(Pane.DETAIL, false, false);
                setPaneVisible(Pane.TOOLBAR, true, false);
            }
            case "analyze" -> {
                setPaneVisible(Pane.TREE, false, false);
                setPaneVisible(Pane.DETAIL, true, false);
                setPaneVisible(Pane.TOOLBAR, true, false);
            }
            case "max" -> {
                setPaneVisible(Pane.TREE, false, false);
                setPaneVisible(Pane.DETAIL, false, false);
                setPaneVisible(Pane.TOOLBAR, false, false);
                ui.setHeaderVisible(false);
            }
            default -> {
                setPaneVisible(Pane.TREE, true, false);
                setPaneVisible(Pane.DETAIL, true, false);
                setPaneVisible(Pane.TOOLBAR, true, false);
                ui.setHeaderVisible(true);
            }
        }
        saveLayout();
    }

    /** True when everything but the API table is hidden. */
    boolean maximized() {
        return !paneVisible(Pane.TREE) && !paneVisible(Pane.DETAIL) && !paneVisible(Pane.TOOLBAR) && !ui.headerVisible();
    }

    void toggleMaximized() {
        applyPreset(maximized() ? "all" : "max");
    }

    void saveLayout() {
        ui.uiPrefs.set(KEY_LAYOUT, "tree=" + (paneVisible(Pane.TREE) ? 1 : 0)
                + ";detail=" + (paneVisible(Pane.DETAIL) ? 1 : 0)
                + ";toolbar=" + (paneVisible(Pane.TOOLBAR) ? 1 : 0)
                + ";header=" + (ui.headerVisible() ? 1 : 0));
    }

    private void restoreLayout() {
        String raw = ui.uiPrefs.get(KEY_LAYOUT);
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(";")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            boolean on = !"0".equals(kv[1].trim());
            switch (kv[0].trim()) {
                case "tree" -> setPaneVisible(Pane.TREE, on, false);
                case "detail" -> setPaneVisible(Pane.DETAIL, on, false);
                case "toolbar" -> setPaneVisible(Pane.TOOLBAR, on, false);
                default -> { }
            }
        }
    }

    void setTreeShowsApis(boolean on) {
        tree.setShowApis(on);
        if (treeScroll != null) {
            if (on) {
                TreeColumns.installHeader(tree.tree, treeScroll);
            } else {
                treeScroll.setColumnHeaderView(null);
            }
        }
        ui.uiPrefs.set(KEY_TREE_SHOW_APIS, on ? "1" : "0");
        treeStale = true;
        refreshVisible(true);
    }

    JPopupMenu layoutMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(layoutCheck("Cây nhánh bên trái", "Ctrl+Alt+1", paneVisible(Pane.TREE), () -> togglePane(Pane.TREE)));
        m.add(layoutCheck("Khung chi tiết (Role + Request/Response)", "Ctrl+Alt+2", paneVisible(Pane.DETAIL),
                () -> togglePane(Pane.DETAIL)));
        m.add(layoutCheck("Thanh công cụ tab API", "Ctrl+Alt+3", paneVisible(Pane.TOOLBAR), () -> togglePane(Pane.TOOLBAR)));
        m.add(layoutCheck("Thanh trên cùng của extension", "Ctrl+Alt+H", ui.headerVisible(), () -> {
            ui.setHeaderVisible(!ui.headerVisible());
            saveLayout();
        }));
        m.addSeparator();
        m.add(layoutCheck("Hiện từng API trong cây (kiểu cũ)", null, tree.showApis(), () -> setTreeShowsApis(!tree.showApis())));
        m.addSeparator();
        m.add(Ui.item("Bố cục Crawl: Cây + Bảng", Icons::folder, () -> applyPreset("crawl")));
        m.add(Ui.item("Bố cục Phân tích: Bảng + Chi tiết", Icons::sliders, () -> applyPreset("analyze")));
        m.add(Ui.item("Bố cục Tối đa: chỉ Bảng API (Ctrl+Alt+0)", Icons::window, () -> applyPreset("max")));
        m.add(Ui.item("Hiện tất cả", Icons::undo, () -> applyPreset("all")));
        return m;
    }

    /** Slim strip shown only while the toolbar is hidden, so the layout can always be restored with the mouse. */
    private JPanel buildRestoreStrip() {
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton[] ref = new JButton[1];
        JButton layout = Ui.dropdown("Bố cục", t -> Icons.withChevron(Icons.window(t.fg, 12), t.fg),
                "Đang ẩn thanh công cụ — bấm để hiện lại hoặc đổi bố cục (Ctrl+Alt+0 để thoát chế độ Tối đa)",
                () -> layoutMenu().show(ref[0], 0, ref[0].getHeight()));
        ref[0] = layout;
        layout.setMargin(new Insets(0, 4, 0, 4));
        strip.add(layout);
        strip.add(Ui.muted("Ctrl+Alt+0: thoát Tối đa · Ctrl+Alt+1/2/3/H: ẩn/hiện Cây / Chi tiết / Công cụ / Thanh trên"));
        strip.setVisible(false);
        return strip;
    }

    private static javax.swing.JCheckBoxMenuItem layoutCheck(String text, String accel, boolean on, Runnable action) {
        javax.swing.JCheckBoxMenuItem it = new javax.swing.JCheckBoxMenuItem(accel == null ? text : text + "   (" + accel + ")", on);
        it.addActionListener(Ui.safe(e -> action.run()));
        return it;
    }

    /** Drag API rows from the flat table onto a branch of the tree to move them (Undo toast as before). */
    private void installTableDrag() {
        table.setTransferHandler(new javax.swing.TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY_OR_MOVE;
            }

            @Override
            protected java.awt.datatransfer.Transferable createTransferable(JComponent c) {
                return BranchTree.transferableFor(selectedGroups());
            }
        });
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            table.setDragEnabled(true);
        }
    }

    private JPanel buildHeldStrip() {
        heldStripLabel = new JLabel("CHỜ DUYỆT");
        heldStripLabel.setIcon(Icons.themed(t -> Icons.strategyShield(t.danger.text(), 13)));
        heldStripLabel.setIconTextGap(6);

        JButton fwdBtn = Ui.solidPrimary("Gửi lên Server", t -> Icons.send(t.accent.onSolid()),
                () -> resolveShownHeld(ProxyInterceptQueue.ResolutionKind.FORWARD));
        JButton draftBtn = Ui.tonalBtn("Lưu nháp", t -> Icons.strategyShield(t.warning.text(), 13), t -> t.warning,
                () -> resolveShownHeld(ProxyInterceptQueue.ResolutionKind.HOLD_AND_SPOOF));
        JButton dropBtn = Ui.dangerBtn("Hủy (Drop)", t -> Icons.stop(t.danger.text()),
                () -> resolveShownHeld(ProxyInterceptQueue.ResolutionKind.DROP));
        JButton fmtBtn = Ui.secondaryBtn("Format JSON", Icons::codeBraces, this::formatInlineRequestJson);
        JButton queueBtn = Ui.secondaryBtn("Hàng chờ chi tiết...", Icons::sliders, () -> ProxyInterceptDialog.showFloating(ui));

        JPanel strip = new JPanel(new BorderLayout(8, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBackground(t.danger.fill());
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 1, 0, t.danger.border()),
                        BorderFactory.createEmptyBorder(3, 8, 3, 6)));
                if (heldStripLabel != null) {
                    heldStripLabel.setFont(t.smallBoldFont);
                    heldStripLabel.setForeground(t.danger.text());
                }
            }
        };
        strip.setOpaque(true);
        strip.setBackground(Theme.current().danger.fill());
        strip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.current().danger.border()),
                BorderFactory.createEmptyBorder(3, 8, 3, 6)));
        heldStripLabel.setFont(Theme.current().smallBoldFont);
        heldStripLabel.setForeground(Theme.current().danger.text());
        heldStripLabel.setMinimumSize(new Dimension(0, 18));
        strip.add(heldStripLabel, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        actions.add(fwdBtn);
        actions.add(draftBtn);
        actions.add(dropBtn);
        actions.add(fmtBtn);
        actions.add(queueBtn);
        strip.add(actions, BorderLayout.EAST);
        strip.setVisible(false);
        return strip;
    }

    private void showHeldRequest(ProxyInterceptQueue.PendingRequest pr) {
        shownHeld = pr;
        if (pr == null) {
            if (heldStrip != null) {
                heldStrip.setVisible(false);
            }
            return;
        }
        String host = pr.service() != null ? pr.service().hostLabel() : "";
        heldStripLabel.setText("[CHỜ DUYỆT · CHƯA GỬI] #" + pr.id() + "  " + pr.method() + " "
                + Text.shortPath(pr.path(), 42)
                + (host.isEmpty() ? "" : "  (" + host + ")")
                + "  ·  Nhánh: " + pr.branchName());
        heldStripLabel.setToolTipText(pr.reason());
        heldStrip.setVisible(true);
        reqHeader.setText(" Request [CHỜ DUYỆT #" + pr.id() + "] — Sửa trực tiếp rồi bấm Gửi lên Server");
        respHeader.setText(" Response · CHƯA GỬI LÊN SERVER");
        reqViewer.show(pr.service(), pr.originalRequest());
        respViewer.show(pr.service(), TreeTab.heldPreviewBytes(pr));
        panel.revalidate();
        panel.repaint();
    }

    private void resolveShownHeld(ProxyInterceptQueue.ResolutionKind kind) {
        ProxyInterceptQueue.PendingRequest pr = shownHeld;
        if (pr == null) {
            return;
        }
        byte[] payload = pr.originalRequest();
        byte[] edited = reqViewer.edited();
        if (edited != null && edited.length > 0) {
            payload = ProxyInterceptDialog.normalizeRawHttpRequest(
                    new String(edited, java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        shownHeld = null;
        heldStrip.setVisible(false);
        engine.proxyQueue().resolve(pr.id(), kind, payload);
        ui.syncHeader();
        String msg = switch (kind) {
            case FORWARD -> "Đã gửi request #" + pr.id() + " (" + pr.method() + " " + pr.path() + ") lên Server.";
            case HOLD_AND_SPOOF -> "Đã lưu nháp request #" + pr.id() + " vào cây (không đẩy Server).";
            case DROP -> "Đã hủy (Drop) request #" + pr.id() + ".";
            default -> "Đã xử lý request #" + pr.id() + ".";
        };
        ui.flash(msg);
    }

    private void saveInlineRequestEdit() {
        if (shownHeld != null) {
            ui.flash("Request đang ở trạng thái CHỜ DUYỆT — hãy bấm 'Gửi lên Server' hoặc 'Lưu nháp'.");
            return;
        }
        ApiGroup g = currentGroup();
        RoleResult r = selectedResult();
        if (g == null || r == null) {
            ui.info("Chọn một API / Role trước khi lưu chỉnh sửa.");
            return;
        }
        byte[] edited = reqViewer.edited();
        if (edited == null || edited.length == 0) {
            ui.info("Khung Request đang trống.");
            return;
        }
        byte[] wire = ProxyInterceptDialog.normalizeRawHttpRequest(
                new String(edited, java.nio.charset.StandardCharsets.ISO_8859_1));
        engine.setCustomRequest(g, r, wire);
        reqViewer.show(g.service(), wire);
        ui.flash("Đã lưu chỉnh sửa Request cho " + Text.groupId(g.gid()) + " · " + r.roleName());
    }

    private void formatInlineRequestJson() {
        byte[] cur = reqViewer.edited();
        if (cur == null || cur.length == 0) {
            ui.flash("Chọn 1 API / Role để hiện Request trước rồi bấm Format JSON.");
            return;
        }
        String raw = new String(cur, java.nio.charset.StandardCharsets.ISO_8859_1);
        String formatted = RequestComposerDialog.formatJsonBodyInRawHttp(raw);
        if (formatted != null) {
            byte[] out = ProxyInterceptDialog.normalizeRawHttpRequest(formatted);
            var svc = shownHeld != null ? shownHeld.service() : (currentGroup() != null ? currentGroup().service() : null);
            reqViewer.show(svc, out);
            ui.flash("Đã format JSON trong khung Request.");
        } else {
            ui.flash("Request này không có body JSON hợp lệ để format.");
        }
    }

    private JButton findSendBranch;

    private JPanel actionBar() {
        JButton sendApi = Ui.accent("Gửi API", t -> Icons.send(t.critical.onSolid()), t -> t.critical,
                () -> ui.send(selectedOrScope()));
        themythofcrawl.ui.kit.RichHoverCard.install(sendApi, new themythofcrawl.ui.kit.RichHoverCard.Spec(
                "Gửi API với tất cả Roles",
                "Thực thi các API đang chọn (hoặc phạm vi hiện tại) qua toàn bộ Role đang bật để so sánh phân quyền.",
                "Ctrl+Enter", t -> Icons.send(t.critical.text()), t -> t.critical, null));
        findSendBranch = Ui.button("Gửi Nhánh", t -> Icons.sendAll(t.fg), this::sendBranchSelection);
        JButton[] sendRef = new JButton[1];
        JButton sendMenu = Ui.dropdown("Tùy chọn Gửi", t -> Icons.withChevron(Icons.send(t.fg), t.fg),
                "Tùy chọn gửi request, chọn Role, gửi chưa gửi, hủy hàng đợi (Ctrl+Enter)",
                () -> sendMenu().show(sendRef[0], 0, sendRef[0].getHeight()));
        sendRef[0] = sendMenu;
        JButton[] actionRef = new JButton[1];
        JButton actionMenuBtn = Ui.dropdown("Bảo vệ dữ liệu", t -> Icons.withChevron(Icons.workbench(t.accent.text(), 14), t.fg),
                "Mở Action Workbench 5 tab, Luật Chặn Proxy, Kho ID Sống & Chuỗi Tạo Mới -> Hành Động", () -> {
                    JPopupMenu m = new JPopupMenu();
                    ActionMenus.populateToolbarDropdown(m, panel, engine, this::currentGroup, () -> {
                        resultModel.refresh();
                        refreshBar();
                    });
                    m.show(actionRef[0], 0, actionRef[0].getHeight());
                });
        actionRef[0] = actionMenuBtn;
        JButton[] repRef = new JButton[1];
        JButton repMenu = Ui.dropdown("Repeater", t -> Icons.withChevron(Icons.repeater(t.fg), t.fg), "Đẩy sang Burp Repeater (Ctrl+R)",
                () -> repeaterMenu().show(repRef[0], 0, repRef[0].getHeight()));
        repRef[0] = repMenu;
        JButton[] branchRef = new JButton[1];
        JButton branchBtn = Ui.dropdown("Nhánh", t -> Icons.withChevron(Icons.folder(t.fg), t.fg),
                "Thêm Sub-folder, đặt Đích, đổi tên/xóa Nhánh, xem tất cả",
                () -> branchMenu().show(branchRef[0], 0, branchRef[0].getHeight()));
        branchRef[0] = branchBtn;
        JButton[] toolsRef = new JButton[1];
        JButton tools = Ui.dropdown("Công cụ", t -> Icons.withChevron(Icons.gear(t.fg), t.fg),
                "Copy STT+Path, đổi nhánh, xuất Excel/Matrix, xóa & trợ giúp",
                () -> toolsMenu().show(toolsRef[0], 0, toolsRef[0].getHeight()));
        toolsRef[0] = tools;

        focusBtn = Ui.button("Tập trung", t -> Icons.focus(t.fg, 14), this::toggleFocusFromSelection);
        themythofcrawl.ui.kit.RichHoverCard.install(focusBtn, new themythofcrawl.ui.kit.RichHoverCard.Spec(
                "Chế độ Tập trung theo Nhánh",
                "Chỉ hiển thị các API thuộc nhánh đang chọn để rà soát gọn gàng; bấm lại để quay về toàn bộ danh sách.",
                null, t -> Icons.focus(t.info.text(), 14), t -> t.info,
                () -> focusPath != null ? "Đang tập trung: " + focusPath : "Chế độ xem bình thường"));

        JButton[] compactToolsRef = new JButton[1];
        compactToolsDropdownBtn = Ui.dropdown("Thao tác", t -> Icons.withChevron(Icons.menu(t.fg, 13), t.fg),
                "Menu tổng hợp (Tùy chọn Gửi, Action Workbench & Luật Chặn Proxy, Repeater, Nhánh, Tập trung, Công cụ)",
                () -> buildCompactToolbarMenu().show(compactToolsRef[0], 0, compactToolsRef[0].getHeight()));
        compactToolsRef[0] = compactToolsDropdownBtn;
        compactToolsDropdownBtn.setVisible(false);

        secondaryToolbarBtns = new java.awt.Component[] {sendMenu, actionMenuBtn, repMenu, branchBtn, focusBtn, tools};

        search.setColumns(13);
        search.setToolTipText("Lọc nhanh bảng API theo #, method, path, nhánh, tag hoặc Excel STT (Ctrl+F)");
        search.onChange(q -> {
            groupModel.setTextFilter(q);
            updateFilterStrip();
        });

        JButton[] filterRef = new JButton[1];
        filterDropdownBtn = Ui.dropdown("Lọc: Tất cả", t -> Icons.withChevron(Icons.sliders(t.fg), t.fg),
                "Lọc bảng API theo trạng thái, Tag (REFRESH...) & Phạm vi nhánh",
                () -> filterPopupMenu().show(filterRef[0], 0, filterRef[0].getHeight()));
        filterRef[0] = filterDropdownBtn;

        viewMode.setToolTipText("Chế độ hiển thị bảng API: Tất cả API hoặc chỉ Nhánh đang chọn");
        viewMode.addActionListener(Ui.safe(e -> applyViewMode()));

        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
        leftActions.setOpaque(false);
        leftActions.add(sendApi);
        leftActions.add(compactToolsDropdownBtn);
        for (java.awt.Component comp : secondaryToolbarBtns) {
            leftActions.add(comp);
        }
        JButton[] layoutRef = new JButton[1];
        JButton layoutBtn = Ui.dropdown("Bố cục", t -> Icons.withChevron(Icons.window(t.fg), t.fg),
                "Ẩn / hiện Cây, Khung chi tiết, Thanh công cụ, Thanh trên cùng · Bố cục Crawl / Phân tích / Tối đa",
                () -> layoutMenu().show(layoutRef[0], 0, layoutRef[0].getHeight()));
        layoutRef[0] = layoutBtn;
        JPanel rightMode = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 1));
        rightMode.setOpaque(false);
        rightMode.add(layoutBtn);
        rightMode.add(search);
        rightMode.add(filterDropdownBtn);
        rightMode.add(filterCount);
        JPanel bar = new JPanel(new BorderLayout(4, 1));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        bar.add(leftActions, BorderLayout.WEST);
        bar.add(rightMode, BorderLayout.EAST);
        return bar;
    }

    private JPopupMenu buildCompactToolbarMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Gửi Nhánh đang chọn", Icons::sendAll, this::sendBranchSelection));
        javax.swing.JMenu sendSub = new javax.swing.JMenu("Tùy chọn Gửi");
        for (java.awt.Component c : sendMenu().getComponents()) {
            sendSub.add(c);
        }
        m.add(sendSub);
        m.addSeparator();
        m.add(Ui.item("Hàng chờ Chặn Proxy trực tiếp (Đợi bấm Gửi)...",
                t -> Icons.strategyShield(engine.proxyQueue().pendingCount() > 0 ? t.danger.text() : t.accent.text(), 14),
                () -> ProxyInterceptDialog.showFloating(ui)));
        m.add(Ui.item("Action Workbench (5 tab: Chiến lược, Luật CRUD, Dò Proxy, Hướng dẫn)...", Icons::workbench,
                () -> ActionWorkbenchDialog.show(panel, engine, currentGroup(), null, ActionWorkbenchDialog.TAB_STRATEGY, () -> {
                    resultModel.refresh();
                    refreshBar();
                })));
        m.add(Ui.item("Luật & Phạm vi (CRUD)...", Icons::sliders,
                () -> ActionWorkbenchDialog.show(panel, engine, currentGroup(), null, ActionWorkbenchDialog.TAB_LEDGER, () -> {
                    resultModel.refresh();
                    refreshBar();
                })));
        m.add(Ui.item("Hướng dẫn Action & Ví dụ nhanh...", Icons::info,
                () -> ActionWorkbenchDialog.show(panel, engine, currentGroup(), null, ActionWorkbenchDialog.TAB_GUIDE, () -> {
                    resultModel.refresh();
                    refreshBar();
                })));
        m.addSeparator();
        m.add(Ui.item(focusPath != null ? "Thoát chế độ Tập trung (" + Hierarchy.leaf(focusPath) + ")" : "Bật chế độ Tập trung nhánh đang chọn",
                Icons::focus, this::toggleFocusFromSelection));
        javax.swing.JMenu repSub = new javax.swing.JMenu("Burp Repeater");
        for (java.awt.Component c : repeaterMenu().getComponents()) {
            repSub.add(c);
        }
        m.add(repSub);
        javax.swing.JMenu brSub = new javax.swing.JMenu("Nhánh & Sub-folder");
        for (java.awt.Component c : branchMenu().getComponents()) {
            brSub.add(c);
        }
        m.add(brSub);
        javax.swing.JMenu toolSub = new javax.swing.JMenu("Công cụ & Xuất báo cáo");
        for (java.awt.Component c : toolsMenu().getComponents()) {
            toolSub.add(c);
        }
        m.add(toolSub);
        return m;
    }

    void applyResponsiveChrome(boolean headerCompact, int width) {
        boolean compact = headerCompact || (width > 0 && width < 960);
        this.toolbarCompact = compact;
        if (compactToolsDropdownBtn != null) {
            compactToolsDropdownBtn.setVisible(compact);
            for (java.awt.Component c : secondaryToolbarBtns) {
                c.setVisible(!compact);
            }
            search.setColumns(width > 0 && width < 780 ? 8 : 13);
            panel.revalidate();
            panel.repaint();
        }
    }

    boolean isToolbarCompact() {
        return toolbarCompact;
    }

    boolean isInspectorExpanded() {
        return inspectorExpanded;
    }

    void setInspectorExpanded(boolean expanded) {
        this.inspectorExpanded = expanded;
        UiPrefs.setInspectorExpanded(ui.uiPrefs, expanded);
        applyInspectorBarState(true);
    }

    int inspectorBarHeight() {
        if (inspectorHostBar == null) {
            return 0;
        }
        return inspectorHostBar.getHeight() > 0 ? inspectorHostBar.getHeight() : inspectorHostBar.getPreferredSize().height;
    }

    private void applyInspectorBarState(boolean revalidate) {
        if (inspectorHostBar == null) {
            return;
        }
        inspectorHostBar.removeAll();
        if (inspectorExpanded) {
            inspectorHostBar.add(fullInspectorBar, BorderLayout.NORTH);
            if (expandedTabs) {
                JScrollPane ts = new JScrollPane(roleTabs, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
                ts.setBorder(BorderFactory.createEmptyBorder());
                ts.setPreferredSize(new Dimension(240, 26));
                ts.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 6));
                inspectorHostBar.add(ts, BorderLayout.SOUTH);
            }
            reqHeader.setVisible(true);
            respHeader.setVisible(true);
        } else {
            inspectorHostBar.add(compactInspectorBar, BorderLayout.CENTER);
            reqHeader.setVisible(false);
            respHeader.setVisible(false);
        }
        if (revalidate) {
            inspectorHostBar.revalidate();
            inspectorHostBar.repaint();
            panel.revalidate();
            panel.repaint();
        }
    }

    private JPopupMenu buildCompactInspectorMenu() {
        JPopupMenu m = new JPopupMenu();
        ApiGroup g = currentGroup();
        if (g != null) {
            RoleResult cur = selectedResult();
            for (RoleResult r : g.entries()) {
                String lbl = (r.original() ? "[GỐC] " : "") + r.roleName()
                        + (r.status() != null ? "  —  HTTP " + r.status() : "  —  " + r.state());
                m.add(Ui.item(lbl, t -> Icons.circle(r == cur ? t.accent.text() : t.muted, r == cur),
                        () -> MainPanel.selectRow(results, resultModel.rowOf(r))));
            }
            m.addSeparator();
            m.add(Ui.item("Sửa Request & Gửi lại (Composer)...", t -> Icons.edit(t.info.text()),
                    () -> RequestComposerDialog.showFloating(panel, engine, g, cur, () -> {
                        resultModel.refresh();
                        refreshBar();
                    })));
            m.add(ActionMenus.buildForApi(panel, engine, g, selectedResults(), () -> {
                resultModel.refresh();
                refreshBar();
            }));
            RoleResult curRole = selectedResult();
            m.add(Ui.item("Đánh dấu lỗi..." + (g.openBugCount() > 0 ? "   (đang có " + g.openBugCount() + ")" : ""),
                    t -> Icons.alertMark(t.danger.text(), 13), () -> BugsTab.BugMarkDialog.mark(ui, g, null,
                            curRole != null && !curRole.original() ? curRole.roleKey() : "", "")));
            m.add(Ui.item("Gửi toàn bộ Roles của Group (" + g.results().size() + ")", Icons::sendAll,
                    () -> ui.send(listOf(g))));
            m.add(Ui.item("Đẩy Tab Role hiện tại -> Burp Repeater", Icons::repeater, this::pushSelectedRoles));
            m.add(Ui.item("Đẩy cả API Group -> Burp Repeater", Icons::repeater, () -> ui.push(g)));
            m.add(Ui.item("Khôi phục Request về trạng thái ban đầu", Icons::undo, this::resetRequest));
            m.addSeparator();
        }
        m.add(Ui.item(inspectorExpanded ? "Thu gọn thanh Inspector thành 1 đường ngang" : "Mở rộng thanh Inspector đầy đủ",
                t -> inspectorExpanded ? Icons.chevronUp(t.fg, 12) : Icons.chevronDown(t.fg, 12),
                () -> setInspectorExpanded(!inspectorExpanded)));
        return m;
    }

    private JPanel buildFocusBanner() {
        focusLabel = new JLabel("TẬP TRUNG");
        focusLabel.setIcon(Icons.themed(t -> Icons.focus(t.accent.text(), 14)));
        focusLabel.setIconTextGap(6);
        focusScopeToggle = new SegmentedToggle(
                new SegmentedToggle.Option("Chỉ nhánh này", "Chỉ hiện API nằm trực tiếp trong nhánh đang tập trung", t -> t.accent, null),
                new SegmentedToggle.Option("Kèm nhánh con", "Hiện cả API trong các nhánh con bên dưới", t -> t.accent, null)
        ).setFixedHeight(20).onChange(idx -> {
            if (focusPath != null) {
                setFocusBranch(focusPath, idx == 1);
            }
        });
        JButton exitBtn = Ui.button("Thoát", t -> Icons.error(t.danger.text(), 12), this::clearFocusBranch);
        exitBtn.setToolTipText("Thoát chế độ Tập trung, quay lại chế độ bình thường");
        exitBtn.setPreferredSize(new Dimension(exitBtn.getPreferredSize().width, 20));

        focusBanner = new JPanel(new BorderLayout(8, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                Theme t = Theme.current();
                setBackground(t.accent.fill());
                setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 1, 0, t.accent.border()),
                        BorderFactory.createEmptyBorder(1, 8, 1, 6)));
                if (focusLabel != null) {
                    focusLabel.setFont(t.smallBoldFont);
                    focusLabel.setForeground(t.accent.text());
                }
            }
        };
        focusBanner.setOpaque(true);
        focusBanner.setPreferredSize(new Dimension(0, 26));
        focusBanner.add(focusLabel, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 1));
        right.setOpaque(false);
        right.add(focusScopeToggle);
        right.add(exitBtn);
        focusBanner.add(right, BorderLayout.EAST);
        focusBanner.setVisible(false);
        return focusBanner;
    }

    private void initFilterPills() {
        filterAll.setPadding(1, 6, 1, 6);
        filterAll.setToolTipText("Hiển thị tất cả API (bỏ lọc trạng thái)");
        filterAll.onClick(this::clearAuditFilters);

        registerFilterPill(AuditLevel.OK, "OK", "Lọc API: Tất cả Role 2xx (OK)", t -> t.success, t -> Icons.check(t.success.text(), 11));
        registerFilterPill(AuditLevel.INFO, "Lệch", "Lọc API: Có Role lỗi / lệch body", t -> t.warning, t -> Icons.alertMark(t.warning.text(), 11));
        registerFilterPill(AuditLevel.WARN, "Bypass", "Lọc API: Nghi vấn Bypass (Guest 2xx)", t -> t.critical, t -> Icons.warn(t.critical.text(), 11));
        registerFilterPill(AuditLevel.ERROR, "Lỗi", "Lọc API: Có lỗi 5xx / Mất kết nối", t -> t.danger, t -> Icons.error(t.danger.text(), 11));
        registerFilterPill(AuditLevel.NONE, "Chưa gửi", "Lọc API: Chưa gửi / chưa replay", t -> t.neutral, t -> Icons.statusDot(t.dotNone, 9));
        registerFilterPill(AuditLevel.PENDING, "Chờ XN", "Lọc API: Chờ xác nhận vị trí / trùng", t -> t.pending, t -> Icons.hourglass(t.pending.text(), 10));
    }

    private JPopupMenu filterPopupMenu() {
        JPopupMenu m = new JPopupMenu();
        javax.swing.JCheckBoxMenuItem allItem = new javax.swing.JCheckBoxMenuItem("Hiển thị Tất cả trạng thái (Bỏ lọc)", activeFilters.isEmpty());
        allItem.addActionListener(Ui.safe(e -> clearAuditFilters()));
        m.add(allItem);
        m.addSeparator();
        Object[][] defs = {
                {AuditLevel.NONE, "Chưa gửi (Chưa replay)"},
                {AuditLevel.OK, "OK (Tất cả Role 2xx)"},
                {AuditLevel.INFO, "Lệch (Có Role lỗi / lệch body)"},
                {AuditLevel.WARN, "Bypass (Nghi vấn Guest/Role thấp 2xx)"},
                {AuditLevel.ERROR, "Lỗi (5xx / Mất kết nối)"},
                {AuditLevel.PENDING, "Chờ XN (Chờ xác nhận vị trí / trùng)"}
        };
        for (Object[] d : defs) {
            AuditLevel lvl = (AuditLevel) d[0];
            String label = (String) d[1];
            javax.swing.JCheckBoxMenuItem item = new javax.swing.JCheckBoxMenuItem(label, activeFilters.contains(lvl));
            item.addActionListener(Ui.safe(e -> toggleAuditFilter(lvl)));
            m.add(item);
        }
        m.addSeparator();
        String curTag = groupModel.tagFilter();
        javax.swing.JRadioButtonMenuItem allTags = new javax.swing.JRadioButtonMenuItem("Tag: Tất cả API", curTag == null);
        allTags.addActionListener(Ui.safe(e -> {
            groupModel.setTagFilter(null);
            updateFilterStrip();
        }));
        m.add(allTags);
        javax.swing.JRadioButtonMenuItem suspected = new javax.swing.JRadioButtonMenuItem(
                "Tag: ? Gợi ý REFRESH / Đã gắn REFRESH", GroupTableModel.TAG_FILTER_SUSPECTED.equals(curTag));
        suspected.addActionListener(Ui.safe(e -> {
            groupModel.setTagFilter(GroupTableModel.TAG_FILTER_SUSPECTED);
            updateFilterStrip();
        }));
        m.add(suspected);
        for (ApiTagCatalog.TagDef td : engine.tagCatalog().all()) {
            javax.swing.JRadioButtonMenuItem tItem = new javax.swing.JRadioButtonMenuItem(
                    "Tag: [" + td.label() + "]", td.id().equalsIgnoreCase(curTag));
            tItem.addActionListener(Ui.safe(e -> {
                groupModel.setTagFilter(td.id());
                updateFilterStrip();
            }));
            m.add(tItem);
        }
        m.add(Ui.item("Quản lý Tag & Chính sách REFRESH...", Icons::tag, ui::openTagManager));
        m.addSeparator();
        javax.swing.JRadioButtonMenuItem allBranches = new javax.swing.JRadioButtonMenuItem(
                "Phạm vi: Tất cả API (Mặc định)", focusPath == null && viewMode.getSelectedIndex() == 0);
        allBranches.addActionListener(Ui.safe(e -> {
            clearFocusBranch();
            viewMode.setSelectedIndex(0);
        }));
        javax.swing.JRadioButtonMenuItem selectedBranchOnly = new javax.swing.JRadioButtonMenuItem(
                "Phạm vi: Chỉ theo Nhánh đang chọn", focusPath == null && viewMode.getSelectedIndex() == 1);
        selectedBranchOnly.addActionListener(Ui.safe(e -> {
            clearFocusBranch();
            viewMode.setSelectedIndex(1);
        }));
        m.add(allBranches);
        m.add(selectedBranchOnly);
        return m;
    }

    SearchField searchField() {
        return search;
    }

    private void registerFilterPill(AuditLevel lvl, String label, String tip,
                                    java.util.function.Function<Theme, Theme.Token> token,
                                    java.util.function.Function<Theme, javax.swing.Icon> icon) {
        Pill p = new Pill(label, token, icon);
        p.setPadding(1, 7, 1, 7);
        p.setToolTipText(tip);
        p.onClick(() -> toggleAuditFilter(lvl));
        filterPills.put(lvl, p);
    }

    // =================================================================== refresh
    void reloadAll() {
        groupModel.setAll(engine.groups());
        updateFilterStrip();
        updateResultSummary(currentGroup());
        treeStale = true;
        branchStale = true;
        refreshVisible(true);
    }

    void apply(Changes.Batch b) {
        if (b.structure()) {
            List<ApiGroup> sel = selectedGroups();
            selecting = true;
            try {
                groupModel.setAll(engine.groups());
                for (ApiGroup g : sel) {
                    int r = groupModel.rowOf(g);
                    if (r >= 0) {
                        int v = table.convertRowIndexToView(r);
                        table.addRowSelectionInterval(v, v);
                    }
                }
            } finally {
                selecting = false;
            }
            updateFilterStrip();
            treeStale = true;
            branchStale = true;
            if (resultModel.group() != null && engine.group(resultModel.group().gid()) == null) {
                showGroup(null);
            }
        } else if (!b.added().isEmpty()) {
            groupModel.addAll(b.added());
            updateFilterStrip();
            treeStale = true;
            branchStale = true;
            if (table.getSelectedRow() < 0 && table.getRowCount() > 0) {
                table.setRowSelectionInterval(0, 0);
            }
        }
        if (!b.dirty().isEmpty()) {
            groupModel.updated(b.dirty());
            treeStale = true;
            branchStale = true;
            ApiGroup cur = resultModel.group();
            if (cur != null && b.dirty().contains(cur)) {
                RoleResult keep = selectedResult();
                resultModel.refresh();
                updateResultSummary(cur);
                if (keep != null && results.getSelectedRow() < 0) {
                    MainPanel.selectRow(results, resultModel.rowOf(keep));
                }
                refreshBar();
                if (keep != null) {
                    showResponse(keep);
                }
            }
        }
    }

    /**
     * Real-time push callback fired directly on the EDT whenever {@link ProxyInterceptQueue} changes.
     * Rebuilds the visible API-tab tree and clears stale held preview bars immediately without waiting for timer tick.
     */
    void onProxyQueueChanged(boolean activeTab) {
        List<ProxyInterceptQueue.PendingRequest> pending = engine.proxyQueue().pending();
        int curCount = pending.size();
        boolean grew = curCount > lastKnownHeldCount;
        lastKnownHeldCount = curCount;

        if (shownHeld != null) {
            long id = shownHeld.id();
            boolean stillPending = pending.stream().anyMatch(p -> p.id() == id);
            if (!stillPending) {
                showHeldRequest(null);
            }
        }

        treeStale = true;
        branchStale = true;
        if (tree.tree.isShowing() || activeTab) {
            treeStale = false;
            tree.rebuild(engine.config().showGhosts);
            if (grew && shownHeld == null && !pending.isEmpty()) {
                tree.revealHeldNode(pending.get(pending.size() - 1).id());
            }
            tree.tree.revalidate();
            tree.tree.repaint();
        }
        if (branchTable.isShowing()) {
            branchStale = false;
            branchModel.reload();
        }
    }

    void refreshVisible(boolean force) {
        if (treeStale && tree.tree.isShowing()) {
            treeStale = false;
            tree.rebuild(engine.config().showGhosts);
        }
        if (branchStale && branchTable.isShowing()) {
            branchStale = false;
            branchModel.reload();
        }
        if (force || treeStale) {
            List<String> paths = new ArrayList<>(engine.knownPaths());
            if (paths.size() != pathEditor.getItemCount()) {
                pathEditor.removeAllItems();
                for (String p : paths) {
                    pathEditor.addItem(p);
                }
            }
        }
    }

    // =================================================================== selection
    List<ApiGroup> selectedGroups() {
        List<ApiGroup> out = new ArrayList<>();
        for (int v : table.getSelectedRows()) {
            ApiGroup g = groupModel.at(table.convertRowIndexToModel(v));
            if (g != null) {
                out.add(g);
            }
        }
        return out;
    }

    private List<ApiGroup> selectedOrScope() {
        List<ApiGroup> s = selectedGroups();
        return !s.isEmpty() ? s : ui.scope().groups();
    }

    Branch selectedBranchRow() {
        int v = branchTable.getSelectedRow();
        return v < 0 ? null : branchModel.at(branchTable.convertRowIndexToModel(v));
    }

    ApiGroup currentGroup() {
        return resultModel.group();
    }

    RoleResult selectedResult() {
        int v = results.getSelectedRow();
        return v < 0 ? null : resultModel.at(results.convertRowIndexToModel(v));
    }

    List<RoleResult> selectedResults() {
        List<RoleResult> out = new ArrayList<>();
        for (int v : results.getSelectedRows()) {
            out.add(resultModel.at(results.convertRowIndexToModel(v)));
        }
        return out;
    }

    void select(ApiGroup g, RoleResult r) {
        if (groupModel.rowOf(g) < 0) {
            if (focusPath != null) {
                clearFocusBranch();
            }
            groupModel.setBranchFilter(null);
            groupModel.setTagFilter(null);
            clearAuditFilters();
            viewMode.setSelectedIndex(0);
        }
        MainPanel.selectRow(table, groupModel.rowOf(g));
        if (r != null) {
            MainPanel.selectRow(results, resultModel.rowOf(r));
        }
    }

    void selectBranch(String branchPath) {
        if (branchPath == null || branchPath.isBlank()) {
            return;
        }
        leftTabs.setSelectedIndex(0);
        if (focusPath != null && !Hierarchy.matchesPrefix(branchPath, focusPath)) {
            clearFocusBranch();
        }
        if (treeStale) {
            treeStale = false;
            tree.rebuild(engine.config().showGhosts);
        }
        tree.selectBranchPath(branchPath);
        if (focusPath == null && viewMode.getSelectedIndex() == 1) {
            groupModel.setBranchFilter(branchPath);
            updateFilterStrip();
        }
    }

    void showGroup(ApiGroup g) {
        if (g != resultModel.group()) {
            resultModel.setGroup(g);
            if (resultModel.getRowCount() > 0) {
                results.setRowSelectionInterval(0, 0);
            } else {
                showResult(null);
            }
        }
        updateResultSummary(g);
        refreshBar();
    }

    private void updateResultSummary(ApiGroup g) {
        if (g == null) {
            resOkPill.setText("OK 0");
            resInfoPill.setText("Lệch 0");
            resWarnPill.setText("Bypass 0");
            resErrPill.setText("Lỗi 0");
            resMetaLabel.setText("Chưa chọn API");
            return;
        }
        int ok = 0;
        int info = 0;
        int warn = 0;
        int err = 0;
        for (RoleResult r : g.results()) {
            if (ResultState.ERROR.equals(r.state()) || (r.status() != null && r.status() >= 500)) {
                err++;
            } else {
                AuditLevel lvl = Audit.level(g, r);
                if (lvl == AuditLevel.WARN) {
                    warn++;
                } else if (lvl == AuditLevel.OK) {
                    ok++;
                } else if (lvl == AuditLevel.INFO || lvl == AuditLevel.REDIRECT) {
                    info++;
                }
            }
        }
        resOkPill.setText("OK " + ok);
        resInfoPill.setText("Lệch " + info);
        resWarnPill.setText("Bypass " + warn);
        resErrPill.setText("Lỗi " + err);
        resMetaLabel.setText(Text.groupId(g.gid()) + " · " + BranchTree.rolesLabel(g));
    }

    void filterPending() {
        selectSingleFilter(AuditLevel.PENDING);
    }

    void filterLevel(AuditLevel lvl) {
        if (lvl == null) {
            clearAuditFilters();
        } else {
            selectSingleFilter(lvl);
        }
    }

    void toggleAuditFilter(AuditLevel lvl) {
        if (lvl == null) {
            clearAuditFilters();
            return;
        }
        if (!activeFilters.add(lvl)) {
            activeFilters.remove(lvl);
        }
        applyFilters();
    }

    void clearAuditFilters() {
        activeFilters.clear();
        applyFilters();
    }

    private void selectSingleFilter(AuditLevel lvl) {
        activeFilters.clear();
        if (lvl != null) {
            activeFilters.add(lvl);
        }
        applyFilters();
    }

    Set<AuditLevel> activeAuditFilters() {
        return Collections.unmodifiableSet(EnumSet.copyOf(activeFilters));
    }

    private void applyFilters() {
        groupModel.setAuditFilters(activeFilters);
        updateFilterStrip();
    }

    void setFocusBranch(String path, boolean recursive) {
        String clean = path == null || path.isBlank() ? null : Hierarchy.normalize(path);
        if (clean == null) {
            clearFocusBranch();
            return;
        }
        focusPath = clean;
        focusRecursive = recursive;
        tree.setFocus(clean, recursive);
        groupModel.setBranchFilter(clean, !recursive);
        if (focusLabel != null) {
            focusLabel.setText("TẬP TRUNG: " + clean + (recursive ? "  (Kèm nhánh con)" : "  (Chỉ nhánh này)"));
            focusLabel.setToolTipText("Đang cô lập hiển thị cho đường dẫn: " + clean);
        }
        if (focusScopeToggle != null) {
            focusScopeToggle.setSelectedIndex(recursive ? 1 : 0, false);
        }
        if (focusBanner != null) {
            focusBanner.setVisible(true);
            focusBanner.revalidate();
            focusBanner.repaint();
        }
        if (focusBtn != null) {
            focusBtn.setText("Thoát Tập trung");
        }
        updateFilterStrip();
        ui.flash("Đang tập trung vào nhánh: " + clean + (recursive ? " (kèm nhánh con)" : " (chỉ nhánh này)"));
    }

    void clearFocusBranch() {
        if (focusPath == null && tree.focusPath() == null) {
            return;
        }
        focusPath = null;
        focusRecursive = false;
        tree.clearFocus();
        if (viewMode.getSelectedIndex() == 0) {
            groupModel.setBranchFilter(null, false);
        } else {
            groupModel.setBranchFilter(ui.selectedBranch(), false);
        }
        if (focusBanner != null) {
            focusBanner.setVisible(false);
        }
        if (focusBtn != null) {
            focusBtn.setText("Tập trung");
        }
        updateFilterStrip();
    }

    String focusPath() {
        return focusPath;
    }

    boolean focusRecursive() {
        return focusRecursive;
    }

    private void toggleFocusFromSelection() {
        if (focusPath != null) {
            clearFocusBranch();
            ui.flash("Đã thoát chế độ Tập trung — hiển thị lại toàn bộ cây");
            return;
        }
        String target = ui.selectedBranch();
        if (target == null || target.isBlank()) {
            target = engine.activeBranch().name();
        }
        setFocusBranch(target, true);
    }

    private void updateFilterStrip() {
        filterAll.setActive(activeFilters.isEmpty());
        for (Map.Entry<AuditLevel, Pill> en : filterPills.entrySet()) {
            en.getValue().setActive(activeFilters.contains(en.getKey()));
        }
        if (filterDropdownBtn != null) {
            List<String> parts = new ArrayList<>();
            if (!activeFilters.isEmpty()) {
                parts.add(activeFilters.size() + " trạng thái");
            }
            String tf = groupModel.tagFilter();
            if (tf != null) {
                parts.add(GroupTableModel.TAG_FILTER_SUSPECTED.equals(tf) ? "Tag: ?Refresh" : "Tag: " + tf);
            }
            if (focusPath != null) {
                parts.add("Tập trung");
            } else if (viewMode.getSelectedIndex() == 1) {
                parts.add("Theo Nhánh");
            }
            filterDropdownBtn.setText(parts.isEmpty() ? "Lọc: Tất cả" : "Lọc: " + String.join(" · ", parts));
        }
        int shown = groupModel.getRowCount();
        int total = groupModel.total();
        filterCount.setText(shown == total ? total + " API" : shown + " / " + total + " API");
    }

    private void showAll() {
        if (focusPath != null) {
            clearFocusBranch();
        }
        viewMode.setSelectedIndex(0);
        groupModel.setBranchFilter(null);
        groupModel.setTagFilter(null);
        branchTable.clearSelection();
        updateFilterStrip();
    }

    private void applyViewMode() {
        if (focusPath != null) {
            return;
        }
        if (viewMode.getSelectedIndex() == 0) {
            groupModel.setBranchFilter(null);
        } else {
            groupModel.setBranchFilter(ui.selectedBranch());
        }
        updateFilterStrip();
    }

    /** RowSorter wrapping {@link TableRowSorter} so model row 0 (API gốc) always stays pinned at view row 0. */
    private static final class PinnedOrigRowSorter extends RowSorter<ResultTableModel> {
        private final TableRowSorter<ResultTableModel> delegate;
        private int origView;

        PinnedOrigRowSorter(ResultTableModel model) {
            this.delegate = new TableRowSorter<>(model);
            this.delegate.addRowSorterListener(new RowSorterListener() {
                @Override
                public void sorterChanged(RowSorterEvent e) {
                    recomputeOrig();
                    if (e.getType() == RowSorterEvent.Type.SORT_ORDER_CHANGED) {
                        fireSortOrderChanged();
                    } else {
                        fireRowSorterChanged(null);
                    }
                }
            });
            recomputeOrig();
        }

        private void recomputeOrig() {
            int n = delegate.getViewRowCount();
            origView = n > 0 ? delegate.convertRowIndexToView(0) : -1;
        }

        private boolean hasActiveSort() {
            for (SortKey k : delegate.getSortKeys()) {
                if (k.getSortOrder() != SortOrder.UNSORTED) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public ResultTableModel getModel() {
            return delegate.getModel();
        }

        @Override
        public void toggleSortOrder(int column) {
            delegate.toggleSortOrder(column);
        }

        @Override
        public int convertRowIndexToModel(int index) {
            if (index <= 0 || !hasActiveSort() || origView < 0) {
                return delegate.getViewRowCount() > 0 && index == 0 ? 0 : index;
            }
            int dv = index - 1 < origView ? index - 1 : index;
            return delegate.convertRowIndexToModel(dv);
        }

        @Override
        public int convertRowIndexToView(int index) {
            if (index <= 0 || !hasActiveSort() || origView < 0) {
                return index;
            }
            int dv = delegate.convertRowIndexToView(index);
            if (dv < 0) {
                return dv;
            }
            return dv < origView ? dv + 1 : dv;
        }

        @Override
        public void setSortKeys(List<? extends SortKey> keys) {
            delegate.setSortKeys(keys);
        }

        @Override
        public List<? extends SortKey> getSortKeys() {
            return delegate.getSortKeys();
        }

        @Override
        public int getViewRowCount() {
            return delegate.getViewRowCount();
        }

        @Override
        public int getModelRowCount() {
            return delegate.getModelRowCount();
        }

        @Override
        public void modelStructureChanged() {
            delegate.modelStructureChanged();
            recomputeOrig();
        }

        @Override
        public void allRowsChanged() {
            delegate.allRowsChanged();
            recomputeOrig();
        }

        @Override
        public void rowsInserted(int firstRow, int endRow) {
            delegate.rowsInserted(firstRow, endRow);
            recomputeOrig();
        }

        @Override
        public void rowsDeleted(int firstRow, int endRow) {
            delegate.rowsDeleted(firstRow, endRow);
            recomputeOrig();
        }

        @Override
        public void rowsUpdated(int firstRow, int endRow) {
            delegate.rowsUpdated(firstRow, endRow);
            recomputeOrig();
        }

        @Override
        public void rowsUpdated(int firstRow, int endRow, int column) {
            delegate.rowsUpdated(firstRow, endRow, column);
            recomputeOrig();
        }
    }

    private void onTreeSelected() {
        if (tree.restoring()) {
            return;
        }
        ui.useSource(tree);
        if (tree.selectionCount() > 1) {
            ui.flash("Đang chọn " + tree.selectionCount() + " mục trong cây = " + tree.selectedGroups().size()
                    + " API (Ctrl / Shift + click để thêm bớt)");
        }
        Node n = tree.selectedNode();
        if (n == null) {
            return;
        }
        switch (n.kind) {
            case ROOT -> {
                showHeldRequest(null);
                findSendBranch.setText("Gửi Tất cả (" + engine.groups().size() + " API)");
                if (focusPath == null && viewMode.getSelectedIndex() == 1) {
                    groupModel.setBranchFilter(null);
                }
            }
            case FOLDER -> {
                showHeldRequest(null);
                String p = (String) n.payload;
                findSendBranch.setText("Gửi \"" + Text.shortPath("/" + Hierarchy.leaf(p), 19).substring(1) + "\" ("
                        + engine.groupsUnder(p).size() + " API)");
                findSendBranch.setToolTipText("Gửi đệ quy toàn bộ nhánh: " + p);
                if (focusPath == null && viewMode.getSelectedIndex() == 1) {
                    groupModel.setBranchFilter(p);
                }
            }
            case CLUSTER -> {
                showHeldRequest(null);
                GroupCluster cl = (GroupCluster) n.payload;
                int sz = tree.selectedGroups().size();
                findSendBranch.setText("Gửi Cụm [" + Text.shortPath("/" + cl.name(), 16).substring(1) + "] (" + sz + " API)");
                findSendBranch.setToolTipText("Cụm hiển thị trực quan: " + cl.name() + " (không đổi đường dẫn API)");
            }
            case HELD_PENDING -> {
                ProxyInterceptQueue.PendingRequest pr = BranchTree.heldOf(n);
                if (pr != null) {
                    showHeldRequest(pr);
                    ui.useSource(tree);
                }
            }
            default -> {
                showHeldRequest(null);
                ApiGroup g = BranchTree.groupOf(n);
                if (g != null) {
                    findSendBranch.setText("Gửi API #" + g.gid());
                    selecting = true;
                    try {
                        select(g, null);
                    } finally {
                        selecting = false;
                    }
                    showGroup(g);
                    if (n.payload instanceof Object[] arr && arr.length > 1 && arr[1] instanceof RoleResult r) {
                        MainPanel.selectRow(results, resultModel.rowOf(r));
                    }
                    ui.useSource(tree);
                }
            }
        }
    }

    private void onBranchRowSelected() {
        Branch b = selectedBranchRow();
        ui.useSource(branchTable);
        if (b == null) {
            if (branchTable.getSelectedRow() == 0) {
                groupModel.setBranchFilter(null);
            }
            return;
        }
        groupModel.setBranchFilter(b.name());
    }

    private void sendBranchSelection() {
        MainPanel.Scope s = ui.scope();
        if (s.groups().isEmpty()) {
            ui.info("Chọn một nhánh trong cây hoặc bảng nhánh trước.");
            return;
        }
        ui.send(s.groups());
    }

    // =================================================================== viewers + grouped repeater
    private void showResult(RoleResult r) {
        showHeldRequest(null);
        ApiGroup g = currentGroup();
        if (g == null || r == null) {
            reqHeader.setText(" Request");
            respHeader.setText(" Response");
            reqViewer.show(null, new byte[0]);
            respViewer.show(null, new byte[0]);
            return;
        }
        reqHeader.setText(" Request · " + Text.groupId(g.gid()) + " · " + r.roleName());
        reqViewer.show(g.service(), engine.requestOf(g, r));
        showResponse(r);
    }

    private void showResponse(RoleResult r) {
        ApiGroup g = currentGroup();
        if (g == null) {
            return;
        }
        Object st = r.status() != null ? r.status() : r.state();
        respHeader.setText(" Response · " + r.roleName() + " · Status: " + st + " · Length: " + (r.length() != null ? r.length() : "-"));
        respViewer.show(g.service(), engine.responseOf(g, r));
    }

    private void sendCurrentTab() {
        ApiGroup g = currentGroup();
        RoleResult r = selectedResult();
        if (g == null || r == null) {
            ui.info("Chọn một API trước.");
            return;
        }
        byte[] edited = reqViewer.edited();
        if (edited != null && edited.length > 0) {
            engine.setCustomRequest(g, r, edited);
        }
        engine.replay(g, List.of(r));
    }

    private void resetRequest() {
        ApiGroup g = currentGroup();
        RoleResult r = selectedResult();
        if (g != null && r != null) {
            engine.setCustomRequest(g, r, null);
            showResult(r);
            ui.flash("Đã khôi phục Request ban đầu của " + r.roleName() + ".");
        } else {
            ui.flash("Chọn 1 API và 1 Role trước rồi bấm Khôi phục.");
        }
    }

    private void pushSelectedRoles() {
        ApiGroup g = currentGroup();
        List<RoleResult> rs = selectedResults();
        if (g == null || rs.isEmpty()) {
            return;
        }
        engine.toRepeater(g, rs, false);
    }

    void refreshBar() {
        ApiGroup g = currentGroup();
        roleTabs.removeAll();
        if (g == null) {
            pill.setText("Chưa chọn API Group");
            if (compactSummaryBtn != null) {
                compactSummaryBtn.setText("Chưa chọn API — Bấm biểu tượng mũi tên để mở rộng");
            }
            if (compactMiniStats != null) {
                compactMiniStats.setText("");
            }
        } else {
            List<RoleResult> entries = g.entries();
            RoleResult cur = selectedResult();
            pill.setText(Text.groupId(g.gid()) + ": " + g.method() + " " + Text.shortPath(g.path(), 26) + " (" + entries.size() + ")");
            if (compactSummaryBtn != null) {
                String curRole = cur == null ? "" : (" · " + (cur.original() ? "GỐC" : cur.roleName())
                        + (cur.status() != null ? " [" + cur.status() + "]" : ""));
                compactSummaryBtn.setText(Text.groupId(g.gid()) + " · " + g.method() + " " + Text.shortPath(g.path(), 34) + curRole);
            }
            if (compactMiniStats != null) {
                compactMiniStats.setText(resOkPill.getText() + " · " + resInfoPill.getText()
                        + (resWarnPill.getText().endsWith(" 0") ? "" : " · " + resWarnPill.getText())
                        + (resErrPill.getText().endsWith(" 0") ? "" : " · " + resErrPill.getText()));
            }
            if (expandedTabs) {
                Theme th = Theme.current();
                for (RoleResult r : entries) {
                    String lbl = (r.original() ? "GỐC" : r.roleName()) + (r.status() != null ? " (" + r.status() + ")" : "");
                    JButton b = new JButton(lbl);
                    b.setMargin(new Insets(2, 7, 2, 7));
                    b.setFont(r == cur ? th.smallBoldFont : th.smallFont);
                    AuditLevel lvl = Audit.level(g, r);
                    if (r == cur) {
                        b.setBackground(th.critical.solidFill());
                        b.setForeground(th.critical.onSolid());
                    } else if (r.original()) {
                        b.setBackground(th.orig.fillStrong());
                        b.setForeground(th.orig.text());
                    } else if (th.tinted(lvl)) {
                        Theme.Token tok = th.audit(lvl);
                        b.setBackground(tok.fill());
                        b.setForeground(tok.text());
                    }
                    if (r.original()) {
                        Color pinCol = r == cur ? th.critical.onSolid() : th.orig.text();
                        b.setIcon(Icons.pin(pinCol, 12));
                    }
                    b.addActionListener(Ui.safe(e -> MainPanel.selectRow(results, resultModel.rowOf(r))));
                    roleTabs.add(b);
                }
            }
        }
        roleTabs.revalidate();
        roleTabs.repaint();
    }

    private void pillMenu() {
        pillMenu(pill);
    }

    private void pillMenu(java.awt.Component anchor) {
        JPopupMenu m = new JPopupMenu();
        ApiGroup g = currentGroup();
        if (g != null) {
            RoleResult cur = selectedResult();
            for (RoleResult r : g.entries()) {
                String lbl = (r.original() ? "[GỐC] " : "") + r.roleName()
                        + (r.status() != null ? "  —  HTTP " + r.status() : "  —  " + r.state());
                m.add(Ui.item(lbl, t -> Icons.circle(r == cur ? t.accent.text() : t.muted, r == cur),
                        () -> MainPanel.selectRow(results, resultModel.rowOf(r))));
            }
            m.addSeparator();
            m.add(ActionMenus.buildForApi(panel, engine, g, selectedResults(), () -> {
                resultModel.refresh();
                refreshBar();
            }));
            m.add(Ui.item("Gửi (Send) toàn bộ " + g.results().size() + " Roles của " + Text.groupId(g.gid()),
                    Icons::sendAll, () -> engine.replay(g, engine.defaultTargets(g, false))));
            m.add(Ui.item("Đẩy Group " + Text.groupId(g.gid()) + " sang Burp Repeater (Tên gọn + Copy tên Group)",
                    Icons::repeater, () -> ui.push(g)));
            m.addSeparator();
        }
        m.add(Ui.item(expandedTabs ? "Thu gọn dải nút Tab Role ngang (Gọn giao diện)" : "Mở rộng dải nút Tab Role ngang",
                t -> expandedTabs ? Icons.chevronDown(t.fg, 12) : Icons.chevronRight(t.fg, 12), () -> {
                    expandedTabs = !expandedTabs;
                    applyInspectorBarState(true);
                    refreshBar();
                }));
        List<ApiGroup> all = engine.groups();
        if (!all.isEmpty()) {
            m.addSeparator();
            javax.swing.JMenu switchGroupMenu = new javax.swing.JMenu("Chuyển nhanh sang API Group khác (" + all.size() + ")");
            for (ApiGroup x : all.subList(Math.max(0, all.size() - 25), all.size())) {
                switchGroupMenu.add(Ui.item(Text.groupId(x.gid()) + " [" + x.branch() + "]: " + x.method() + " " + Text.shortPath(x.path(), 30)
                        + " (" + x.results().size() + " Roles)", Icons::folder, () -> select(x, null)));
            }
            m.add(switchGroupMenu);
        }
        java.awt.Component invoker = (anchor != null && anchor.isShowing()) ? anchor : panel;
        m.show(invoker, 0, anchor != null ? anchor.getHeight() : 0);
    }

    // =================================================================== menus
    private static MouseAdapter popup(javax.swing.JTree t, java.util.function.Consumer<MouseEvent> show) {
        return new MouseAdapter() {
            private void trigger(MouseEvent e) {
                if (e.isPopupTrigger() || (SwingUtilities.isRightMouseButton(e) && e.getID() == MouseEvent.MOUSE_PRESSED)) {
                    int row = t.getClosestRowForLocation(e.getX(), e.getY());
                    if (row >= 0 && !t.isRowSelected(row)) {
                        t.setSelectionRow(row);
                    }
                    show.accept(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                trigger(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    trigger(e);
                }
            }
        };
    }

    static MouseAdapter treePopup(javax.swing.JTree t, java.util.function.Consumer<MouseEvent> show) {
        return popup(t, show);
    }

    private void treePopup(MouseEvent e) {
        ui.useSource(tree);
        TreeMenus.show(ui, tree, e.getComponent(), e.getX(), e.getY());
    }

    private JPopupMenu groupMenu() {
        ui.useSource(table);
        List<ApiGroup> gs = selectedGroups();
        int n = Math.max(1, gs.size());
        ApiGroup primary = gs.isEmpty() ? currentGroup() : gs.get(0);
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Gửi " + n + " API đang chọn (N Roles)", Icons::send, () -> ui.send(gs)));
        if (primary != null) {
            m.add(Ui.item("Sửa Request & Gửi lại (Composer)...", t -> Icons.edit(t.info.text()),
                    () -> RequestComposerDialog.showFloating(panel, engine, primary, null, () -> {
                        resultModel.refresh();
                        refreshBar();
                    })));
            m.add(ActionMenus.buildForApi(panel, engine, primary, null, () -> {
                resultModel.refresh();
                refreshBar();
            }));
        }
        m.add(Ui.item("Đẩy " + n + " API đang chọn -> Burp Repeater", Icons::repeater, () -> ui.push(gs)));
        m.add(Ui.item("Copy STT + Path (" + n + " API)     Ctrl+Shift+C", Icons::copy, () -> ui.copy(gs)));
        m.add(Ui.item("Đẩy / Gửi " + n + " API có chọn Role (API gốc / role nào)...",
                Icons::target, () -> ui.pushDialog(false, gs, gs.size() + " API đang chọn")));
        m.add(Ui.item("Bật / Tắt Role cho " + n + " API đang chọn...",
                Icons::sliders, () -> ui.roleScopeDialog(false, RoleScopes.Scope.API, null, gs, null)));
        m.addSeparator();
        m.add(ui.clusterMenu(gs));
        m.add(ui.tagMenu(gs));
        if (primary != null) {
            m.add(Ui.item("Xem chuỗi Gốc & Trùng (Lineage)...", Icons::dup,
                    () -> ui.openLineage(primary, table, 16, 16)));
        }
        m.addSeparator();
        ui.placeItems(m, gs);
        m.addSeparator();
        m.add(Ui.item("Đổi Group Path cho API đang chọn...", Icons::folder, () -> ui.moveDialog(gs)));
        m.add(Ui.item("Rebuild Roles cho API đang chọn", Icons::refresh, () -> ui.rebuild(gs)));
        m.add(Ui.item("Xem API Gốc (Acc Crawl)", Icons::pin, this::showOriginal));
        m.addSeparator();
        m.add(Ui.item("Xóa API đang chọn", t -> Icons.trash(t.danger.text()), () -> ui.delete(gs)));
        return m;
    }

    private JPopupMenu resultMenu() {
        JPopupMenu m = new JPopupMenu();
        ApiGroup g = currentGroup();
        m.add(Ui.item("Gửi lại Role đang chọn", Icons::send, () -> {
            if (g != null) {
                engine.replay(g, selectedResults());
            }
        }));
        m.add(Ui.item("Gửi toàn bộ Roles của API này", Icons::sendAll, () -> ui.send(listOf(g))));
        if (g != null) {
            RoleResult selRole = selectedResult();
            m.add(Ui.item("Sửa Request & Gửi lại (Composer)...", t -> Icons.edit(t.info.text()),
                    () -> RequestComposerDialog.showFloating(panel, engine, g, selRole, () -> {
                        resultModel.refresh();
                        refreshBar();
                    })));
            m.add(ActionMenus.buildForApi(panel, engine, g, selectedResults(), () -> {
                resultModel.refresh();
                refreshBar();
            }));
        }
        m.addSeparator();
        m.add(Ui.item("Đẩy Role đang chọn -> Burp Repeater", Icons::repeater, this::pushSelectedRoles));
        m.add(Ui.item("Đẩy cả API Group -> Burp Repeater", Icons::repeater, () -> ui.push(g)));
        m.addSeparator();
        m.add(Ui.item("Xem API Gốc (Acc Crawl)", Icons::pin, this::showOriginal));
        return m;
    }

    private void showOriginal() {
        if (currentGroup() != null) {
            results.setRowSelectionInterval(0, 0);
        }
    }

    private JPopupMenu sendMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Gửi có chọn Role (API gốc / role nào / bỏ role nào)...", Icons::target, () -> ui.pushDialog(true, null, null)));
        m.addSeparator();
        m.add(Ui.item("Gửi API đang chọn (Tất cả Roles)", Icons::send, () -> ui.send(selectedOrScope())));
        m.add(Ui.item("Gửi lại Role đang chọn", Icons::send, () -> {
            if (currentGroup() != null) {
                engine.replay(currentGroup(), selectedResults());
            }
        }));
        m.add(Ui.item("Gửi toàn bộ Nhánh đang chọn (Đệ quy tất cả cấp con)", Icons::sendAll, this::sendBranchSelection));
        m.addSeparator();
        m.add(Ui.item("Gửi tất cả request CHƯA GỬI (crawl lúc đang dừng)", Icons::send,
                () -> ui.flash("Xếp hàng " + engine.sendAllUnsent() + " request chưa gửi")));
        m.add(Ui.item("Hủy hàng đợi gửi", t -> Icons.stop(t.danger.text()), () -> engine.cancelPending("Đã hủy")));
        m.add(Ui.item("Gửi tất cả API đã bắt (" + engine.groups().size() + " API)", Icons::sendAll, () -> ui.send(engine.groups())));
        return m;
    }

    private JPopupMenu repeaterMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Đẩy có chọn Role (API gốc / role nào / bỏ role nào)...", Icons::target, () -> ui.pushDialog(false, null, null)));
        m.addSeparator();
        m.add(Ui.item("Đẩy API đang chọn -> Burp Repeater", Icons::repeater, () -> ui.push(selectedOrScope())));
        m.add(Ui.item("Đẩy Role đang chọn -> Burp Repeater", Icons::repeater, this::pushSelectedRoles));
        m.add(Ui.item("Đẩy cả Nhánh đang chọn -> Burp Repeater", Icons::repeater, () -> ui.push(ui.scope().groups())));
        m.addSeparator();
        for (int i = 0; i < MainPanel.REPEATER_MODES.length; i++) {
            int idx = i;
            m.add(Ui.item(MainPanel.REPEATER_MODES[i],
                    t -> Icons.circle(ui.repeaterMode == idx ? t.accent.text() : t.muted, ui.repeaterMode == idx),
                    () -> ui.repeaterMode = idx));
        }
        return m;
    }

    private JPopupMenu toolsMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Copy STT + Path (Ctrl+Shift+C)", Icons::copy, () -> ui.copy(selectedOrScope())));
        m.add(Ui.item("Đổi Group Path cho API đang chọn...", Icons::folder, () -> ui.moveDialog(selectedOrScope())));
        m.add(Ui.item("Rebuild Roles cho API đang chọn (Áp dụng lại cấu hình Role)", Icons::refresh, () -> ui.rebuild(selectedOrScope())));
        m.add(Ui.item("Xem Request/Response của API Gốc (Acc Crawl)", Icons::pin, this::showOriginal));
        m.add(Ui.item("Quản lý API Tag & Chính sách REFRESH...", Icons::tag, ui::openTagManager));
        m.add(Ui.item("Quản lý Loại mục con & Biểu tượng (Folder/Link/Button/Icon)...", Icons::sliders, ui::openNodeTypeManager));
        m.addSeparator();
        m.add(Ui.item("Xuất Excel (.xlsx): STT / Method / Path / Vị trí...", Icons::excel, ui::exportExcel));
        m.add(Ui.item("Export Matrix JSON...", Icons::save, ui::exportMatrix));
        m.add(Ui.item("Export Báo cáo Lịch sử (.txt/.md)...", Icons::document, ui::exportHistory));
        m.addSeparator();
        m.add(Ui.item("Xóa API đang chọn", t -> Icons.trash(t.danger.text()), () -> ui.delete(selectedGroups())));
        m.add(Ui.item("Reset Dedup Cache", Icons::refresh, engine::resetDedup));
        m.add(Ui.item("Clear All (Xóa toàn bộ API & Nhánh)", Icons::clean, () -> {
            if (ui.confirmDialog("Xóa toàn bộ các API đã bắt, đặt lại danh sách Nhánh về mặc định và xóa Dedup cache?\n"
                    + "(Vị trí giữ chỗ vẫn được nhớ)")) {
                engine.clearAll();
            }
        }));
        m.addSeparator();
        m.add(Ui.item("Hướng dẫn Copy & Chọn nhiều...", Icons::info,
                () -> Ui.popover(panel, "Copy & Chọn nhiều", Help.COPY + "\n\n" + Help.SELECT)));
        return m;
    }

    private JPopupMenu branchMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(Ui.item("Gửi toàn bộ Nhánh đang chọn", Icons::send, this::sendBranchSelection));
        m.add(Ui.item("Đẩy Nhánh đang chọn -> Burp Repeater", Icons::repeater, () -> ui.push(ui.scope().groups())));
        m.add(Ui.item("Đẩy / Gửi Nhánh có chọn Role...", Icons::target, () -> ui.pushDialog(false, null, null)));
        m.addSeparator();
        if (focusPath != null) {
            m.add(Ui.item("Thoát chế độ Tập trung (" + Hierarchy.leaf(focusPath) + ")", Icons::focus, this::clearFocusBranch));
        } else {
            String selBranch = ui.selectedBranch();
            String label = selBranch == null || selBranch.isBlank()
                    ? "Tập trung vào Nhánh Đích hiện tại"
                    : "Tập trung vào nhánh \"" + Hierarchy.leaf(selBranch) + "\"";
            m.add(Ui.item(label, Icons::focus, this::toggleFocusFromSelection));
        }
        m.add(Ui.item("Đặt làm ĐÍCH cho request mới", Icons::move, ui::destinationFromTree));
        m.add(Ui.item("Thêm mục con (Folder / Link / Button / Icon...)...", Icons::plus, () -> ui.addSubfolder(ui.selectedBranch())));
        m.add(Ui.item("Thêm Nhánh mới cùng cấp...", Icons::plus, ui::nextSibling));
        m.add(Ui.item("Đổi loại & Icon nhánh đang chọn...", Icons::sliders, () -> {
            String b = ui.selectedBranch();
            if (b != null) {
                ui.editNodeTypeDialog(b);
            }
        }));
        m.add(Ui.item("Đổi tên Nhánh đang chọn...", Icons::edit, () -> {
            String b = ui.selectedBranch();
            if (b != null) {
                ui.renameBranchDialog(b);
            }
        }));
        String curBranchForWait = ui.selectedBranch();
        if (curBranchForWait != null && !curBranchForWait.isBlank()) {
            boolean bw = engine.actionEngine().isBranchInterceptWait(curBranchForWait)
                    && engine.actionEngine().isInterceptWaitEnabled();
            m.add(Ui.item(bw
                            ? "Tắt Chặn chờ duyệt (Đợi bấm Gửi) cho nhánh \"" + Hierarchy.leaf(curBranchForWait) + "\""
                            : "Bật Chặn chờ duyệt (Đợi bấm Gửi) cho nhánh \"" + Hierarchy.leaf(curBranchForWait) + "\"",
                    t -> Icons.strategyShield(bw ? t.danger.text() : t.accent.text(), 14),
                    () -> {
                        boolean next = !bw;
                        engine.toggleBranchInterceptWait(curBranchForWait, next);
                        ui.syncHeader();
                        ui.flash(next
                                ? "Đã bật Chặn chờ duyệt cho nhánh " + Hierarchy.leaf(curBranchForWait) + " — request sẽ hiện ngay trên Cây!"
                                : "Đã tắt Chặn chờ duyệt cho nhánh " + Hierarchy.leaf(curBranchForWait));
                    }));
        }
        m.add(Ui.item("Quản lý các cấp & Loại Sub-folder...", Icons::gear, () -> LevelsDialog.show(ui.root(), engine)));
        m.add(Ui.item("Xóa Nhánh đang chọn...", t -> Icons.trash(t.danger.text()), () -> ui.deleteBranchDialog(ui.selectedBranch())));
        m.addSeparator();
        m.add(Ui.item("Xem tất cả API (Bỏ lọc Nhánh)", Icons::show, this::showAll));
        return m;
    }

    private String excelTip(ApiGroup g) {
        if (g == null || g.target() == null) {
            return engine.targets() != null ? "API không có trong file Excel" : null;
        }
        return "Bấm để mở dòng STT " + g.target().sttText() + " trong tab Excel (" + g.target().methodLabel() + " "
                + g.target().path() + ") · đã gặp " + engine.hits().count(g.target()) + " lần";
    }

    /** Moves the given model columns to the front, in that order; the remaining columns keep their order. */
    static void applyViewOrder(TableColumnModel cm, int... modelOrder) {
        int pos = 0;
        for (int mi : modelOrder) {
            for (int v = pos; v < cm.getColumnCount(); v++) {
                if (cm.getColumn(v).getModelIndex() == mi) {
                    if (v != pos) {
                        cm.moveColumn(v, pos);
                    }
                    pos++;
                    break;
                }
            }
        }
    }

    static List<ApiGroup> listOf(ApiGroup g) {
        return g == null ? List.of() : List.of(g);
    }
}
