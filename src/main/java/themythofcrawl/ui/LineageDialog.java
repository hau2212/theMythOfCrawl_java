package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumn;

import themythofcrawl.app.Prefs;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ApiTagCatalog;
import themythofcrawl.place.Decisions;
import themythofcrawl.place.PlacementBook;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.SectionHeader;
import themythofcrawl.ui.kit.StructuredAdviceCard;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Floating, draggable, resizable manager for Original (#1) &amp; duplicate copies of an API:
 * - Non-modal ({@link JDialog.ModalityType#MODELESS}) so users can freely move, resize, expand,
 *   pin on top, and work side-by-side with the main API table.
 * - Zero HTML labels (uses pure-Swing {@link StructuredAdviceCard}) so Burp Suite's
 *   {@code html.disable=true} never leaks raw tags.
 * - Generous column widths with automatic stretching on {@code Vị trí trong cây} so Vietnamese
 *   labels like {@code Chờ bạn chọn} and timestamps like {@code 07:21:45} never truncate.
 * - Remembers the user's preferred window size in {@link UiPrefs}.
 */
final class LineageDialog {
    static final int[] BASE_WIDTHS = {54, 114, 74, 310, 132, 110, 94, 98};
    static final int PATH_COL = 3;

    private static final List<String> COLS = List.of(
            "Bản", "Vai trò", "Mã API", "Vị trí trong cây", "Trạng thái", "Nhãn", "STT / Excel", "Lần cuối");

    private static FloatingRef activeFloating;

    private record FloatingRef(
            WeakReference<MainPanel> ownerUi,
            WeakReference<JDialog> dialog,
            BiConsumer<String, ApiGroup> switchTarget) {
    }

    /** Headless-testable content view of the Lineage Manager. */
    record View(
            JPanel panel,
            JTable table,
            JToggleButton pinButton,
            BiConsumer<String, ApiGroup> switchTarget,
            Consumer<Integer> stretchForWidth) {
    }

    private LineageDialog() {
    }

    static void show(MainPanel ui, ApiGroup focus) {
        showFloating(ui, focus);
    }

    static void showByKey(MainPanel ui, String bookKey, ApiGroup focus) {
        showFloatingByKey(ui, bookKey, focus);
    }

    /** Opens (or retargets and raises) the floating, draggable, resizable Duplicate &amp; Lineage Manager. */
    static void showFloating(MainPanel ui, ApiGroup focus) {
        if (focus == null || focus.bookKey() == null) {
            ui.info("Chọn 1 API để xem Bản Gốc và các lần xuất hiện trùng.");
            return;
        }
        showFloatingByKey(ui, focus.bookKey(), focus);
    }

    /** Opens (or retargets and raises) the floating manager for a specific endpoint key. */
    static void showFloatingByKey(MainPanel ui, String bookKey, ApiGroup focus) {
        if (Text.blank(bookKey)) {
            ui.info("Không tìm thấy thông tin vị trí cho API này.");
            return;
        }
        FloatingRef ref = activeFloating;
        if (ref != null) {
            MainPanel prevUi = ref.ownerUi().get();
            JDialog prevDlg = ref.dialog().get();
            if (prevUi == ui && prevDlg != null && prevDlg.isDisplayable() && prevDlg.isVisible()) {
                ref.switchTarget().accept(bookKey, focus);
                prevDlg.toFront();
                prevDlg.requestFocus();
                return;
            }
        }

        Prefs prefs = UiPrefs.resolve(null, ui.engine);
        Window owner = SwingUtilities.getWindowAncestor(ui.root());
        JDialog dlg = new JDialog(Ui.ownerWindow(owner), "", JDialog.ModalityType.MODELESS);
        dlg.setResizable(true);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        View view = buildView(ui, bookKey, focus, dlg, dlg::dispose, pinned -> {
            try {
                dlg.setAlwaysOnTop(pinned);
            } catch (SecurityException ignored) {
                // Restricted environments may deny alwaysOnTop
            }
        }, dlg::setTitle);

        dlg.setContentPane(view.panel());

        // ESC closes the floating dialog quickly
        dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeLineage");
        dlg.getRootPane().getActionMap().put("closeLineage", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dlg.dispose();
            }
        });

        // Persist user's preferred window size whenever resized or closed
        dlg.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (dlg.isVisible()) {
                    UiPrefs.setLineageSize(prefs, dlg.getWidth(), dlg.getHeight());
                }
            }
        });
        dlg.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                UiPrefs.setLineageSize(prefs, dlg.getWidth(), dlg.getHeight());
            }

            @Override
            public void windowClosed(WindowEvent e) {
                FloatingRef cur = activeFloating;
                if (cur != null && cur.dialog().get() == dlg) {
                    activeFloating = null;
                }
            }
        });

        Theme.track(dlg);
        Dimension saved = UiPrefs.lineageSize(prefs);
        dlg.setMinimumSize(new Dimension(860, 520));
        dlg.setPreferredSize(saved);
        dlg.pack();
        dlg.setSize(saved);
        view.stretchForWidth().accept(Math.max(0, saved.width - 44));

        activeFloating = new FloatingRef(new WeakReference<>(ui), new WeakReference<>(dlg), view.switchTarget());
        dlg.setLocationRelativeTo(ui.root());
        Ui.fastScroll(dlg.getContentPane());
        dlg.setVisible(true);
    }

    /** Builds the pure-Swing content panel without creating a native top-level Window (headless-safe). */
    static View buildView(
            MainPanel ui,
            String initialBookKey,
            ApiGroup initialFocus,
            java.awt.Component dialogParent,
            Runnable onClose,
            Consumer<Boolean> onPinChanged,
            Consumer<String> onTitleChanged) {
        final String[] keyRef = {initialBookKey};
        final ApiGroup[] focusRef = {initialFocus};

        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));

        RowsModel<ApiGroup> model = new RowsModel<>(COLS);
        JTable table = new ThemedTable(model);
        Ui.style(table);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        applyBaseColumnWidths(table);
        AuditRenderer renderer = new AuditRenderer(model, null, 4, null, 0, 1)
                .numberCols(0)
                .timeCols(7);
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(renderer);
        }

        StructuredAdviceCard adviceCard = new StructuredAdviceCard();
        Pill headerBadge = Pill.badge("Bản Gốc & Trùng", t -> t.dup);

        JToggleButton pinBtn = new JToggleButton("Ghim") {
            @Override
            public void updateUI() {
                super.updateUI();
                stylePinButton(this);
            }
        };
        pinBtn.setName("lineagePinBtn");
        pinBtn.setFocusable(false);
        pinBtn.setToolTipText("Ghim cửa sổ nổi trên cùng để vừa xem vừa thao tác bảng chính");
        pinBtn.addActionListener(Ui.safe(e -> {
            if (onPinChanged != null) {
                onPinChanged.accept(pinBtn.isSelected());
            }
            stylePinButton(pinBtn);
        }));
        stylePinButton(pinBtn);

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_2, 0));
        headerRight.setOpaque(false);
        headerRight.add(pinBtn);
        headerRight.add(headerBadge);

        SectionHeader header = new SectionHeader("Quản lý bản trùng & Bản Gốc (#1)", "", headerRight, null);
        JPanel headerWrap = new JPanel(new BorderLayout());
        headerWrap.setOpaque(false);
        headerWrap.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_1, Theme.SPACE_3));
        headerWrap.add(header, BorderLayout.CENTER);

        JButton keepOrigBtn = Ui.accent("Chỉ giữ Bản Gốc (#1) & Ẩn bản lặp",
                t -> Icons.originCrown(t.accent.onSolid(), 14), t -> t.accent,
                "Giữ lại duy nhất Bản Gốc (#1) trên màn hình chính và ẩn toàn bộ các bản xuất hiện lại phía sau.",
                null, () -> { });
        JButton promoteBtn = Ui.button("Đặt làm Bản Gốc (#1)", t -> Icons.star(t.info.text(), 14),
                "Đổi dòng đang chọn trở thành Bản Gốc (#1) chuẩn cho endpoint này.", () -> { });
        JButton keepCopyBtn = Ui.button("Giữ bản này", t -> Icons.check(t.success.text(), 14),
                "Xác nhận giữ bản trùng này hiển thị song song tại vị trí mới.", () -> { });
        JButton toggleRefreshBtn = Ui.button("Nhãn Tự tải lại", Icons::refresh,
                "Đánh dấu endpoint này là API tự tải lại dữ liệu (REFRESH) sau thao tác.", () -> { });
        JButton openBtn = Ui.button("Mở API", Icons::search, "Nhảy tới đúng dòng API này trong tab API.", () -> { });
        JButton editSttBtn = Ui.button("Sửa STT...", Icons::edit, "Đặt lại số thứ tự (STT) hiển thị khi copy báo cáo.", () -> { });
        JButton moveBtn = Ui.button("Chuyển nhánh...", Icons::folder, "Di chuyển bản này sang một nhánh khác trong cây.", () -> { });
        JButton moveOrigBtn = Ui.button("Về chỗ Bản Gốc", Icons::move, "Đưa bản trùng này về cùng nhánh với Bản Gốc (#1).", () -> { });
        JButton toggleVisBtn = Ui.button("Ẩn / Hiện", Icons::hide, "Ẩn hoặc hiện lại bản trùng này trên danh sách chính.", () -> { });
        JButton deleteBtn = Ui.button("Xóa bản này", t -> Icons.trash(t.danger.text(), 14),
                "Xóa vĩnh viễn bản trùng đang chọn khỏi danh sách.", () -> { });

        Runnable[] reloadRef = new Runnable[1];
        Supplier<ApiGroup> selectedFn = () -> {
            int r = table.getSelectedRow();
            return r >= 0 ? model.payload(table.convertRowIndexToModel(r)) : null;
        };

        Runnable updateHeaderTitle = () -> {
            String bk = keyRef[0];
            ApiGroup foc = focusRef[0];
            PlacementBook.Record rec = ui.engine.book().get(bk);
            String ep = foc != null ? foc.endpoint()
                    : (rec != null ? Text.nz(rec.method) + " " + Text.nz(rec.path) : bk);
            if (onTitleChanged != null) {
                onTitleChanged.accept("Quản lý bản trùng & Bản Gốc — " + ep);
            }
            header.setSubtitle("Endpoint: " + ep + "  ·  Kéo thả hoặc mở rộng cửa sổ tùy ý · Chọn dòng để xử lý");
        };

        Runnable updateDetails = () -> {
            String bk = keyRef[0];
            ApiGroup sel = selectedFn.get();
            Decisions.SmartAdvice adv = sel != null
                    ? Decisions.advise(ui.engine, sel)
                    : Decisions.adviseEndpoint(ui.engine, bk);
            adviceCard.setAdvice(adv);

            boolean hasSel = sel != null;
            ApiGroup orig = ui.engine.originOf(bk);
            boolean isOrig = hasSel && orig != null && orig.gid() == sel.gid();
            boolean hasRefresh = ui.engine.book().hasTag(bk, ApiTagCatalog.TAG_REFRESH);

            promoteBtn.setEnabled(hasSel && !isOrig);
            keepCopyBtn.setEnabled(hasSel && sel.dupPending());
            moveOrigBtn.setEnabled(hasSel && !isOrig && orig != null);
            openBtn.setEnabled(hasSel);
            editSttBtn.setEnabled(hasSel);
            moveBtn.setEnabled(hasSel);
            toggleVisBtn.setEnabled(hasSel);
            toggleVisBtn.setText(hasSel && sel.hidden() ? "Hiện lại" : "Ẩn bản này");
            deleteBtn.setEnabled(hasSel);
            toggleRefreshBtn.setText(hasRefresh ? "Gỡ nhãn Tự tải lại" : "Gắn nhãn Tự tải lại");
            keepOrigBtn.setEnabled(model.getRowCount() > 1);
            headerBadge.setText(model.getRowCount() + " lần xuất hiện");
        };

        reloadRef[0] = () -> {
            updateHeaderTitle.run();
            String bk = keyRef[0];
            ApiGroup prev = selectedFn.get();
            List<ApiGroup> copies = new ArrayList<>(ui.engine.copiesOf(bk));
            copies.sort(Comparator.comparingInt(ApiGroup::dupNo));
            ApiGroup origin = ui.engine.originOf(bk);
            List<Decisions.Row<ApiGroup>> rows = new ArrayList<>();
            for (ApiGroup c : copies) {
                boolean isOrig = origin != null && c.gid() == origin.gid();
                String role = isOrig ? "Bản Gốc (#1)" : "Bản trùng";
                String state = Decisions.copyStateShort(c);
                String tag = Decisions.copyTagLabel(c);
                String stt = c.target() != null ? "Excel #" + c.target().sttText()
                        : (c.copyStt() != null ? "STT " + c.copyStt() : "—");
                AuditLevel lvl = isOrig ? AuditLevel.OK : Decisions.copyStateLevel(c);
                rows.add(new Decisions.Row<>(c, lvl, List.of(
                        "#" + c.dupNo(), role, Text.apiLabel(c.gid()), c.branch(), state, tag, stt, c.created())));
            }
            model.set(rows);
            int idx = -1;
            if (prev != null) {
                idx = copies.indexOf(prev);
            }
            if (idx < 0 && focusRef[0] != null) {
                idx = copies.indexOf(focusRef[0]);
            }
            if (idx < 0 && !copies.isEmpty()) {
                idx = 0;
            }
            if (idx >= 0) {
                table.setRowSelectionInterval(idx, idx);
            }
            updateDetails.run();
        };

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateDetails.run();
            }
        });
        Ui.onDoubleClick(table, () -> {
            ApiGroup sel = selectedFn.get();
            if (sel != null) {
                ui.jumpToGroup(sel);
            }
        });

        keepOrigBtn.addActionListener(Ui.safe(e -> {
            int n = ui.engine.keepOnlyOriginAndHideOthers(keyRef[0]);
            ui.flash("Đã giữ Bản Gốc (#1) và ẩn " + n + " bản trùng");
            ui.tick(true);
            reloadRef[0].run();
        }));
        promoteBtn.addActionListener(Ui.safe(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel != null && ui.engine.promoteToOrigin(sel)) {
                ui.flash("Đã đặt " + Text.apiLabel(sel.gid()) + " làm Bản Gốc (#1)");
                ui.tick(true);
                reloadRef[0].run();
            }
        }));
        keepCopyBtn.addActionListener(Ui.safe(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel != null) {
                ui.engine.keepDuplicates(List.of(sel));
                ui.flash("Đã giữ bản trùng #" + sel.dupNo() + " tại " + sel.branch());
                ui.tick(true);
                reloadRef[0].run();
            }
        }));
        toggleRefreshBtn.addActionListener(Ui.safe(e -> {
            boolean next = !ui.engine.book().hasTag(keyRef[0], ApiTagCatalog.TAG_REFRESH);
            ui.engine.toggleEndpointTag(keyRef[0], ApiTagCatalog.TAG_REFRESH, next);
            ui.flash(next ? "Đã gắn nhãn Tự tải lại (REFRESH)" : "Đã gỡ nhãn Tự tải lại");
            ui.tick(true);
            reloadRef[0].run();
        }));
        openBtn.addActionListener(Ui.safe(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel != null) {
                ui.jumpToGroup(sel);
            }
        }));
        editSttBtn.addActionListener(Ui.safe(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel == null) {
                return;
            }
            String cur = sel.copyStt() != null ? String.valueOf(sel.copyStt()) : "";
            String input = JOptionPane.showInputDialog(Ui.ownerWindow(dialogParent),
                    "Nhập số thứ tự (STT) cho " + Text.apiLabel(sel.gid()) + " (để trống để tự động):", cur);
            if (input == null) {
                return;
            }
            Integer val = null;
            if (!input.isBlank()) {
                try {
                    val = Integer.parseInt(input.trim());
                } catch (NumberFormatException ex) {
                    ui.info("STT phải là số nguyên dương (ví dụ: 1, 2, 15).");
                    return;
                }
            }
            ui.engine.setApiCopyStt(sel, val);
            ui.tick(true);
            reloadRef[0].run();
        }));
        moveBtn.addActionListener(Ui.safe(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel != null) {
                ui.moveDialog(List.of(sel));
                ui.tick(true);
                reloadRef[0].run();
            }
        }));
        moveOrigBtn.addActionListener(Ui.safe(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel != null && ui.engine.moveCopyToOrigin(sel)) {
                ui.flash("Đã chuyển " + Text.apiLabel(sel.gid()) + " về cùng nhánh với Bản Gốc (#1)");
                ui.tick(true);
                reloadRef[0].run();
            }
        }));
        toggleVisBtn.addActionListener(Ui.safe(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel == null) {
                return;
            }
            if (sel.hidden()) {
                ui.engine.show(List.of(sel));
                ui.flash("Đã hiện lại bản #" + sel.dupNo());
            } else {
                ui.engine.hide(List.of(sel), "ẩn từ Quản lý bản trùng");
                ui.flash("Đã ẩn bản #" + sel.dupNo());
            }
            ui.tick(true);
            reloadRef[0].run();
        }));
        deleteBtn.addActionListener(Ui.safe(e -> {
            ApiGroup sel = selectedFn.get();
            if (sel == null) {
                return;
            }
            if (ui.confirmDialog("Xóa bản #" + sel.dupNo() + " (" + Text.apiLabel(sel.gid()) + ")?")) {
                ui.engine.remove(List.of(sel), "xóa từ Quản lý bản trùng");
                ui.tick(true);
                reloadRef[0].run();
            }
        }));

        JButton closeBtn = Ui.button("Đóng", onClose != null ? onClose : () -> { });

        JPanel primaryRow = Ui.flow(FlowLayout.LEFT, keepOrigBtn, promoteBtn, keepCopyBtn, toggleRefreshBtn);
        JPanel eastActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        eastActions.setOpaque(false);
        eastActions.add(deleteBtn);
        eastActions.add(closeBtn);

        JPanel secondaryRow = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        secondaryRow.setOpaque(false);
        secondaryRow.add(Ui.flow(FlowLayout.LEFT, openBtn, editSttBtn, moveBtn, moveOrigBtn, toggleVisBtn), BorderLayout.CENTER);
        secondaryRow.add(eastActions, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout(0, Theme.SPACE_1));
        south.setOpaque(false);
        south.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));
        south.add(primaryRow, BorderLayout.NORTH);
        south.add(secondaryRow, BorderLayout.SOUTH);

        JPanel topAdviceWrap = new JPanel(new BorderLayout());
        topAdviceWrap.setOpaque(false);
        topAdviceWrap.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_3, 0, Theme.SPACE_3));
        topAdviceWrap.add(adviceCard, BorderLayout.NORTH);

        JScrollPane tableScroll = MainPanel.scroll(table);
        tableScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                stretchPathColumn(table, tableScroll.getViewport().getWidth());
            }
        });

        JPanel tableWrap = new JPanel(new BorderLayout());
        tableWrap.setOpaque(false);
        tableWrap.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, 0, Theme.SPACE_3));
        tableWrap.add(tableScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topAdviceWrap, tableWrap);
        split.setResizeWeight(0.0);
        split.setContinuousLayout(true);
        split.setDividerSize(5);
        split.setBorder(BorderFactory.createEmptyBorder());

        root.add(headerWrap, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        reloadRef[0].run();
        int naturalAdviceH = Math.max(185, Math.min(260, adviceCard.getPreferredSize().height + 10));
        split.setDividerLocation(naturalAdviceH);

        BiConsumer<String, ApiGroup> switcher = (newKey, newFocus) -> {
            keyRef[0] = newKey;
            focusRef[0] = newFocus;
            reloadRef[0].run();
        };
        return new View(root, table, pinBtn, switcher, w -> stretchPathColumn(table, w));
    }

    private static void applyBaseColumnWidths(JTable table) {
        for (int i = 0; i < BASE_WIDTHS.length && i < table.getColumnCount(); i++) {
            TableColumn col = table.getColumnModel().getColumn(i);
            col.setMinWidth(Math.min(BASE_WIDTHS[i], 48));
            col.setPreferredWidth(BASE_WIDTHS[i]);
        }
    }

    static void stretchPathColumn(JTable table, int viewportWidth) {
        if (viewportWidth <= 0 || table.getColumnCount() <= PATH_COL) {
            return;
        }
        int others = 0;
        for (int i = 0; i < table.getColumnCount(); i++) {
            if (i != PATH_COL) {
                others += table.getColumnModel().getColumn(i).getPreferredWidth();
            }
        }
        int desired = Math.max(BASE_WIDTHS[PATH_COL], viewportWidth - others - 2);
        table.getColumnModel().getColumn(PATH_COL).setPreferredWidth(desired);
    }

    private static void stylePinButton(JToggleButton btn) {
        Theme t = Theme.current();
        boolean pinned = btn.isSelected();
        btn.setFont(t.smallBoldFont);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setIcon(Icons.themed(th -> Icons.pin(pinned ? th.accent.onSolid() : th.muted, 12)));
        btn.setText(pinned ? "Đã ghim" : "Ghim");
        if (pinned) {
            btn.setBackground(t.accent.solidFill());
            btn.setForeground(t.accent.onSolid());
            btn.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(t.accent.border(), 1),
                    BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        } else {
            btn.setBackground(t.surfaceAlt);
            btn.setForeground(t.muted);
            btn.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(t.border, 1),
                    BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        }
    }
}
