package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.Config;
import themythofcrawl.excel.TargetList;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Service;
import themythofcrawl.store.Workspaces;
import themythofcrawl.app.Bridge;
import themythofcrawl.model.RoleScopes;

/**
 * Opens the real UI outside Burp with sample data and saves a screenshot of every tab
 * (run under Xvfb: {@code xvfb-run java -cp ... themythofcrawl.UiPreview <outDir> [workspaceRoot]}).
 */
public final class UiPreview {
    static final class DemoBridge implements Bridge {
        @Override
        public boolean inScope(String url) {
            return true;
        }

        @Override
        public Sent send(Service s, byte[] request) {
            String raw = request == null ? "" : new String(request, StandardCharsets.ISO_8859_1);
            String firstLine = raw.split("\r?\n", 2)[0];
            int st = 200;
            String note = "ok";
            if (firstLine.contains("/admin/stat")) {
                if (raw.contains("ADMIN_TOKEN")) {
                    st = 200;
                    note = "admin_stats_ok";
                } else if (raw.contains("EXPIRED_TOKEN")) {
                    st = 401;
                    note = "expired_token";
                } else if (!raw.contains("Authorization:") && !raw.contains("JSESSIONID=")) {
                    st = 200; // Guest bypass!
                    note = "guest_bypass_warning";
                } else {
                    st = 403;
                    note = "forbidden";
                }
            } else if (firstLine.contains("/users/")) {
                if (raw.contains("EXPIRED_TOKEN") || (!raw.contains("Authorization:") && !raw.contains("JSESSIONID="))) {
                    st = 401;
                    note = "unauthorized";
                } else if (raw.contains("USER_C_TOKEN") || raw.contains("tenant-b")) {
                    st = 403;
                    note = "cross_tenant_denied";
                } else if (raw.contains("USER_B_TOKEN")) {
                    st = 200;
                    note = "idor_different_body_length_extra_payload_content";
                }
            } else if (firstLine.contains("/orders")) {
                if (raw.contains("EXPIRED_TOKEN")) {
                    st = 500;
                    note = "server_error_null_principal";
                } else if (!raw.contains("ADMIN_TOKEN") && !raw.contains("MANAGER_TOKEN") && !raw.contains("USER_A_TOKEN")) {
                    st = 403;
                    note = "role_blocked";
                }
            } else if (firstLine.contains("/login")) {
                st = 200;
            }
            String body = "{\"status\":" + st + ",\"detail\":\"" + note + "\"}";
            return new Sent(("HTTP/1.1 " + st + " X\r\nContent-Type: application/json\r\n\r\n" + body)
                    .getBytes(StandardCharsets.ISO_8859_1), 12, null);
        }

        @Override
        public void toRepeater(Service s, byte[] request, String tabName) {
        }

        @Override
        public void out(String line) {
        }

        @Override
        public void err(String line) {
        }
    }

    static final class PlainHost implements UiHost {
        @Override
        public Viewer requestViewer(boolean editable) {
            return viewer(editable);
        }

        @Override
        public Viewer responseViewer() {
            return viewer(false);
        }

        private static Viewer viewer(boolean editable) {
            JTextArea t = new JTextArea();
            t.setEditable(editable);
            JScrollPane s = new JScrollPane(t);
            return new Viewer() {
                @Override
                public Component component() {
                    return s;
                }

                @Override
                public void show(Service service, byte[] message) {
                    t.setText(message == null ? "" : new String(message, StandardCharsets.ISO_8859_1));
                    t.setCaretPosition(0);
                }

                @Override
                public byte[] edited() {
                    return null;
                }
            };
        }

        @Override
        public void applyTheme(Component c) {
            if (System.getProperty("laf") != null) {
                SwingUtilities.updateComponentTreeUI(c);   // what a host theme pass does to a component tree
            }
            if (Boolean.getBoolean("simulateBurp")) {
                simulateBurpTheme(c);
            }
        }

        static void simulateBurpTheme(Component c) {
            if (c instanceof javax.swing.JTree tree) {
                tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer());
            } else if (c instanceof javax.swing.JTable table) {
                javax.swing.table.DefaultTableCellRenderer def = new javax.swing.table.DefaultTableCellRenderer();
                table.setDefaultRenderer(Object.class, def);
                table.setDefaultRenderer(String.class, def);
                table.setDefaultRenderer(Integer.class, def);
                for (int i = 0; i < table.getColumnModel().getColumnCount(); i++) {
                    table.getColumnModel().getColumn(i).setCellRenderer(def);
                }
            }
            if (c instanceof java.awt.Container container) {
                for (Component child : container.getComponents()) {
                    simulateBurpTheme(child);
                }
            }
        }

        @Override
        public String projectName() {
            return "Demo project";
        }
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/preview");
        Files.createDirectories(out);
        String laf = System.getProperty("laf");
        if (laf != null) {
            javax.swing.UIManager.setLookAndFeel(laf);
            themythofcrawl.ui.theme.Theme.onThemeChanged();
        }
        Path root = Files.createTempDirectory("tmoc-ws");
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), root.resolve("x.jar").toString());
        ws.setRoot(root);
        DemoBridge bridge = new DemoBridge();
        Engine e = new Engine(bridge, prefs, null);
        e.open(ws.start("demo", "Demo project"));
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.sendProfile = Config.SendProfile.CUSTOM;
        c.sendDelayMs = 0;
        c.sendJitterPct = 0;
        e.applyConfig(c);

        // Include a realistic expired JWT on 09_ExpiredToken so the JWT inspector showcases syntax colors + warning pill
        List<themythofcrawl.model.Role> demoRoles = new ArrayList<>(e.roles().all());
        for (int i = 0; i < demoRoles.size(); i++) {
            themythofcrawl.model.Role r = demoRoles.get(i);
            if (r.name().contains("Expired")) {
                String sampleJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                        + "eyJzdWIiOiJ1c2VyXzQ0MiIsInJvbGUiOiJhZG1pbiIsImV4cCI6MTYwMDAwMDAwMH0."
                        + "demoSigPlaceholder";
                demoRoles.set(i, new themythofcrawl.model.Role(r.name(), true,
                        List.of(themythofcrawl.model.HeaderRule.of("Authorization", "Bearer " + sampleJwt, "REPLACE"),
                                themythofcrawl.model.HeaderRule.of("X-Expired", "true", "ADD"))));
            }
        }
        e.roles().setAll(demoRoles);
        e.saveRoles();

        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("STT", "PROTOCOL", "PATH", "POSITION"));
        String[] paths = {"/api/v1/login", "/api/v1/users/{id}", "/api/v1/contact/send", "/api/v1/orders", "/api/v1/orders/{id}",
            "/api/v1/cart", "/api/v1/profile", "/api/v1/search", "/api/v1/missing", "/api/v1/admin/stats"};
        for (int i = 0; i < paths.length; i++) {
            rows.add(List.of(String.valueOf(200 + i), i % 3 == 0 ? "POST" : "GET", paths[i], i == 2 ? "> contact > button \"send\"" : ""));
        }
        e.setTargets(TargetList.fromRows(rows, "danh_sach_api.xlsx", "Sheet1"));
        String[][] flow = {
            {"Toppage > menu > button \"Login\"", "POST /api/v1/login", "GET /api/v1/profile"},
            {"Toppage > menu > Users", "GET /api/v1/users/15", "GET /api/v1/users/16", "GET /api/v1/search?q=a"},
            {"Toppage > contact > button \"send\"", "POST /api/v1/contact/send", "GET /api/v1/profile"},
            {"Toppage > Orders", "GET /api/v1/orders", "GET /api/v1/orders/77", "POST /api/v1/cart", "GET /api/v1/admin/stats"},
            {"Toppage > Orders > Chi tiết", "GET /api/v1/orders/78/items", "GET /api/v1/orders/78/invoice"},
        };
        for (String[] f : flow) {
            e.setDestination(f[0]);
            for (int i = 1; i < f.length; i++) {
                String[] mp = f[i].split(" ");
                e.process(UiSmokeTest.msg(mp[0], mp[1]), "Proxy", true, e.activeBranch().id());
            }
        }
        List<ApiGroup> all = e.groups();
        e.confirm(all.subList(0, Math.min(5, all.size())), "");
        for (int i = 0; i < all.size(); i++) {
            if (all.size() > 4 && i == all.size() - 3) {
                continue; // keep one API unsent to showcase Chưa gửi badge
            }
            e.replay(all.get(i), e.defaultTargets(all.get(i), true));
        }
        for (int wait = 0; wait < 60 && e.queue().queued() + e.queue().inFlight() > 0; wait++) {
            Thread.sleep(100);
        }
        Thread.sleep(300);
        if (all.size() > 7) {
            e.remove(List.of(all.get(all.size() - 2)), "demo");
        }
        Thread.sleep(300);
        MainPanel[] ui = new MainPanel[1];
        JFrame[] fr = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            ui[0] = new MainPanel(e, ws, new PlainHost(), "demo");
            JFrame f = new JFrame("preview");
            f.getContentPane().add(ui[0].root(), BorderLayout.CENTER);
            f.setSize(new Dimension(1500, 950));
            f.setVisible(true);
            fr[0] = f;
        });
        javax.swing.JTabbedPane tabs = find(ui[0].root(), javax.swing.JTabbedPane.class);
        String only = System.getProperty("only");
        if ("gate".equals(only)) {
            SwingUtilities.invokeAndWait(() -> {
                e.setProxyGate(new themythofcrawl.core.ProxyGate.Settings(true, themythofcrawl.core.ProxyGate.Scope.HOST_PATH,
                        "api.example.test", "/api/v1/orders", "", java.util.Set.of("POST", "PUT", "PATCH", "DELETE"),
                        themythofcrawl.core.ProxyGate.Effect.FAKE_200));
                ui[0].syncHeader();
                JFrame f = new JFrame("gate");
                ProxyGateDialog.Gate gate = new ProxyGateDialog.Gate(e, e.groups().get(0), null);
                f.getContentPane().add(new javax.swing.JScrollPane(gate.panel));
                f.setSize(new Dimension(860, 720));
                f.setVisible(true);
                fr[0].setSize(new Dimension(1500, 120));
                fr[0].validate();
            });
            Thread.sleep(900);
            shot(fr[0], out.resolve("only_header.png").toFile());
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof JFrame jf && "gate".equals(jf.getTitle())) {
                    shot(jf, out.resolve("only_gate.png").toFile());
                }
            }
            System.exit(0);
        }
        if ("wb".equals(only)) {
            SwingUtilities.invokeAndWait(() -> {
                JFrame f = new JFrame("wb");
                f.getContentPane().add(ActionWorkbenchDialog.buildWorkbenchPanel(null, e, null, null, 1, null, null));
                f.setSize(new Dimension(1120, 730));
                f.setVisible(true);
                fr[0].dispose();
                fr[0] = f;
            });
            Thread.sleep(900);
            shot(fr[0], out.resolve("only_wb.png").toFile());
            System.exit(0);
        }
        if ("dp".equals(only)) {
            SwingUtilities.invokeAndWait(() -> {
                JFrame f = new JFrame("dp");
                f.getContentPane().add(DataProtectionDialog.build(f, e, e.groups().get(e.groups().size() > 5 ? 5 : 0), null));
                f.setSize(new Dimension(1000, 740));
                f.setVisible(true);
                fr[0].dispose();
                fr[0] = f;
            });
            Thread.sleep(900);
            shot(fr[0], out.resolve("only_dp.png").toFile());
            System.exit(0);
        }
        if (only != null) {
            // Quick mode: -Donly=<tab title> shoots just that tab with every folder expanded, then exits
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (tabs.getTitleAt(i).equalsIgnoreCase(only) || String.valueOf(i).equals(only)) {
                    int idx = i;
                    SwingUtilities.invokeAndWait(() -> {
                        tabs.setSelectedIndex(idx);
                        ui[0].tick(true);
                        ui[0].matrix.rebuild();
                        ui[0].treeTab.tree.expandFolders(true);
                        List<ApiGroup> gs = e.groups();
                        if (!gs.isEmpty() && Boolean.getBoolean("expandFirst")) {
                            ui[0].treeTab.tree.expandApi(gs.get(0).gid());
                            ui[0].treeTab.tree.rebuild(false);
                            ui[0].treeTab.tree.expandFolders(true);
                        }
                    });
                    Thread.sleep(900);
                    shot(fr[0], out.resolve("only_" + only.replaceAll("[^\\p{L}0-9]+", "_") + ".png").toFile());
                }
            }
            System.exit(0);
        }
        for (int i = 0; i < tabs.getTabCount(); i++) {
            int idx = i;
            SwingUtilities.invokeAndWait(() -> {
                tabs.setSelectedIndex(idx);
                if (tabs.getComponentAt(idx) == ui[0].roles.panel && e.roles().count() > 8) {
                    ui[0].roles.selectRole(8);
                }
            });
            Thread.sleep(700);
            String name = String.format("%02d_%s", i, tabs.getTitleAt(i).replaceAll("[^\\p{L}0-9]+", "_"));
            shot(fr[0], out.resolve(name + ".png").toFile());
        }
        MainPanel m = ui[0];
        step(fr[0], out, "10_api_chon", () -> {
            m.tabs.setSelectedIndex(0);
            List<ApiGroup> cur = e.groups();
            if (!cur.isEmpty()) {
                m.api.select(cur.get(cur.size() - 1), null);
            }
        });
        step(fr[0], out, "11_audit_giu_cho", () -> {
            m.tabs.setSelectedComponent(m.audit.panel);
            m.audit.show(1, all.get(0).bookKey());
        });
        step(fr[0], out, "12_audit_nhat_ky", () -> {
            m.tabs.setSelectedComponent(m.audit.panel);
            m.audit.show(3, null);
        });
        step(fr[0], out, "13_audit_trung", () -> {
            m.tabs.setSelectedComponent(m.audit.panel);
            m.audit.show(2, null);
        });
        step(fr[0], out, "14_cay_chon", () -> {
            m.tabs.setSelectedComponent(m.treeTab.panel);
            m.tick(true);
            m.treeTab.tree.selectGroup(all.get(0));
        });
        step(fr[0], out, "15_spotlight", () -> {
            m.tabs.setSelectedIndex(0);
            m.spotlight.open();
            m.spotlight.searchField().setText("api");
        });
        step(fr[0], out, "16_history_matrix", () -> {
            m.spotlight.close();
            m.tabs.setSelectedComponent(m.history.panel);
            m.history.report.setViewMode(HistoryReportView.MODE_MATRIX);
        });
        step(fr[0], out, "17_history_text", () -> {
            m.tabs.setSelectedComponent(m.history.panel);
            m.history.report.setViewMode(HistoryReportView.MODE_TEXT);
        });
        step(fr[0], out, "18_settings_diag", () -> {
            m.tabs.setSelectedComponent(m.settings.panel);
            m.settings.searchField().setText("chẩn đoán");
        });
        dialog(out, "20_dlg_role_scope", () -> m.roleScopeDialog(false, RoleScopes.Scope.BRANCH, List.of("Toppage > menu"), null, null));
        dialog(out, "21_dlg_push", () -> m.pushDialog(false, all, "Tất cả API"));
        dialog(out, "22_dlg_levels", () -> LevelsDialog.show(m.root(), e));
        step(fr[0], out, "23_dup_popup", () -> {
            m.tabs.setSelectedIndex(0);
            DupPopup.show(m, all.get(1), m.api.table, 200, 60);
        });
        step(fr[0], out, "24_narrow_1100", () -> {
            fr[0].setSize(new Dimension(1100, 820));
            m.tabs.setSelectedIndex(0);
            fr[0].validate();
            m.headerPanel().doLayout();
        });
        if (laf != null && laf.contains("Flat")) {
            String swapped = themythofcrawl.ui.theme.Theme.current().dark
                    ? "com.formdev.flatlaf.FlatLightLaf" : "com.formdev.flatlaf.FlatDarkLaf";
            step(fr[0], out, "25_runtime_laf_switch", () -> {
                try {
                    javax.swing.UIManager.setLookAndFeel(swapped);
                    SwingUtilities.updateComponentTreeUI(fr[0]);
                    themythofcrawl.ui.theme.Theme.onThemeChanged();
                    fr[0].validate();
                    fr[0].repaint();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
        SwingUtilities.invokeAndWait(() -> {
            ui[0].dispose();
            fr[0].dispose();
        });
        e.close();
        System.out.println("screenshots in " + out.toAbsolutePath());
        System.exit(0);
    }

    static void step(JFrame f, Path out, String name, Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
        Thread.sleep(700);
        shot(f, out.resolve(name + ".png").toFile());
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof javax.swing.JWindow || w.getClass().getName().contains("Popup")) {
                    w.setVisible(false);
                }
            }
            javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
        });
    }

    /** Opens a modal dialog, saves a picture of it, closes it. */
    static void dialog(Path out, String name, Runnable open) throws Exception {
        SwingUtilities.invokeLater(open);
        Thread.sleep(1200);
        java.awt.Window[] d = new java.awt.Window[1];
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window w : java.awt.Window.getWindows()) {
                if (w instanceof javax.swing.JDialog && w.isShowing()) {
                    d[0] = w;
                }
            }
        });
        if (d[0] == null) {
            System.out.println("no dialog for " + name);
            return;
        }
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            Component c = ((javax.swing.JDialog) d[0]).getContentPane();
            BufferedImage b = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = b.createGraphics();
            c.paint(g);
            g.dispose();
            img[0] = b;
        });
        ImageIO.write(img[0], "png", out.resolve(name + ".png").toFile());
        SwingUtilities.invokeAndWait(d[0]::dispose);
        Thread.sleep(300);
    }

    static <T> T find(Component c, Class<T> type) {
        if (type.isInstance(c)) {
            return type.cast(c);
        }
        if (c instanceof java.awt.Container k) {
            for (Component x : k.getComponents()) {
                T t = find(x, type);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    static void shot(JFrame f, File file) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            Component c = f.getContentPane();
            var tx = f.getGraphicsConfiguration() != null
                    ? f.getGraphicsConfiguration().getDefaultTransform()
                    : null;
            double sx = tx != null && tx.getScaleX() > 0 ? tx.getScaleX() : 1.0;
            double sy = tx != null && tx.getScaleY() > 0 ? tx.getScaleY() : 1.0;
            int pw = Math.max(1, (int) Math.round(c.getWidth() * sx));
            int ph = Math.max(1, (int) Math.round(c.getHeight() * sy));
            BufferedImage b = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = b.createGraphics();
            if (sx != 1.0 || sy != 1.0) {
                g.scale(sx, sy);
            }
            c.paint(g);
            g.dispose();
            img[0] = b;
        });
        ImageIO.write(img[0], "png", file);
    }
}
