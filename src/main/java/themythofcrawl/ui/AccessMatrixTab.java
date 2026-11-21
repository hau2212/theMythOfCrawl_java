package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import themythofcrawl.app.Engine;
import themythofcrawl.core.AccessMatrix;
import themythofcrawl.core.ExpectedAccess;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Role;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * V_7.0 tab "Ma trận Role": crawl each role once, the extension records which role's UI reached each
 * API ("Thấy bởi"), replays every API with every role ("Gọi được") and flags the difference as
 * Broken Access Control candidates, exported as test cases.
 */
final class AccessMatrixTab {
    final JPanel panel = new JPanel(new BorderLayout());
    private final MainPanel ui;
    private final Engine engine;
    private final MatrixModel model = new MatrixModel();
    final JTable table = new ThemedTable(model);
    private final JCheckBox onlyFindings = new JCheckBox("Chỉ hiện NGHI BAC / Vi phạm");
    private final JLabel summary = Ui.muted(" ");
    private final JLabel explain = new JLabel(" ");
    private AccessMatrix matrix;
    boolean stale = true;

    AccessMatrixTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        JPanel left = Ui.flow(FlowLayout.LEFT, Ui.bold("Đang crawl bằng:"), crawlRoleCombo(engine),
                Ui.button("Tính lại", t -> Icons.refresh(t.fg, 13), "Tính lại ma trận từ kết quả hiện có", () -> {
                    rebuild();
                    ui.flash("Đã tính lại: " + matrix.rows().size() + " API, " + matrix.findings().size() + " nghi BAC / vi phạm");
                }),
                Ui.button("Gửi role chưa gửi", t -> Icons.sendAll(t.fg), "Gửi mọi role còn 'Chưa gửi' để có cột Gọi được",
                        () -> {
                            int n = engine.sendAllUnsent();
                            ui.flash("Đã xếp hàng " + n + " request role chưa gửi");
                        }),
                onlyFindings);
        onlyFindings.setOpaque(false);
        onlyFindings.addActionListener(Ui.safe(e -> applyFilter()));
        JPanel right = Ui.flow(FlowLayout.RIGHT,
                Ui.button("Quyền mong đợi...", t -> Icons.sliders(t.fg), "Khai báo nhánh nào chỉ dành cho role nào",
                        this::editExpected),
                Ui.button("Xuất test case Excel", t -> Icons.excel(t.fg, 13), "Ma trận + danh sách test case BAC (.xlsx)",
                        this::exportExcel),
                Ui.button("Xuất JSON", t -> Icons.save(t.fg), "Test case BAC dạng JSON cho server quản lý", this::exportJson),
                Ui.button("Đánh dấu lỗi", t -> Icons.alertMark(t.danger.text(), 13),
                        "Đánh dấu ô đang chọn là lỗi (tự điền loại, vai trò, bằng chứng)", this::markSelectedCell),
                Ui.button("Đẩy sang Logger++", t -> Icons.send(t.fg), "Gửi lại API đã chọn 1 lần để Logger++ ghi lại",
                        () -> LoggerPushDialog.show(ui, selectedOrAll())));
        top.add(left, BorderLayout.CENTER);
        top.add(right, BorderLayout.EAST);

        JPanel legend = Ui.flow(FlowLayout.LEFT, Ui.muted("Cách đọc:"));
        for (AccessMatrix.Verdict v : new AccessMatrix.Verdict[] {AccessMatrix.Verdict.SEEN_OK, AccessMatrix.Verdict.DENIED_OK,
            AccessMatrix.Verdict.BAC, AccessMatrix.Verdict.VIOLATION, AccessMatrix.Verdict.DIFF, AccessMatrix.Verdict.SEEN_DENIED,
            AccessMatrix.Verdict.UNKNOWN_OK}) {
            JLabel l = new JLabel(" " + v.shortLabel + " = " + v.label + " ");
            l.setOpaque(true);
            Theme th = Theme.current();
            l.setBackground(th.audit(v.level).fill());
            l.setForeground(th.audit(v.level).text());
            l.setToolTipText(v.explain);
            legend.add(l);
        }
        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        north.add(legend, BorderLayout.CENTER);
        north.add(Ui.flow(FlowLayout.LEFT, summary), BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);

        Ui.style(table);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setCellSelectionEnabled(true);
        table.setDefaultRenderer(Object.class, new CellRenderer());
        table.getSelectionModel().addListSelectionListener(e -> showExplain());
        table.getColumnModel().getSelectionModel().addListSelectionListener(e -> showExplain());
        Ui.onDoubleClick(table, () -> {
            ApiGroup g = model.groupAt(table.getSelectedRow());
            if (g != null) {
                ui.jumpToGroup(g);
            }
        });
        panel.add(MainPanel.scroll(table), BorderLayout.CENTER);
        explain.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        panel.add(explain, BorderLayout.SOUTH);
    }

    /** "Đang crawl bằng" selector shared by the Cây and Ma trận Role tabs: auto-detect or a fixed role. */
    static JComboBox<String> crawlRoleCombo(Engine engine) {
        String auto = "Tự nhận diện (token/cookie)";
        JComboBox<String> box = new JComboBox<>();
        Runnable fill = () -> {
            DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
            m.addElement(auto);
            for (Role r : engine.roles().all()) {
                m.addElement(r.name());
            }
            m.addElement(Engine.CRAWL_ACCOUNT);
            String cur = engine.crawlRoleOverride();
            m.setSelectedItem(cur == null ? auto : cur);
            box.setModel(m);
        };
        fill.run();
        box.setToolTipText("Role của tài khoản bạn đang dùng để crawl. Tự nhận diện = so token/cookie của request với rule"
                + " từng Role. API đã có mà role khác cũng thấy sẽ chỉ được ghi thêm vào 'Thấy bởi', không tạo bản trùng.");
        boolean[] filling = {false};
        box.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                filling[0] = true;
                fill.run();
                filling[0] = false;
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        box.addActionListener(Ui.safe(e -> {
            if (filling[0]) {
                return;
            }
            Object sel = box.getSelectedItem();
            String next = sel == null || auto.equals(sel) ? null : sel.toString();
            if (!java.util.Objects.equals(next, engine.crawlRoleOverride())) {
                engine.setCrawlRoleOverride(next);
            }
        }));
        return box;
    }

    void refreshIfVisible() {
        if (stale && ui.isShowing(panel)) {
            rebuild();
        }
    }

    void rebuild() {
        stale = false;
        matrix = engine.accessMatrix(engine.groups());
        applyFilter();
        int bac = 0;
        int vio = 0;
        for (AccessMatrix.Finding f : matrix.findings()) {
            if (f.verdict() == AccessMatrix.Verdict.VIOLATION) {
                vio++;
            } else {
                bac++;
            }
        }
        Set<String> crawled = matrix.crawledRoles();
        summary.setText(matrix.rows().size() + " API · " + matrix.roles().size() + " role · Đã crawl bằng: "
                + (crawled.isEmpty() ? "(chưa có)" : String.join(", ", crawled))
                + " · NGHI BAC: " + bac + " · Vi phạm quyền mong đợi: " + vio
                + " · " + engine.expectedAccess().rules().size() + " luật quyền mong đợi");
    }

    AccessMatrix matrix() {
        return matrix;
    }

    private void applyFilter() {
        if (matrix == null) {
            return;
        }
        List<AccessMatrix.Row> rows = new ArrayList<>();
        for (AccessMatrix.Row r : matrix.rows()) {
            if (!onlyFindings.isSelected() || r.hasFinding()) {
                rows.add(r);
            }
        }
        model.set(matrix.roles(), rows);
        int[] widths = {56, 64, 280, 200, 150};
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(i < widths.length ? widths[i] : 96);
        }
    }

    private void showExplain() {
        int r = table.getSelectedRow();
        int c = table.getSelectedColumn();
        AccessMatrix.Cell cell = model.cellAt(r, c);
        if (cell == null) {
            ApiGroup g = model.groupAt(r);
            explain.setText(g == null ? " " : "API #" + g.gid() + " · Thấy bởi: "
                    + (g.seenBy().isEmpty() ? "(chưa ghi nhận)" : String.join(", ", g.seenBy()))
                    + " — nhấp đúp để mở API");
            return;
        }
        explain.setText(cell.role() + ": " + cell.verdict().label + " — " + cell.verdict().explain
                + (cell.similarity() >= 0 ? " (giống bản gốc " + Math.round(cell.similarity() * 100) + "%)" : ""));
    }

    private void markSelectedCell() {
        int r = table.getSelectedRow();
        ApiGroup g = model.groupAt(r);
        if (g == null) {
            Ui.hint(table, "Chọn 1 ô vai trò (cột bên phải) của API cần đánh dấu");
            return;
        }
        AccessMatrix.Cell cell = model.cellAt(r, table.getSelectedColumn());
        themythofcrawl.model.BugMark.Type type = cell != null && cell.verdict() == AccessMatrix.Verdict.VIOLATION
                ? themythofcrawl.model.BugMark.Type.PRIVILEGE : themythofcrawl.model.BugMark.Type.IDOR;
        String note = cell == null ? "" : "Vai trò " + cell.role() + " gọi " + g.method() + " " + g.path() + " -> HTTP "
                + cell.status() + (cell.similarity() >= 0 ? ", giống bản gốc " + Math.round(cell.similarity() * 100) + "%" : "")
                + "; thấy trong UI bởi: " + (g.seenBy().isEmpty() ? "(chưa ai)" : String.join(", ", g.seenBy()));
        BugsTab.BugMarkDialog.mark(ui, g, type, cell == null ? "" : cell.role(), note);
    }

    private List<ApiGroup> selectedOrAll() {
        ApiGroup g = model.groupAt(table.getSelectedRow());
        return g != null ? List.of(g) : engine.groups();
    }

    private void editExpected() {
        List<String> roleNames = new ArrayList<>();
        for (Role r : engine.roles().all()) {
            roleNames.add(r.name());
        }
        List<String> branches = new ArrayList<>(engine.knownPaths());
        java.util.Collections.sort(branches);
        JComboBox<String> branchBox = new JComboBox<>(branches.toArray(new String[0]));
        branchBox.setEditable(true);
        JList<String> roleList = new JList<>(roleNames.toArray(new String[0]));
        roleList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        roleList.setVisibleRowCount(8);
        StringBuilder current = new StringBuilder();
        for (ExpectedAccess.Rule r : engine.expectedAccess().rules()) {
            current.append("• ").append(r.sentence()).append('\n');
        }
        JPanel p = new JPanel(new BorderLayout(6, 6));
        javax.swing.JTextArea cur = new javax.swing.JTextArea(current.length() == 0 ? "(chưa có luật)" : current.toString(), 5, 50);
        cur.setEditable(false);
        cur.setLineWrap(true);
        p.add(new JScrollPane(cur), BorderLayout.NORTH);
        JPanel form = new JPanel(new BorderLayout(4, 4));
        form.add(Ui.flow(FlowLayout.LEFT, new JLabel("Nhánh (kèm nhánh con):"), branchBox), BorderLayout.NORTH);
        form.add(new JLabel("Chỉ những role được dùng nhánh này (Ctrl+click chọn nhiều; bỏ trống + OK = xóa luật):"),
                BorderLayout.CENTER);
        form.add(new JScrollPane(roleList), BorderLayout.SOUTH);
        p.add(form, BorderLayout.CENTER);
        int ok = JOptionPane.showConfirmDialog(Ui.ownerWindow(panel), p, "Quyền mong đợi theo nhánh", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION || branchBox.getSelectedItem() == null) {
            return;
        }
        String branch = branchBox.getSelectedItem().toString().trim();
        if (branch.isEmpty()) {
            return;
        }
        List<String> picked = roleList.getSelectedValuesList();
        if (picked.isEmpty()) {
            engine.expectedAccess().remove(branch);
        } else {
            engine.expectedAccess().put(new ExpectedAccess.Rule(branch, new LinkedHashSet<>(picked), ""));
        }
        engine.saveExpectedAccess();
        rebuild();
    }

    private void exportExcel() {
        if (matrix == null) {
            rebuild();
        }
        File f = ui.save("JCrawl_BAC_testcases.xlsx", "Excel", "xlsx");
        if (f == null) {
            return;
        }
        try {
            List<Xlsx.OutSheet> sheets = List.of(
                    new Xlsx.OutSheet("Test case BAC", AccessMatrix.findingHeader(), matrix.findingRows(),
                            new int[] {22, 18, 8, 8, 22, 44, 30, 18, 24, 8, 10, 60, 12, 14}),
                    new Xlsx.OutSheet("Ma trận Role", matrix.matrixHeader(), matrix.matrixRows(),
                            matrixWidths(matrix.matrixHeader().size())));
            Files.write(f.toPath(), Xlsx.write(sheets));
            ui.flash("Đã xuất " + matrix.findings().size() + " test case -> " + f.getName());
        } catch (Exception ex) {
            ui.error("Xuất Excel lỗi: " + ex.getMessage());
        }
    }

    static int[] matrixWidths(int n) {
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            w[i] = switch (i) {
                case 0, 1 -> 8;
                case 3 -> 44;
                case 4 -> 30;
                case 5 -> 24;
                case 2 -> 22;
                default -> 22;
            };
        }
        return w;
    }

    private void exportJson() {
        if (matrix == null) {
            rebuild();
        }
        File f = ui.save("JCrawl_BAC_testcases.json", "JSON", "json");
        if (f == null) {
            return;
        }
        try {
            Files.writeString(f.toPath(), matrix.findingsJson(), StandardCharsets.UTF_8);
            ui.flash("Đã xuất " + matrix.findings().size() + " test case -> " + f.getName());
        } catch (Exception ex) {
            ui.error("Ghi file lỗi: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ table
    static final class MatrixModel extends AbstractTableModel {
        private static final String[] FIXED = {"#", "Method", "Path", "Nhánh", "Thấy bởi"};
        private List<String> roles = List.of();
        private List<AccessMatrix.Row> rows = List.of();

        void set(List<String> roles, List<AccessMatrix.Row> rows) {
            this.roles = roles;
            this.rows = rows;
            fireTableStructureChanged();
        }

        ApiGroup groupAt(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r).group() : null;
        }

        AccessMatrix.Cell cellAt(int r, int c) {
            if (r < 0 || r >= rows.size() || c < FIXED.length || c - FIXED.length >= roles.size()) {
                return null;
            }
            return rows.get(r).cells().get(roles.get(c - FIXED.length));
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return FIXED.length + roles.size();
        }

        @Override
        public String getColumnName(int c) {
            return c < FIXED.length ? FIXED[c] : roles.get(c - FIXED.length);
        }

        @Override
        public Object getValueAt(int r, int c) {
            ApiGroup g = rows.get(r).group();
            return switch (c) {
                case 0 -> g.gid();
                case 1 -> g.method();
                case 2 -> g.path();
                case 3 -> g.branch();
                case 4 -> String.join(", ", g.seenBy());
                default -> {
                    AccessMatrix.Cell cell = cellAt(r, c);
                    yield cell == null ? "" : cell;
                }
            };
        }
    }

    private static final class CellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            Theme th = Theme.current();
            Object shown = value instanceof AccessMatrix.Cell c
                    ? c.verdict().shortLabel + (c.status() != null && c.verdict() != AccessMatrix.Verdict.NOT_SENT ? " " + c.status() : "")
                    : value;
            super.getTableCellRendererComponent(t, shown, sel, focus, row, col);
            setFont(col == 2 ? th.monoFont : (value instanceof AccessMatrix.Cell c && c.verdict().finding
                    ? th.boldFont : th.baseFont));
            if (value instanceof AccessMatrix.Cell c) {
                Theme.Token tok = th.audit(c.verdict().level);
                if (!sel) {
                    setBackground(tok.fill());
                    setForeground(tok.text());
                }
                setToolTipText(c.verdict().label + " — " + c.verdict().explain);
            } else {
                if (!sel) {
                    setBackground(t.getBackground());
                    setForeground(col == 1 ? th.method(String.valueOf(value)).text() : t.getForeground());
                }
                setToolTipText(null);
            }
            return this;
        }
    }
}
