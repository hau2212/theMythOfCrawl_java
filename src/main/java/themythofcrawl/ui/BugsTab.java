package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import themythofcrawl.app.Engine;
import themythofcrawl.excel.Xlsx;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.BugMark;
import themythofcrawl.model.Role;
import themythofcrawl.ui.kit.SearchField;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * V_8.2 tab "Lỗi": every bug marked on an API, with counts by severity / type / status, filters,
 * jump-to-API and Excel / JSON export for the report or the test-management server.
 */
final class BugsTab {
    final JPanel panel = new JPanel(new BorderLayout(0, 6));
    private final MainPanel ui;
    private final Engine engine;
    private final Model model = new Model();
    final JTable table = new ThemedTable(model);
    private final JPanel stats = new JPanel(new GridLayout(1, 0, 8, 0));
    private final JLabel byType = new JLabel(" ");
    private final JComboBox<Object> typeFilter = new JComboBox<>();
    private final JComboBox<Object> sevFilter = new JComboBox<>();
    private final JComboBox<Object> statusFilter = new JComboBox<>();
    private final SearchField search = new SearchField("Tìm path, ghi chú, role...", 16);
    private List<Engine.BugRow> all = List.of();
    boolean stale = true;

    BugsTab(MainPanel ui) {
        this.ui = ui;
        this.engine = ui.engine;
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        typeFilter.addItem("Mọi loại");
        for (BugMark.Type t : BugMark.Type.values()) {
            typeFilter.addItem(t);
        }
        sevFilter.addItem("Mọi mức độ");
        for (BugMark.Severity s : BugMark.Severity.values()) {
            sevFilter.addItem(s);
        }
        statusFilter.addItem("Đang mở");
        statusFilter.addItem("Tất cả");
        for (BugMark.Status s : BugMark.Status.values()) {
            statusFilter.addItem(s);
        }
        for (JComboBox<Object> c : List.of(typeFilter, sevFilter, statusFilter)) {
            c.addActionListener(Ui.safe(e -> applyFilter()));
        }
        search.onChange(q -> applyFilter());

        JPanel north = new JPanel(new BorderLayout(0, 6));
        north.add(stats, BorderLayout.NORTH);
        byType.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        north.add(byType, BorderLayout.CENTER);
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.add(Ui.flow(FlowLayout.LEFT, typeFilter, sevFilter, statusFilter, search), BorderLayout.WEST);
        bar.add(Ui.flow(FlowLayout.RIGHT,
                Ui.button("Sửa", t -> Icons.edit(t.fg), "Sửa lỗi đang chọn", this::editSelected),
                Ui.button("Xóa", t -> Icons.trash(t.danger.text()), "Xóa dấu lỗi đang chọn", this::deleteSelected),
                Ui.button("Mở API", t -> Icons.api(t.fg), "Nhảy tới API của lỗi đang chọn", this::openSelected),
                Ui.button("Xuất Excel", t -> Icons.excel(t.fg, 13), "Danh sách lỗi + thống kê (.xlsx)", this::exportExcel),
                Ui.button("Xuất JSON", t -> Icons.save(t.fg), "Danh sách lỗi cho server quản lý", this::exportJson)),
                BorderLayout.EAST);
        north.add(bar, BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);

        Ui.style(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setDefaultRenderer(Object.class, new Renderer());
        Ui.widths(table, 56, 64, 300, 150, 130, 96, 104, 110, 260, 60, 110);
        Ui.onDoubleClick(table, this::openSelected);
        panel.add(MainPanel.scroll(table), BorderLayout.CENTER);
        panel.add(Ui.muted("Đánh dấu lỗi: chuột phải 1 API ở tab Cây / API, hoặc nút \"Đánh dấu lỗi\" ở tab Ma trận Role."
                + " Nhấp đúp 1 dòng để mở API."), BorderLayout.SOUTH);
    }

    void refreshIfVisible() {
        if (stale && ui.isShowing(panel)) {
            reload();
        }
    }

    void reload() {
        stale = false;
        all = engine.bugRows();
        Theme th = Theme.current();
        stats.removeAll();
        Map<BugMark.Severity, Integer> sev = new EnumMap<>(BugMark.Severity.class);
        Map<BugMark.Type, Integer> types = new EnumMap<>(BugMark.Type.class);
        int open = 0;
        int fixed = 0;
        for (Engine.BugRow r : all) {
            if (r.bug().status().open()) {
                open++;
                sev.merge(r.bug().severity(), 1, Integer::sum);
                types.merge(r.bug().type(), 1, Integer::sum);
            } else if (r.bug().status() == BugMark.Status.FIXED) {
                fixed++;
            }
        }
        long apis = all.stream().filter(r -> r.bug().status().open()).map(r -> r.group().gid()).distinct().count();
        stats.add(stat("Lỗi đang mở", String.valueOf(open), apis + " API", th.danger));
        for (BugMark.Severity s : BugMark.Severity.values()) {
            stats.add(stat(s.label, String.valueOf(sev.getOrDefault(s, 0)), "đang mở", th.tokenForColorKey(s.colorKey, null)));
        }
        stats.add(stat("Đã sửa", String.valueOf(fixed), "đã kiểm lại", th.success));
        StringBuilder sb = new StringBuilder("Theo loại: ");
        if (types.isEmpty()) {
            sb.append("(chưa có lỗi đang mở)");
        }
        for (Map.Entry<BugMark.Type, Integer> e : types.entrySet()) {
            sb.append(e.getKey().label).append(' ').append(e.getValue()).append("   ");
        }
        byType.setText(sb.toString().trim());
        stats.revalidate();
        stats.repaint();
        applyFilter();
    }

    private JPanel stat(String title, String value, String sub, Theme.Token tok) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(true);
        p.setBackground(tok.fill());
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(tok.border()),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        JLabel v = new JLabel(value);
        v.setFont(Theme.current().headingFont);
        v.setForeground(tok.text());
        JLabel t = new JLabel(title + " · " + sub);
        t.setForeground(tok.text());
        p.add(v, BorderLayout.CENTER);
        p.add(t, BorderLayout.SOUTH);
        return p;
    }

    private void applyFilter() {
        Object tf = typeFilter.getSelectedItem();
        Object sf = sevFilter.getSelectedItem();
        Object st = statusFilter.getSelectedItem();
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<Engine.BugRow> rows = new ArrayList<>();
        for (Engine.BugRow r : all) {
            BugMark b = r.bug();
            if (tf instanceof BugMark.Type t && b.type() != t) {
                continue;
            }
            if (sf instanceof BugMark.Severity s && b.severity() != s) {
                continue;
            }
            if ("Đang mở".equals(st) && !b.status().open()) {
                continue;
            }
            if (st instanceof BugMark.Status s && b.status() != s) {
                continue;
            }
            if (!q.isEmpty() && !(r.group().path() + " " + b.note() + " " + b.role() + " " + r.group().branch())
                    .toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            rows.add(r);
        }
        model.set(rows);
    }

    private Engine.BugRow selected() {
        int v = table.getSelectedRow();
        if (v < 0) {
            Ui.hint(table, "Chọn 1 lỗi trong bảng trước");
            return null;
        }
        return model.at(table.convertRowIndexToModel(v));
    }

    private void openSelected() {
        Engine.BugRow r = selected();
        if (r != null) {
            ui.jumpToGroup(r.group());
        }
    }

    private void editSelected() {
        Engine.BugRow r = selected();
        if (r != null && BugMarkDialog.edit(ui, r.group(), r.bug())) {
            reload();
        }
    }

    private void deleteSelected() {
        Engine.BugRow r = selected();
        if (r != null && ui.confirmDialog("Xóa dấu lỗi \"" + r.bug().type().label + "\" của " + r.group().method() + " "
                + r.group().path() + "?")) {
            engine.removeBug(r.group(), r.bug().id());
            reload();
            ui.tick(true);
        }
    }

    private void exportExcel() {
        File f = ui.save("JCrawl_loi.xlsx", "Excel", "xlsx");
        if (f == null) {
            return;
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Engine.BugRow r : engine.bugRows()) {
            BugMark b = r.bug();
            rows.add(List.of(r.group().gid(), r.group().method(), r.group().service().hostLabel(), r.group().path(),
                    r.group().branch(), b.type().label, b.type().code, b.severity().label, b.status().label, b.role(),
                    b.note(), b.by(), time(b.atMs())));
        }
        try {
            Files.write(f.toPath(), Xlsx.write(List.of(new Xlsx.OutSheet("Loi", List.of("API #", "Method", "Host", "Path",
                    "Nhánh", "Loại", "OWASP", "Mức độ", "Trạng thái", "Role", "Ghi chú", "Người", "Lúc"), rows,
                    new int[] {8, 8, 22, 44, 30, 20, 26, 14, 14, 16, 50, 10, 18}))));
            ui.flash("Đã xuất " + rows.size() + " lỗi -> " + f.getName());
        } catch (Exception ex) {
            ui.error("Xuất Excel lỗi: " + ex.getMessage());
        }
    }

    private void exportJson() {
        File f = ui.save("JCrawl_loi.json", "JSON", "json");
        if (f == null) {
            return;
        }
        try {
            Files.writeString(f.toPath(), engine.bugsJson(), StandardCharsets.UTF_8);
            ui.flash("Đã xuất danh sách lỗi -> " + f.getName());
        } catch (Exception ex) {
            ui.error("Ghi file lỗi: " + ex.getMessage());
        }
    }

    static String time(long ms) {
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM")
                .format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()));
    }

    // ------------------------------------------------------------------ table
    static final class Model extends AbstractTableModel {
        static final String[] COLS = {"API #", "Method", "Path", "Nhánh", "Loại", "Mức độ", "Trạng thái", "Role", "Ghi chú",
            "Người", "Lúc"};
        private List<Engine.BugRow> rows = List.of();

        void set(List<Engine.BugRow> r) {
            rows = r;
            fireTableDataChanged();
        }

        Engine.BugRow at(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            Engine.BugRow row = rows.get(r);
            BugMark b = row.bug();
            ApiGroup g = row.group();
            return switch (c) {
                case 0 -> g.gid();
                case 1 -> g.method();
                case 2 -> g.path();
                case 3 -> g.branch();
                case 4 -> b.type();
                case 5 -> b.severity();
                case 6 -> b.status();
                case 7 -> b.role();
                case 8 -> b.note();
                case 9 -> b.by();
                default -> time(b.atMs());
            };
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Integer.class : Object.class;
        }
    }

    private static final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, focus, row, col);
            Theme th = Theme.current();
            setFont(col == 2 ? th.monoFont : th.baseFont);
            setToolTipText(null);
            if (!sel) {
                setBackground(t.getBackground());
                setForeground(t.getForeground());
                if (v instanceof BugMark.Severity s) {
                    Theme.Token tok = th.tokenForColorKey(s.colorKey, null);
                    setBackground(tok.fill());
                    setForeground(tok.text());
                } else if (v instanceof BugMark.Type ty) {
                    setToolTipText(ty.code + " — " + ty.explain);
                } else if (v instanceof BugMark.Status s && !s.open()) {
                    setForeground(th.muted);
                } else if (col == 1) {
                    setForeground(th.method(String.valueOf(v)).text());
                }
            } else if (v instanceof BugMark.Type ty) {
                setToolTipText(ty.code + " — " + ty.explain);
            }
            return this;
        }
    }

    /** Small form to add / edit a bug mark. */
    static final class BugMarkDialog {
        private BugMarkDialog() {
        }

        /** Marks a new bug on {@code g}; {@code type} / {@code role} may prefill (e.g. from the Ma trận Role). */
        static boolean mark(MainPanel ui, ApiGroup g, BugMark.Type type, String role, String note) {
            return show(ui, g, null, type, role, note);
        }

        static boolean edit(MainPanel ui, ApiGroup g, BugMark b) {
            return show(ui, g, b, b.type(), b.role(), b.note());
        }

        private static boolean show(MainPanel ui, ApiGroup g, BugMark existing, BugMark.Type type, String role, String note) {
            if (g == null) {
                Ui.hint(ui.root(), "Chọn 1 API trước rồi mới đánh dấu lỗi");
                return false;
            }
            JComboBox<BugMark.Type> typeBox = new JComboBox<>(BugMark.Type.values());
            typeBox.setSelectedItem(type != null ? type : BugMark.Type.IDOR);
            JLabel typeHelp = Ui.muted(" ");
            Runnable help = () -> {
                BugMark.Type t = (BugMark.Type) typeBox.getSelectedItem();
                typeHelp.setText(t.code + " — " + t.explain);
            };
            typeBox.addActionListener(Ui.safe(e -> help.run()));
            help.run();
            JComboBox<BugMark.Severity> sevBox = new JComboBox<>(BugMark.Severity.values());
            sevBox.setSelectedItem(existing != null ? existing.severity() : BugMark.Severity.HIGH);
            JComboBox<BugMark.Status> stBox = new JComboBox<>(BugMark.Status.values());
            stBox.setSelectedItem(existing != null ? existing.status() : BugMark.Status.SUSPECT);
            JComboBox<String> roleBox = new JComboBox<>();
            roleBox.addItem("");
            for (Role r : ui.engine.roles().all()) {
                roleBox.addItem(r.name());
            }
            roleBox.setEditable(true);
            roleBox.setSelectedItem(role == null ? "" : role);
            JTextArea noteArea = new JTextArea(note == null ? "" : note, 4, 40);
            noteArea.setLineWrap(true);
            noteArea.setWrapStyleWord(true);
            JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
            form.add(Ui.bold(g.method() + " " + g.path()));
            form.add(Ui.flow(FlowLayout.LEFT, new JLabel("Loại:"), typeBox));
            form.add(typeHelp);
            form.add(Ui.flow(FlowLayout.LEFT, new JLabel("Mức độ:"), sevBox, new JLabel("Trạng thái:"), stBox));
            form.add(Ui.flow(FlowLayout.LEFT, new JLabel("Role tái hiện được:"), roleBox));
            JPanel p = new JPanel(new BorderLayout(0, 6));
            p.add(form, BorderLayout.NORTH);
            p.add(new JLabel("Ghi chú / cách tái hiện:"), BorderLayout.CENTER);
            p.add(new JScrollPane(noteArea), BorderLayout.SOUTH);
            int ok = JOptionPane.showConfirmDialog(Ui.ownerWindow(ui.root()), p,
                    existing == null ? "Đánh dấu lỗi" : "Sửa lỗi", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) {
                return false;
            }
            BugMark.Type t = (BugMark.Type) typeBox.getSelectedItem();
            BugMark.Severity s = (BugMark.Severity) sevBox.getSelectedItem();
            BugMark.Status st = (BugMark.Status) stBox.getSelectedItem();
            String r = roleBox.getSelectedItem() == null ? "" : roleBox.getSelectedItem().toString();
            BugMark b = existing == null
                    ? BugMark.create(t, s, st, r, noteArea.getText(), ui.engine.inventoryCatalog().memberName())
                    : existing.with(t, s, st, r, noteArea.getText());
            ui.engine.putBug(g, b);
            ui.flash((existing == null ? "Đã đánh dấu lỗi: " : "Đã sửa lỗi: ") + b.shortText());
            ui.tick(true);
            return true;
        }
    }
}
