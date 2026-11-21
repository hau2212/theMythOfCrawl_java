package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.place.Decisions;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.kit.ThemedTree;

/** Verifies Phase 0 fixes: diagnostics strings and Burp renderer protection in ThemedTree / ThemedTable. */
class ThemedComponentTest {
    @Test
    void themedTreeRetainsExtensionRendererAcrossForeignOverwriteAndUpdateUi() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Node root = new Node(Node.Kind.ROOT, "", "Root", AuditLevel.NONE, "R", 0);
            DefaultTreeModel model = new DefaultTreeModel(root);
            TreeRenderer ext = new TreeRenderer();
            ThemedTree tree = new ThemedTree(model, ext);
            tree.setRowHeight(26);
            tree.setLargeModel(true);
            tree.setToggleClickCount(2);

            tree.setCellRenderer(new DefaultTreeCellRenderer());
            assertSame(ext, tree.getCellRenderer(), "foreign DefaultTreeCellRenderer must be ignored");

            tree.setCellRenderer(null);
            assertSame(ext, tree.getCellRenderer(), "null renderer must be ignored once extension renderer is installed");

            SwingUtilities.updateComponentTreeUI(tree);
            assertSame(ext, tree.getCellRenderer(), "updateComponentTreeUI must retain extension renderer");
            assertEquals(26, tree.getRowHeight());
            assertTrue(tree.isLargeModel());
            assertEquals(2, tree.getToggleClickCount());
            assertFalse(ext.isOpaque());

            TreeRenderer ext2 = new TreeRenderer();
            tree.setCellRenderer(ext2);
            assertSame(ext2, tree.getCellRenderer(), "replacing with another extension renderer is allowed");
        });
    }

    @Test
    void themedTableRetainsRenderersStyleAndMovedColumnsAcrossBurpTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RowsModel<String> model = new RowsModel<>(List.of("ColA", "ColB", "ColC"));
            model.set(List.of(new Decisions.Row<>("x", AuditLevel.OK, List.of("1", "GET", "/v1/a"))));
            ThemedTable table = new ThemedTable(model);
            Ui.style(table);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

            AuditRenderer mainRenderer = new AuditRenderer(model, null, null, 1, 0);
            AuditRenderer copyRenderer = new AuditRenderer(model, null, null, null, AuditRenderer.Mode.COPY);
            table.setDefaultRenderer(Object.class, mainRenderer);
            table.getColumnModel().getColumn(0).setCellRenderer(copyRenderer);
            table.getColumnModel().getColumn(1).setCellRenderer(mainRenderer);
            table.getColumnModel().getColumn(2).setCellRenderer(mainRenderer);

            // Simulate column reordering (e.g. ApiTab moves Copy/Excel/Dup columns)
            table.getColumnModel().moveColumn(0, 2);

            // Simulate Burp applying DefaultTableCellRenderer to defaults and columns
            DefaultTableCellRenderer foreign = new DefaultTableCellRenderer();
            table.setDefaultRenderer(Object.class, foreign);
            for (int i = 0; i < table.getColumnCount(); i++) {
                table.getColumnModel().getColumn(i).setCellRenderer(foreign);
            }
            SwingUtilities.updateComponentTreeUI(table);

            assertSame(mainRenderer, table.getDefaultRenderer(Object.class));
            assertSame(copyRenderer, table.getColumn("ColA").getCellRenderer());
            assertSame(mainRenderer, table.getColumn("ColB").getCellRenderer());
            assertSame(mainRenderer, table.getColumn("ColC").getCellRenderer());
            assertEquals(themythofcrawl.ui.theme.Theme.ROW_TABLE, table.getRowHeight());
            assertEquals(JTable.AUTO_RESIZE_OFF, table.getAutoResizeMode());
            assertTrue(table.getShowHorizontalLines());
            assertFalse(table.getShowVerticalLines());
            assertTrue(table.getFillsViewportHeight());
            assertTrue(table.getTableHeader().getFont().isBold());

            // Structure change rebuilds TableColumns from TableModel and should re-attach extension renderers
            model.fireTableStructureChanged();
            assertSame(copyRenderer, table.getColumn("ColA").getCellRenderer());
            assertSame(mainRenderer, table.getColumn("ColB").getCellRenderer());
            assertSame(mainRenderer, table.getColumn("ColC").getCellRenderer());
        });
    }

    @Test
    void booleanCheckboxColumnRemainsNullRendererAfterSimulateBurp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AbstractTableModel model = new AbstractTableModel() {
                @Override
                public int getRowCount() {
                    return 1;
                }

                @Override
                public int getColumnCount() {
                    return 2;
                }

                @Override
                public Class<?> getColumnClass(int c) {
                    return c == 0 ? Boolean.class : String.class;
                }

                @Override
                public Object getValueAt(int r, int c) {
                    return c == 0 ? Boolean.TRUE : "Role";
                }
            };
            ThemedTable t = new ThemedTable(model);
            UiPreview.PlainHost.simulateBurpTheme(t);
            assertNull(t.getColumnModel().getColumn(0).getCellRenderer(),
                    "columns without custom extension renderer stay null so Boolean checkboxes keep rendering");
        });
    }

    @Test
    void mainPanelKeepsAllExtensionRenderersAndShowsDiagnosticsUnderSimulatedBurpTheme(@TempDir java.nio.file.Path dir)
            throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(ws.start("p", "Project"));
        e.setDestination("Toppage > menu");
        e.process(UiSmokeTest.msg("GET", "/api/v1/users/1"), "Proxy", true, e.activeBranch().id());
        List<ApiGroup> all = e.groups();
        assertEquals(1, all.size());

        UiHost burpLikeHost = new UiHost() {
            private final UiHost delegate = new UiSmokeTest.HeadlessHost();

            @Override
            public Viewer requestViewer(boolean editable) {
                return delegate.requestViewer(editable);
            }

            @Override
            public Viewer responseViewer() {
                return delegate.responseViewer();
            }

            @Override
            public void applyTheme(Component c) {
                SwingUtilities.updateComponentTreeUI(c);
                UiPreview.PlainHost.simulateBurpTheme(c);
            }

            @Override
            public String projectName() {
                return "Project";
            }
        };

        MainPanel[] ui = new MainPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            ui[0] = new MainPanel(e, ws, burpLikeHost, "p");
            for (int i = 0; i < ui[0].tabs.getTabCount(); i++) {
                ui[0].tabs.setSelectedIndex(i);
                ui[0].tick(true);
            }
            // Re-run simulateBurpTheme after all tabs have populated data (runtime theme switch)
            burpLikeHost.applyTheme(ui[0].root());

            List<JTree> trees = collect(ui[0].root(), JTree.class);
            assertTrue(trees.size() >= 4, "expected BranchTree + LogTab tree instances");
            for (JTree t : trees) {
                assertInstanceOf(ThemedTree.class, t);
                assertInstanceOf(ThemedTree.ExtensionRenderer.class, t.getCellRenderer(),
                        "tree renderer must survive Burp applyTheme");
            }

            // API, Branch, Results, Audit, Excel tables must keep AuditRenderer
            for (JTable table : List.of(ui[0].api.table, ui[0].api.branchTable, ui[0].api.results)) {
                assertInstanceOf(ThemedTable.class, table);
                for (int col = 0; col < table.getColumnCount(); col++) {
                    TableCellRenderer cr = table.getColumnModel().getColumn(col).getCellRenderer();
                    assertInstanceOf(ThemedTree.ExtensionRenderer.class, cr,
                            "table column " + col + " lost its extension renderer");
                }
            }

            // Diagnostics assertions
            String loadLine = Diagnostics.loadLogLine();
            assertTrue(loadLine.startsWith("theMythOf-JCrawl "), loadLine);
            assertTrue(loadLine.contains(" loaded · LAF="), loadLine);
            assertTrue(loadLine.contains("dark="), loadLine);

            String summary = Diagnostics.summary();
            assertTrue(summary.contains("LAF: "), summary);
            assertTrue(summary.contains("font="), summary);
            assertNotNull(ui[0].status.getToolTipText());
            assertTrue(ui[0].status.getToolTipText().contains("LAF:"));

            List<String> labels = collect(ui[0].settings.panel, JLabel.class).stream().map(JLabel::getText).toList();
            assertTrue(labels.stream().anyMatch(s -> s != null && s.contains("Panel.background=")),
                    "Settings Section 8 should populate theme colors");
            assertTrue(labels.stream().anyMatch(s -> s != null && s.contains("sun.java2d.uiScale=")),
                    "Settings Section 8 should populate scale & font");

            ui[0].dispose();
        });
        e.close();
    }

    @Test
    void headerFitsTwoRowsAt1100PxAndToastAndPopoverWork(@TempDir java.nio.file.Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(ws.start("p", "Project"));
        e.setTargets(themythofcrawl.excel.TargetList.fromRows(
                List.of(List.of("STT", "PROTOCOL", "PATH"), List.of("1", "GET", "/api/v1/users/{id}")),
                "t.xlsx", "S"));
        e.setDestination("Toppage > menu > button \"Search\" > confirm");
        e.process(UiSmokeTest.msg("GET", "/api/v1/users/1"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Toppage > second");
        e.process(UiSmokeTest.msg("GET", "/api/v1/users/2"), "Proxy", true, e.activeBranch().id());

        SwingUtilities.invokeAndWait(() -> {
            MainPanel panel = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "p");
            panel.tick(true);
            java.awt.Dimension pref = panel.headerPanel().getPreferredSize();
            assertTrue(pref.height <= 64, "2-row header preferred height must be <= 64 px, was " + pref.height);

            panel.root().setSize(1100, 760);
            panel.root().doLayout();
            panel.headerPanel().doLayout();
            assertTrue(panel.headerPanel().getHeight() <= 68,
                    "header at 1100px width should stay within 2 compact rows, height=" + panel.headerPanel().getHeight());
            assertTrue(pref.width <= 1100, "header preferred width should fit 1100px window, was " + pref.width);

            assertTrue(panel.status.getText().startsWith("API "), panel.status.getText());
            assertTrue(panel.dest.path().contains(">"), panel.dest.path());

            themythofcrawl.ui.kit.ToastPane.setAnimationsEnabled(false);
            panel.flash("Đã copy đường dẫn Test");
            assertTrue(panel.toastPane.isToastVisible(), "flash() must reveal non-blocking ToastPane toast");
            assertEquals("Đã copy đường dẫn Test", panel.toastPane.lastMessage());
            panel.toastPane.doLayout();
            panel.toastPane.hideToast();
            assertFalse(panel.toastPane.isToastVisible(), "hideToast() must hide toast");
            themythofcrawl.ui.kit.ToastPane.setAnimationsEnabled(true);

            javax.swing.JPopupMenu pop = Ui.popover(panel.status, "Trợ giúp", "Nội dung mẫu");
            assertEquals(1, pop.getComponentCount());
            assertInstanceOf(themythofcrawl.ui.kit.Card.class, pop.getComponent(0),
                    "Ui.popover must render inside a themed Card");

            panel.dispose();
        });
        e.close();
    }

    @Test
    void renderersHandleNullAndEmptyValuesAndHeaderTruncatesWithoutHtml(@TempDir java.nio.file.Path dir) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(320, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            try {
                // Null AuditRows model + null cell value across every column mode
                for (AuditRenderer.Mode mode : AuditRenderer.Mode.values()) {
                    AuditRenderer r = new AuditRenderer(null, 0, 1, 2, mode, 0).pathCols(3).numberCols(4).deltaCol(5).timeCols(6);
                    for (int col = 0; col <= 7; col++) {
                        Component comp = r.getTableCellRendererComponent(null, null, col == 0, false, 0, col);
                        comp.setSize(120, 28);
                        comp.paint(g);
                    }
                }

                // Narrow table column -> HeaderRenderer truncates & supplies full tooltip
                RowsModel<String> m = new RowsModel<>(List.of("VeryLongHeaderColumnTitleThatOverflows", "B"));
                ThemedTable t = new ThemedTable(m);
                Ui.style(t);
                t.getColumnModel().getColumn(0).setWidth(48);
                AuditRenderer.HeaderRenderer hdr = (AuditRenderer.HeaderRenderer) t.getTableHeader().getDefaultRenderer();
                Component hc = hdr.getTableCellRendererComponent(t, "VeryLongHeaderColumnTitleThatOverflows", false, false, -1, 0);
                hc.setSize(48, 28);
                hc.paint(g);
                assertEquals("VeryLongHeaderColumnTitleThatOverflows", ((javax.swing.JComponent) hc).getToolTipText());

                // TreeRenderer handles null value, plain string value, and GROUP nodes without HTML
                TreeRenderer tr = new TreeRenderer();
                Component nullComp = tr.getTreeCellRendererComponent(null, null, false, false, true, 0, false);
                assertFalse(tr.getText().contains("<html>"));
                nullComp.setSize(160, 26);
                nullComp.paint(g);
                Component strComp = tr.getTreeCellRendererComponent(null, "Plain", true, false, true, 1, false);
                strComp.setSize(160, 26);
                strComp.paint(g);
            } finally {
                g.dispose();
            }
        });
    }

    @Test
    void multiSelectFilterChipsPinnedOrigSortAndBranchTreeChipHitTestWork(@TempDir java.nio.file.Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(ws.start("p", "Project"));
        e.setTargets(themythofcrawl.excel.TargetList.fromRows(
                List.of(List.of("STT", "PROTOCOL", "PATH"), List.of("1", "GET", "/api/v1/ok")),
                "t.xlsx", "S"));
        e.setDestination("Toppage > menu");
        e.process(UiSmokeTest.msg("GET", "/api/v1/ok"), "Proxy", true, e.activeBranch().id());
        e.process(UiSmokeTest.msg("GET", "/api/v1/warn"), "Proxy", true, e.activeBranch().id());
        // Capture duplicate of /api/v1/ok on a second branch so DUP badge renders on the tree row
        e.setDestination("Toppage > second");
        e.process(UiSmokeTest.msg("GET", "/api/v1/ok"), "Proxy", true, e.activeBranch().id());

        List<ApiGroup> all = e.groups();
        assertEquals(3, all.size());
        ApiGroup gOk = all.get(0);
        ApiGroup gWarn = all.get(1);
        ApiGroup gDupOk = all.get(2);

        gOk.origResult().setStatus(200);
        gOk.origResult().setLength(100);
        gOk.setOrigMeta(200, 100);
        // Set role results: one OK (200), one INFO (403), one WARN bypass, one ERROR
        var r0 = gOk.results().get(0);
        r0.setStatus(200);
        r0.setLength(100);
        r0.setState(themythofcrawl.model.ResultState.DONE);
        var r1 = gOk.results().get(1);
        r1.setStatus(403);
        r1.setLength(15);
        r1.setState(themythofcrawl.model.ResultState.DONE);
        var r2 = gOk.results().get(2);
        r2.setStatus(500);
        r2.setLength(40);
        r2.setState(themythofcrawl.model.ResultState.DONE);

        gWarn.origResult().setStatus(200);
        gWarn.origResult().setLength(200);
        gWarn.setOrigMeta(200, 200);
        boolean setDenied = false;
        for (var r : gWarn.results()) {
            if (!r.strippedAuth() && !setDenied) {
                r.setStatus(403);
                r.setLength(20);
                setDenied = true;
            } else {
                r.setStatus(200);
                r.setLength(200);
            }
            r.setState(themythofcrawl.model.ResultState.DONE);
        }
        gOk.setPending(false);
        gWarn.setPending(false);
        gDupOk.setPending(true);
        themythofcrawl.core.Audit.recalc(gOk);
        themythofcrawl.core.Audit.recalc(gWarn);

        SwingUtilities.invokeAndWait(() -> {
            MainPanel panel = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "p");
            panel.api.reloadAll();
            panel.api.tree.rebuild(true);

            // Multi-select filter chips in ApiTab
            assertEquals(3, panel.api.groupModel.getRowCount());
            panel.api.toggleAuditFilter(AuditLevel.PENDING);
            assertEquals(1, panel.api.groupModel.getRowCount());
            assertSame(gDupOk, panel.api.groupModel.at(0));

            panel.api.toggleAuditFilter(AuditLevel.WARN);
            assertEquals(2, panel.api.groupModel.getRowCount());
            panel.api.toggleAuditFilter(AuditLevel.PENDING);
            assertEquals(1, panel.api.groupModel.getRowCount());
            assertSame(gWarn, panel.api.groupModel.at(0));
            panel.api.clearAuditFilters();
            assertEquals(3, panel.api.groupModel.getRowCount());

            // Result table sorting keeps GOC pinned at view row 0 in ASC and DESC
            panel.api.showGroup(gOk);
            JTable res = panel.api.results;
            res.getRowSorter().toggleSortOrder(4); // Status ASC
            assertEquals("GỐC", res.getValueAt(0, 0));
            res.getRowSorter().toggleSortOrder(4); // Status DESC
            assertEquals("GỐC", res.getValueAt(0, 0));
            res.getRowSorter().toggleSortOrder(5); // Body Len ASC
            assertEquals("GỐC", res.getValueAt(0, 0));
            res.getRowSorter().toggleSortOrder(5); // Body Len DESC
            assertEquals("GỐC", res.getValueAt(0, 0));
            res.getRowSorter().toggleSortOrder(6); // Delta DESC
            assertEquals("GỐC", res.getValueAt(0, 0));

            // BranchTree chip hit testing (Copy, Excel, Dup badges)
            BranchTree bt = panel.api.tree;
            panel.api.setTreeShowsApis(true); // V_7.0: API rows in the tree are opt-in (legacy mode)
            bt.rebuild(false);
            bt.expandFolders(true);
            bt.tree.setSize(720, 480);
            bt.tree.doLayout();
            boolean hitCopy = false;
            boolean hitExcel = false;
            boolean hitDup = false;
            TreeRenderer tr = (TreeRenderer) bt.tree.getCellRenderer();
            for (int row = 0; row < bt.tree.getRowCount(); row++) {
                javax.swing.tree.TreePath tp = bt.tree.getPathForRow(row);
                Node node = (Node) tp.getLastPathComponent();
                if (node.kind == Node.Kind.GROUP && node.payload == gDupOk) {
                    java.awt.Rectangle bounds = bt.tree.getPathBounds(tp);
                    assertNotNull(bounds);
                    tr.getTreeCellRendererComponent(bt.tree, node, false, false, false, row, false);
                    assertFalse(tr.getText().contains("<html>"));
                    int y = bounds.y + bounds.height / 2;
                    for (int dx = 0; dx < bounds.width; dx += 4) {
                        Object[] hit = bt.chipAt(bounds.x + dx, y);
                        if (hit != null) {
                            if (hit[0] == BranchTree.Chip.COPY) {
                                hitCopy = true;
                            } else if (hit[0] == BranchTree.Chip.EXCEL) {
                                hitExcel = true;
                            } else if (hit[0] == BranchTree.Chip.DUP) {
                                hitDup = true;
                            }
                        }
                    }
                }
            }
            assertTrue(hitCopy, "BranchTree.chipAt must hit Copy icon on GROUP row");
            assertTrue(hitExcel, "BranchTree.chipAt must hit Excel badge on GROUP row");
            assertTrue(hitDup, "BranchTree.chipAt must hit Dup badge on GROUP row");

            panel.dispose();
        });
        e.close();
    }

    @Test
    void historyReportViewKpisModesHeatmapJumpAnd5000ApiVirtualization(@TempDir java.nio.file.Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(ws.start("p", "Project"));
        e.setTargets(themythofcrawl.excel.TargetList.fromRows(
                List.of(List.of("STT", "PROTOCOL", "PATH"),
                        List.of("1", "GET", "/api/v1/ok"),
                        List.of("2", "GET", "/api/v1/missing")),
                "cov.xlsx", "S"));

        // Branch 1 (default): ok, bypass, error
        e.setDestination("Toppage > auth");
        e.process(UiSmokeTest.msg("GET", "/api/v1/ok"), "Proxy", true, e.activeBranch().id());
        e.process(UiSmokeTest.msg("GET", "/api/v1/bypass"), "Proxy", true, e.activeBranch().id());
        e.process(UiSmokeTest.msg("GET", "/api/v1/err"), "Proxy", true, e.activeBranch().id());
        // Branch 2: empty branch
        e.setDestination("Toppage > empty");
        // Branch 3: diff + unsent
        e.setDestination("Toppage > orders");
        e.process(UiSmokeTest.msg("GET", "/api/v1/diff"), "Proxy", true, e.activeBranch().id());
        e.process(UiSmokeTest.msg("GET", "/api/v1/unsent"), "Proxy", true, e.activeBranch().id());

        List<ApiGroup> gs = e.groups();
        assertEquals(5, gs.size());
        ApiGroup gOk = gs.get(0);
        ApiGroup gBypass = gs.get(1);
        ApiGroup gErr = gs.get(2);
        ApiGroup gDiff = gs.get(3);
        ApiGroup gUnsent = gs.get(4);

        gOk.setOrigMeta(200, 100);
        gOk.origResult().setStatus(200);
        gOk.origResult().setLength(100);
        for (var r : gOk.results()) {
            r.setStatus(200);
            r.setLength(100);
            r.setState(themythofcrawl.model.ResultState.DONE);
        }

        gBypass.setOrigMeta(200, 120);
        gBypass.origResult().setStatus(200);
        gBypass.origResult().setLength(120);
        boolean deniedOnce = false;
        for (var r : gBypass.results()) {
            if (!r.strippedAuth() && !deniedOnce) {
                r.setStatus(403);
                r.setLength(10);
                deniedOnce = true;
            } else {
                r.setStatus(200);
                r.setLength(120);
            }
            r.setState(themythofcrawl.model.ResultState.DONE);
        }

        gErr.setOrigMeta(200, 80);
        gErr.origResult().setStatus(200);
        gErr.origResult().setLength(80);
        for (int i = 0; i < gErr.results().size(); i++) {
            var r = gErr.results().get(i);
            if (i == 0) {
                r.setState(themythofcrawl.model.ResultState.ERROR);
                r.setError("Timeout");
            } else {
                r.setStatus(200);
                r.setLength(80);
                r.setState(themythofcrawl.model.ResultState.DONE);
            }
        }

        gDiff.setOrigMeta(200, 90);
        gDiff.origResult().setStatus(200);
        gDiff.origResult().setLength(90);
        for (var r : gDiff.results()) {
            if (r.strippedAuth()) {
                r.setStatus(401);
                r.setLength(20);
            } else {
                r.setStatus(403);
                r.setLength(10);
            }
            r.setState(themythofcrawl.model.ResultState.DONE);
        }

        for (ApiGroup g : List.of(gOk, gBypass, gErr, gDiff, gUnsent)) {
            themythofcrawl.core.Audit.recalc(g);
        }

        SwingUtilities.invokeAndWait(() -> {
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "p");
            ui.root().setSize(1100, 760);
            ui.root().doLayout();
            HistoryReportView rv = ui.history.report;
            rv.setSize(680, 620);
            rv.doLayout();
            rv.setData(e.branches(), e.groups(), e.targets());

            // 1. KPI strip counts & Excel coverage
            assertEquals("5", rv.kpiValue(1)); // Total API
            assertEquals("1", rv.kpiValue(2)); // OK
            assertEquals("1", rv.kpiValue(3)); // Lệch
            assertEquals("1", rv.kpiValue(4)); // Bypass
            assertEquals("1", rv.kpiValue(5)); // Lỗi
            assertEquals("1", rv.kpiValue(6)); // Chưa gửi
            assertEquals("50%", rv.kpiValue(7)); // Excel 1/2
            assertEquals(e.branches().size(), rv.branchCardCount());

            // 2. Empty branch displays an EmptyState card body
            List<themythofcrawl.ui.kit.EmptyState> emptyStates = collect(rv, themythofcrawl.ui.kit.EmptyState.class);
            assertFalse(emptyStates.isEmpty(), "empty branch card must display an EmptyState placeholder");

            // 3. Heatmap tooltip + click-to-jump onto specific (ApiGroup, RoleResult)
            HistoryReportView.BranchRowsPanel brp = rv.rowsPanelForBranch(gDiff.branchId());
            assertNotNull(brp);
            brp.setSize(640, 60);
            var hitRole = gDiff.results().get(0);
            java.awt.Point center = brp.roleSquareCenter(0, 0);
            assertNotNull(center);
            java.awt.event.MouseEvent hoverEv = new java.awt.event.MouseEvent(brp,
                    java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, center.x, center.y, 0, false);
            String tip = brp.getToolTipText(hoverEv);
            assertNotNull(tip);
            assertTrue(tip.contains(hitRole.roleName() + ": 403 (10 B)"), "tooltip was: " + tip);

            java.awt.event.MouseEvent clickEv = new java.awt.event.MouseEvent(brp,
                    java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                    java.awt.event.InputEvent.BUTTON1_DOWN_MASK, center.x, center.y, 1, false,
                    java.awt.event.MouseEvent.BUTTON1);
            brp.dispatchEvent(clickEv);
            assertSame(ui.api.panel, ui.tabs.getSelectedComponent());
            assertSame(gDiff, ui.api.currentGroup());
            assertSame(hitRole, ui.api.selectedResult());

            // 4. Mode switching (Ma trận + Văn bản) & Burp renderer retention
            ui.selectTab(ui.history.panel);
            rv.setViewMode(HistoryReportView.MODE_MATRIX);
            UiPreview.PlainHost.simulateBurpTheme(rv);
            ThemedTable mat = rv.matrixTable();
            assertEquals(5, mat.getRowCount());
            assertTrue(mat.getColumnCount() >= 5 + gOk.results().size());
            for (int col = 0; col < mat.getColumnCount(); col++) {
                assertInstanceOf(ThemedTree.ExtensionRenderer.class,
                        mat.getColumnModel().getColumn(col).getCellRenderer());
            }

            rv.setViewMode(HistoryReportView.MODE_TEXT);
            assertTrue(rv.summaryTextArea().getText().contains("BÁO CÁO LỊCH SỬ"));

            // 5. Collapse / Expand all & scrollToBranchPath
            rv.setViewMode(HistoryReportView.MODE_CARDS);
            rv.expandAll(false);
            assertNull(rv.rowsPanelForBranch(gDiff.branchId()), "collapsed card hides row body");
            assertTrue(rv.scrollToBranchPath("Toppage > orders"));
            assertNotNull(rv.rowsPanelForBranch(gDiff.branchId()), "scrolling to branch auto-expands it");

            // Paint & dynamic theme switch check
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(680, 620,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2 = img.createGraphics();
            try {
                rv.paint(g2);
                themythofcrawl.ui.theme.Theme.onThemeChanged();
            } finally {
                g2.dispose();
            }
            ui.dispose();
        });
        e.close();

        // 6. Scale test: 5000 APIs across 200 branches < 300 ms and virtualized card realization
        Engine big = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        themythofcrawl.core.Config cfg = big.config().copy();
        cfg.captureEnabled = true;
        cfg.confirmNewApis = false;
        big.applyConfig(cfg);
        try {
            for (int i = 0; i < 5000; i++) {
                if (i % 25 == 0) {
                    big.setDestination("Toppage > m" + (i / 500) + " > b" + (i / 25));
                }
                big.process(UiSmokeTest.msg("GET", "/api/v1/r" + i + "/" + i), "Proxy", true, big.activeBranch().id());
            }
            SwingUtilities.invokeAndWait(() -> {
                HistoryReportView bigView = new HistoryReportView(null, null);
                bigView.setSize(720, 600);
                bigView.doLayout();
                long t0 = System.nanoTime();
                bigView.setData(big.branches(), big.groups(), big.targets());
                bigView.doLayout();
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                assertTrue(elapsedMs < 300, "5000-API HistoryReportView setData+layout must be < 300 ms, took " + elapsedMs + " ms");
                assertEquals("5000", bigView.kpiValue(1));
                assertTrue(bigView.branchCardCount() >= 200);
                assertTrue(bigView.realizedCardCount() < 50,
                        "virtualized card list should materialize only viewport cards, realized=" + bigView.realizedCardCount());
            });
        } finally {
            big.close();
        }
    }

    @Test
    void phase5RolesSettingsLogExcelTreeAuditChecklistAndDupPopup(@TempDir java.nio.file.Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
        e.open(ws.start("p5", "Project5"));

        // Configure two roles with multiple rules + valid/expired JWTs to exercise RoleTable & JWT inspector
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String hdr = b64.encodeToString("{\"alg\":\"HS256\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String expiredPayload = b64.encodeToString("{\"sub\":\"admin\",\"exp\":1600000000}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String validPayload = b64.encodeToString("{\"sub\":\"staff\",\"exp\":4110000000}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String expiredJwt = hdr + "." + expiredPayload + ".sig";
        String validJwt = hdr + "." + validPayload + ".sig";
        e.roles().setAll(List.of(
                new themythofcrawl.model.Role("01_Admin", true, List.of(
                        themythofcrawl.model.HeaderRule.of("Authorization", "Bearer " + expiredJwt, "REPLACE"),
                        themythofcrawl.model.HeaderRule.of("X-Role", "admin", "ADD"),
                        themythofcrawl.model.HeaderRule.of("X-Debug", "[REMOVE]", "REMOVE"),
                        themythofcrawl.model.HeaderRule.of("SESSION", "s1", "COOKIE_SET"),
                        themythofcrawl.model.HeaderRule.of("TRACK", "[REMOVE]", "COOKIE_REMOVE"))),
                new themythofcrawl.model.Role("02_Staff", true, List.of(
                        themythofcrawl.model.HeaderRule.of("Authorization", "Bearer " + validJwt, "REPLACE"))),
                new themythofcrawl.model.Role("03_Disabled", false, List.of())));
        e.saveRoles();

        themythofcrawl.core.Config cfg = e.config().copy();
        cfg.confirmNewApis = true;
        cfg.dupNewPlace = true;
        e.applyConfig(cfg);
        e.setDestination("Toppage > phase5");
        e.process(UiSmokeTest.msg("GET", "/api/v1/users"), "Proxy", true, e.activeBranch().id());
        e.setDestination("Toppage > second");
        e.process(UiSmokeTest.msg("GET", "/api/v1/users"), "Proxy", true, e.activeBranch().id());
        e.log("Lỗi kết nối upstream", null);

        SwingUtilities.invokeAndWait(() -> {
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "p5");
            ui.tick(true);

            // 1. RolesTab: row height, initials, JWT syntax colors + exp badge, RuleChipsRenderer paint
            assertEquals("AD", RolesTab.initialsOf("01_Admin"));
            assertEquals("GU", RolesTab.initialsOf("Guest"));
            ThemedTable roleTable = ui.roles.roleTable();
            assertEquals(themythofcrawl.ui.theme.Theme.current().scaled(36), roleTable.getRowHeight());
            assertNotNull(ui.roles.originalProfileCard());
            roleTable.setRowSelectionInterval(0, 0);
            assertTrue(ui.roles.expBadge().isVisible());
            assertTrue(ui.roles.expBadge().getText().contains("hết hạn"), ui.roles.expBadge().getText());
            javax.swing.text.StyledDocument doc = ui.roles.jwtInspector().getStyledDocument();
            assertTrue(doc.getLength() > 0);
            java.util.Set<java.awt.Color> jwtColors = new java.util.HashSet<>();
            for (int i = 0; i < doc.getLength(); i++) {
                jwtColors.add(javax.swing.text.StyleConstants.getForeground(doc.getCharacterElement(i).getAttributes()));
            }
            assertTrue(jwtColors.size() >= 3, "JWT JSON syntax highlighter should produce >= 3 distinct token colors");

            roleTable.setRowSelectionInterval(1, 1);
            assertTrue(ui.roles.expBadge().isVisible());
            assertTrue(ui.roles.expBadge().getText().contains("còn hạn"), ui.roles.expBadge().getText());

            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(640, 120, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2 = img.createGraphics();
            try {
                for (int r = 0; r < roleTable.getRowCount(); r++) {
                    for (int c = 0; c < roleTable.getColumnCount(); c++) {
                        Component cell = roleTable.prepareRenderer(roleTable.getCellRenderer(r, c), r, c);
                        cell.setSize(160, roleTable.getRowHeight());
                        cell.paint(g2);
                    }
                }
            } finally {
                g2.dispose();
            }

            // 2. SettingsTab: 8 Cards, responsive 2-col vs 1-col, search filter + EmptyState, regex outline
            assertEquals(8, ui.settings.sectionCount());
            assertEquals(8, ui.settings.visibleSectionCount());
            ui.settings.panel.setSize(1100, 720);
            ui.settings.relayoutSections();
            assertTrue(ui.settings.isTwoColumnLayout());
            ui.settings.panel.setSize(760, 720);
            ui.settings.relayoutSections();
            assertFalse(ui.settings.isTwoColumnLayout());

            ui.settings.searchField().setText("429");
            assertEquals(1, ui.settings.visibleSectionCount());
            ui.settings.searchField().setText("no_match_xyz_999");
            assertEquals(0, ui.settings.visibleSectionCount());
            assertFalse(collect(ui.settings.panel, themythofcrawl.ui.kit.EmptyState.class).isEmpty());
            ui.settings.searchField().setText("");
            assertEquals(8, ui.settings.visibleSectionCount());

            ui.settings.regexCheckBox().setSelected(true);
            ui.settings.regexField().setText("[unclosed");
            assertEquals("error", ui.settings.regexField().getClientProperty("JComponent.outline"));
            ui.settings.regexField().setText("^/api/.*");
            assertNull(ui.settings.regexField().getClientProperty("JComponent.outline"));

            // 3. ExcelTab: EmptyState before load -> KPI StatCards + filters + HitModel after load
            assertTrue(ui.excel.isShowingEmptyState());
            assertNotNull(ui.excel.emptyState());
            e.setTargets(themythofcrawl.excel.TargetList.fromRows(
                    List.of(List.of("STT", "PROTOCOL", "PATH"),
                            List.of("1", "GET", "/api/v1/users"),
                            List.of("2", "POST", "/api/v1/orders")),
                    "targets.xlsx", "API"));
            ui.excel.reload();
            assertFalse(ui.excel.isShowingEmptyState());
            assertEquals(4, ui.excel.statCards().length);
            assertEquals("1 / 2", ui.excel.statCards()[0].valueText());
            assertEquals(2, ui.excel.coverageTable().getRowCount());
            ui.excel.selectFilter(1); // Chưa bắt
            assertEquals(1, ui.excel.coverageTable().getRowCount());
            ui.excel.selectFilter(2); // Đã bắt
            assertEquals(1, ui.excel.coverageTable().getRowCount());
            ui.excel.coverageTable().setRowSelectionInterval(0, 0);
            assertTrue(ui.excel.hitTable().getRowCount() >= 0);

            // 4. LogTab: lazy tree + filter pills + search + LOGLINE typed icons
            ui.log.rebuild();
            JTree logTree = ui.log.logTree();
            assertTrue(logTree.getRowCount() >= 2);
            ui.log.setFilter(LogTab.LogKind.ERROR);
            assertEquals(LogTab.LogKind.ERROR, ui.log.activeKind());
            logTree.expandRow(logTree.getRowCount() - 1); // Expand Hệ thống & thao tác lazily
            assertTrue(logTree.getRowCount() >= 2);
            TreeRenderer ltr = (TreeRenderer) logTree.getCellRenderer();
            for (int r = 0; r < logTree.getRowCount(); r++) {
                Node ln = (Node) logTree.getPathForRow(r).getLastPathComponent();
                if (ln.kind == Node.Kind.LOGLINE) {
                    ltr.getTreeCellRendererComponent(logTree, ln, false, false, true, r, false);
                    assertNotNull(ltr.getIcon(), "LOGLINE must carry Java2D action icon");
                }
            }
            ui.log.setFilter(LogTab.LogKind.ALL);
            ui.log.searchField().setText("upstream");
            assertTrue(logTree.getRowCount() >= 1);
            ui.log.searchField().setText("");

            // 5. TreeTab: Breadcrumb + detached window title tracking
            ui.treeTab.tree.rebuild(false);
            ui.treeTab.tree.selectBranchPath("Toppage > phase5");
            assertEquals("Toppage > phase5", ui.treeTab.currentBreadcrumbPath());
            assertEquals("Toppage > phase5", ui.treeTab.headerBreadcrumb().path());
            assertTrue(ui.treeTab.detachedTitle().contains("Toppage > phase5"));
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                ui.treeTab.toggleWindow();
                assertNotNull(ui.treeTab.detachedWindow());
                assertTrue(ui.treeTab.detachedWindow().getTitle().contains("Toppage > phase5"));
                ui.treeTab.tree.selectBranchPath("Toppage > second");
                assertTrue(ui.treeTab.detachedWindow().getTitle().contains("Toppage > second"));
                ui.treeTab.closeWindow();
                assertNull(ui.treeTab.detachedWindow());
            } else {
                ui.treeTab.tree.selectBranchPath("Toppage > second");
                assertTrue(ui.treeTab.detachedTitle().contains("Toppage > second"));
            }

            // 6. AuditTab: card tabs + count badges + inline Card action buttons + SearchFields
            assertEquals("card", ui.audit.tabs().getClientProperty("JTabbedPane.tabType"));
            assertTrue(ui.audit.tabs().getTitleAt(0).contains("("));
            assertNotNull(ui.audit.placeSearch());
            assertNotNull(ui.audit.logSearch());
            ui.audit.reload();
            ui.audit.decideTable().clearSelection();
            for (javax.swing.JButton b : ui.audit.inlineActionButtons()) {
                assertFalse(b.isEnabled(), "inline decision button must be disabled without selection");
            }
            if (ui.audit.decideTable().getRowCount() > 0) {
                ui.audit.decideTable().setRowSelectionInterval(0, 0);
                for (javax.swing.JButton b : ui.audit.inlineActionButtons()) {
                    assertTrue(b.isEnabled(), "inline decision button must be enabled when a pending row is selected");
                }
            }

            // 7. CheckList & DupPopup
            CheckList<String> cl = new CheckList<>(
                    List.of(new CheckList.Item<>("A", "01_Admin"), new CheckList.Item<>("B", "02_Guest")),
                    List.of("A"), () -> { }, "Lọc...");
            assertEquals(themythofcrawl.ui.theme.Theme.ROW_TABLE, cl.listTable().getRowHeight());
            UiPreview.PlainHost.simulateBurpTheme(cl);
            assertInstanceOf(ThemedTree.ExtensionRenderer.class, cl.listTable().getColumnModel().getColumn(0).getCellRenderer());
            assertInstanceOf(ThemedTree.ExtensionRenderer.class, cl.listTable().getColumnModel().getColumn(1).getCellRenderer());
            cl.searchField().setText("Guest");
            assertEquals(1, cl.listTable().getRowCount());

            javax.swing.JPopupMenu dupPop = DupPopup.show(ui, e.groups().get(0), ui.api.table, 10, 10);
            assertEquals(1, dupPop.getComponentCount());
            assertInstanceOf(themythofcrawl.ui.kit.Card.class, dupPop.getComponent(0));

            ui.dispose();
        });
        e.close();
    }

    @Test
    void phase6ShortcutsSpotlightDragDropColumnMemoryScaleAndAnimations(@TempDir java.nio.file.Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        java.util.concurrent.atomic.AtomicInteger repeaterPushes = new java.util.concurrent.atomic.AtomicInteger();
        themythofcrawl.app.Bridge countingBridge = new themythofcrawl.app.Bridge() {
            @Override
            public boolean inScope(String url) {
                return true;
            }

            @Override
            public Sent send(themythofcrawl.model.Service s, byte[] request) {
                return UiSmokeTest.BRIDGE.send(s, request);
            }

            @Override
            public void toRepeater(themythofcrawl.model.Service s, byte[] request, String tabName) {
                repeaterPushes.incrementAndGet();
            }

            @Override
            public void out(String line) {
            }

            @Override
            public void err(String line) {
            }
        };

        Engine e = new Engine(countingBridge, prefs, null);
        try {
            e.open(ws.start("p6", "Project6"));
            themythofcrawl.core.Config cfg = e.config().copy();
            cfg.confirmNewApis = false;
            cfg.captureEnabled = true;
            e.applyConfig(cfg);

            e.roles().setAll(List.of(
                    new themythofcrawl.model.Role("01_Admin", true, List.of(
                            themythofcrawl.model.HeaderRule.of("X-Role", "admin", "ADD"))),
                    new themythofcrawl.model.Role("02_Guest", true, List.of(
                            themythofcrawl.model.HeaderRule.of("Authorization", "[REMOVE]", "REMOVE")))));
            e.saveRoles();

            e.setDestination("Toppage > src");
            e.process(UiSmokeTest.msg("GET", "/api/v1/alpha"), "Proxy", true, e.activeBranch().id());
            e.process(UiSmokeTest.msg("POST", "/api/v1/beta"), "Proxy", true, e.activeBranch().id());
            e.setDestination("Toppage > orders");
            e.process(UiSmokeTest.msg("GET", "/api/v1/orders"), "Proxy", true, e.activeBranch().id());

            List<ApiGroup> gs = e.groups();
            assertEquals(3, gs.size());
            ApiGroup gAlpha = gs.get(0);
            ApiGroup gBeta = gs.get(1);

            SwingUtilities.invokeAndWait(() -> {
                MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "p6");
                try {
                    ui.root().setSize(1280, 820);
                    ui.root().doLayout();
                    ui.tick(true);

                    // 1. Header compact constraint still holds with Spotlight button
                    java.awt.Dimension pref = ui.headerPanel().getPreferredSize();
                    assertTrue(pref.height <= 64, "header height must stay <= 64, was " + pref.height);
                    assertTrue(pref.width <= 1100, "header width must fit 1100px, was " + pref.width);

                    // 2. Tab tooltips + Alt+1..7 tab switching (V_7.0: Cây first, Báo cáo / Cài đặt grouped)
                    assertTrue(ui.tabs.getToolTipTextAt(0).contains("Alt+1"));
                    assertTrue(ui.tabs.getToolTipTextAt(7).contains("Alt+8"));
                    assertTrue(ui.dispatchShortcut("alt 8"));
                    ui.settingsTabs.setSelectedComponent(ui.settings.panel);
                    assertSame(ui.settings.panel, ui.selectedTab());
                    assertSame(ui.settings.searchField(), ui.activeSearchField());
                    ui.settingsTabs.setSelectedComponent(ui.log.panel);
                    assertSame(ui.log.panel, ui.selectedTab());
                    assertSame(ui.log.searchField(), ui.activeSearchField());
                    assertTrue(ui.dispatchShortcut("alt 7"));
                    assertSame(ui.audit.panel, ui.tabs.getSelectedComponent());
                    ui.audit.tabs().setSelectedIndex(1);
                    assertSame(ui.audit.placeSearch(), ui.activeSearchField());
                    ui.audit.tabs().setSelectedIndex(3);
                    assertSame(ui.audit.logSearch(), ui.activeSearchField());
                    assertTrue(ui.dispatchShortcut("alt 2"));
                    assertSame(ui.api.panel, ui.tabs.getSelectedComponent());
                    assertSame(ui.api.searchField(), ui.activeSearchField());

                    // 3. ApiTab inline search filter + Ctrl+F
                    assertTrue(ui.dispatchShortcut("ctrl F"));
                    ui.api.searchField().setText("#" + gBeta.gid());
                    assertEquals(1, ui.api.groupModel.getRowCount());
                    assertSame(gBeta, ui.api.groupModel.at(0));
                    ui.api.searchField().setText("");
                    assertEquals(3, ui.api.groupModel.getRowCount());

                    // 4. Ctrl+K Spotlight search (API #gid, Branch, Role, API gốc)
                    assertTrue(ui.dispatchShortcut("ctrl K"));
                    assertTrue(ui.spotlight.isVisible());
                    ui.toastPane.doLayout();
                    java.awt.image.BufferedImage spotImg = new java.awt.image.BufferedImage(760, 440,
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D sg = spotImg.createGraphics();
                    try {
                        ui.spotlight.paint(sg);
                    } finally {
                        sg.dispose();
                    }

                    ui.spotlight.searchField().setText("#" + gBeta.gid());
                    assertTrue(ui.spotlight.itemCount() >= 1);
                    assertTrue(ui.spotlight.executeSelected());
                    assertFalse(ui.spotlight.isVisible());
                    assertSame(ui.api.panel, ui.tabs.getSelectedComponent());
                    assertSame(gBeta, ui.api.currentGroup());

                    ui.spotlight.open();
                    ui.spotlight.searchField().setText("Toppage > orders");
                    assertTrue(ui.spotlight.itemCount() >= 1);
                    assertTrue(ui.spotlight.executeSelected());
                    assertSame(ui.api.panel, ui.tabs.getSelectedComponent());
                    assertEquals("Toppage > orders", ui.api.tree.selectedBranch());

                    ui.spotlight.open();
                    ui.spotlight.searchField().setText("01_Admin");
                    assertTrue(ui.spotlight.itemCount() >= 1);
                    assertTrue(ui.spotlight.executeSelected());
                    assertSame(ui.roles.panel, ui.tabs.getSelectedComponent());
                    assertEquals(0, ui.roles.roleTable().getSelectedRow());

                    ui.spotlight.open();
                    ui.spotlight.searchField().setText("original");
                    assertTrue(ui.spotlight.executeSelected());
                    assertSame(ui.roles.panel, ui.tabs.getSelectedComponent());
                    assertEquals(-1, ui.roles.roleTable().getSelectedRow());

                    // 5. Ctrl+Shift+C, Ctrl+Enter, Ctrl+R, Delete with confirmation
                    assertTrue(ui.dispatchShortcut("alt 1"));
                    ui.api.select(gAlpha, null);
                    assertTrue(ui.dispatchShortcut("ctrl shift C"));
                    assertTrue(ui.toastPane.isToastVisible());
                    assertTrue(ui.toastPane.lastMessage().contains("Đã copy"));

                    assertTrue(ui.dispatchShortcut("ctrl R"));
                    e.awaitCaptures();
                    assertTrue(repeaterPushes.get() > 0, "Ctrl+R must push to Repeater");

                    assertTrue(ui.dispatchShortcut("ctrl ENTER"));
                    assertTrue(ui.toastPane.lastMessage().contains("Xếp hàng gửi")
                            || e.queue().queued() + e.queue().inFlight() > 0);

                    java.util.concurrent.atomic.AtomicInteger confirmCalls = new java.util.concurrent.atomic.AtomicInteger();
                    ui.setConfirmHook(q -> {
                        confirmCalls.incrementAndGet();
                        return false;
                    });
                    assertTrue(ui.dispatchShortcut("DELETE"));
                    assertEquals(1, confirmCalls.get());
                    assertEquals(3, e.groups().size(), "declining Delete confirmation must keep APIs");

                    // 6. BranchTree Drag & Drop + Undo toast
                    ui.api.setTreeShowsApis(true); // V_7.0: API rows in the tree are opt-in (legacy mode)
                    ui.api.tree.rebuild(false);
                    ui.api.tree.expandFolders(true);
                    ui.api.tree.selectGroup(gAlpha);
                    java.awt.datatransfer.Transferable payload = ui.api.tree.createTransferableForSelection();
                    assertNotNull(payload, "GROUP selection must create DnD Transferable");
                    assertTrue(ui.api.tree.selectBranchPath("Toppage > orders"));
                    javax.swing.TransferHandler.TransferSupport support =
                            new javax.swing.TransferHandler.TransferSupport(ui.api.tree.tree, payload);
                    assertTrue(ui.api.tree.tree.getTransferHandler().canImport(support));
                    assertTrue(ui.api.tree.tree.getTransferHandler().importData(support));
                    assertEquals("Toppage > orders", gAlpha.branch());
                    assertTrue(ui.toastPane.hasAction(), "drag & drop must show Undo toast action");
                    ui.toastPane.triggerAction();
                    assertEquals("Toppage > src", gAlpha.branch(), "Undo toast must revert drag & drop");

                    // Delete confirmed removes selected group
                    ui.api.select(gBeta, null);
                    ui.setConfirmHook(q -> {
                        confirmCalls.incrementAndGet();
                        return true;
                    });
                    assertTrue(ui.dispatchShortcut("DELETE"));
                    assertEquals(2, confirmCalls.get());
                    assertEquals(2, e.groups().size(), "confirming Delete must remove selected API");

                    // 7. ColumnMemory save / restore across panels + popup menu + resetDefaults
                    ColumnMemory cm = ColumnMemory.of(ui.api.table);
                    assertNotNull(cm);
                    int lastModelCol = ui.api.table.getModel().getColumnCount() - 1;
                    int resizedModel = ui.api.table.convertColumnIndexToModel(1);
                    ui.api.table.getColumnModel().getColumn(1).setPreferredWidth(213);
                    ui.api.table.getColumnModel().getColumn(1).setWidth(213);
                    int movedModel = ui.api.table.convertColumnIndexToModel(2);
                    ui.api.table.getColumnModel().moveColumn(2, 1);
                    cm.setColumnVisible(lastModelCol, false);
                    cm.saveNow();

                    String colJson = ui.uiPrefs.get(UiPrefs.KEY_COLUMNS_PREFIX + ApiTab.API_COLUMNS_ID);
                    assertNotNull(colJson);
                    assertTrue(colJson.contains("213"));
                    assertFalse(cm.isColumnVisible(lastModelCol));

                    MainPanel ui2 = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "p6");
                    try {
                        ColumnMemory cm2 = ColumnMemory.of(ui2.api.table);
                        assertNotNull(cm2);
                        assertFalse(cm2.isColumnVisible(lastModelCol), "hidden column must restore across instances");
                        assertEquals(movedModel, ui2.api.table.convertColumnIndexToModel(1),
                                "column order must restore across instances");
                        int viewOfResized = ui2.api.table.convertColumnIndexToView(resizedModel);
                        assertEquals(213, ui2.api.table.getColumnModel().getColumn(viewOfResized).getPreferredWidth(),
                                "column width must restore across instances");

                        javax.swing.JPopupMenu colMenu = cm2.buildColumnPopupMenu();
                        assertTrue(colMenu.getComponentCount() >= lastModelCol);
                        cm2.resetDefaults();
                        assertTrue(cm2.isColumnVisible(lastModelCol));
                        assertNull(ui2.uiPrefs.get(UiPrefs.KEY_COLUMNS_PREFIX + ApiTab.API_COLUMNS_ID));
                    } finally {
                        ui2.dispose();
                    }

                    // 8. Font scale (90% / 100% / 115%) & animations toggle in Settings Section 8
                    assertEquals(8, ui.settings.sectionCount());
                    ui.settings.animationsCheckBox().doClick();
                    assertFalse(UiPrefs.animationsEnabled(ui.uiPrefs));
                    assertFalse(themythofcrawl.ui.kit.ToastPane.animationsEnabled());
                    ui.settings.animationsCheckBox().doClick();
                    assertTrue(UiPrefs.animationsEnabled(ui.uiPrefs));
                    assertTrue(themythofcrawl.ui.kit.ToastPane.animationsEnabled());

                    ui.settings.fontScaleBox().setSelectedItem("90%");
                    float smallSize = themythofcrawl.ui.theme.Theme.current().baseFont.getSize2D();
                    assertEquals(90, UiPrefs.fontScalePercent(ui.uiPrefs));
                    assertEquals(0.90f, themythofcrawl.ui.theme.Theme.fontScaleMultiplier(), 0.01f);

                    ui.settings.fontScaleBox().setSelectedItem("115%");
                    float largeSize = themythofcrawl.ui.theme.Theme.current().baseFont.getSize2D();
                    assertEquals(115, UiPrefs.fontScalePercent(ui.uiPrefs));
                    assertEquals(1.15f, themythofcrawl.ui.theme.Theme.fontScaleMultiplier(), 0.01f);
                    assertTrue(largeSize > smallSize, "115% scale font must exceed 90% scale font");
                } finally {
                    UiPrefs.setAnimationsEnabled(ui.uiPrefs, true);
                    UiPrefs.setFontScalePercent(ui.uiPrefs, 100);
                    ui.dispose();
                }
            });
        } finally {
            e.close();
        }
    }

    @Test
    void historyReportViewNullSafetyAcrossAllModes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            HistoryReportView view = new HistoryReportView(null, null);
            view.setSize(1024, 680);
            view.setData(null, null, null);
            view.doLayout();

            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1024, 680,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            try {
                for (int mode : new int[] {
                    HistoryReportView.MODE_CARDS, HistoryReportView.MODE_MATRIX, HistoryReportView.MODE_TEXT
                }) {
                    view.setViewMode(mode);
                    view.doLayout();
                    view.paint(g);
                }

                // Mixed null branches + partial ApiGroup (null path, null url, no results)
                ApiGroup minimal = new ApiGroup(99, new themythofcrawl.model.Service("localhost", 80, false),
                        "GET", null, null, null, 1, null, null, null, null, 0L);
                view.setData(null, java.util.Collections.singletonList(minimal), null);
                for (int mode : new int[] {
                    HistoryReportView.MODE_CARDS, HistoryReportView.MODE_MATRIX, HistoryReportView.MODE_TEXT
                }) {
                    view.setViewMode(mode);
                    view.doLayout();
                    view.paint(g);
                }
            } finally {
                g.dispose();
            }
        });
    }

    @Test
    void flatLafRuntimeThemeSwitchUpdatesColorsAndRetainsRenderers(@TempDir java.nio.file.Path dir) throws Exception {
        javax.swing.LookAndFeel origLaf = javax.swing.UIManager.getLookAndFeel();
        try {
            Prefs prefs = Prefs.memory();
            Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
            ws.setRoot(dir);
            Engine e = new Engine(UiSmokeTest.BRIDGE, prefs, null);
            e.open(ws.start("fl", "FlatLafTest"));
            e.setDestination("Toppage > Auth");
            e.process(UiSmokeTest.msg("POST", "/api/v1/login"), "Proxy", true, e.activeBranch().id());
            try {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        javax.swing.UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatLightLaf());
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    themythofcrawl.ui.theme.Theme.onThemeChanged();
                    UiHost baseHost = new UiSmokeTest.HeadlessHost();
                    MainPanel ui = new MainPanel(e, ws, new UiHost() {
                        @Override
                        public Viewer requestViewer(boolean editable) {
                            return baseHost.requestViewer(editable);
                        }

                        @Override
                        public Viewer responseViewer() {
                            return baseHost.responseViewer();
                        }

                        @Override
                        public void applyTheme(Component c) {
                            SwingUtilities.updateComponentTreeUI(c);
                        }

                        @Override
                        public String projectName() {
                            return baseHost.projectName();
                        }
                    }, "fl");
                    try {
                        ui.root().setSize(1100, 820);
                        ui.root().doLayout();
                        ui.tick(true);
                        themythofcrawl.ui.theme.Theme light = themythofcrawl.ui.theme.Theme.current();
                        assertFalse(light.dark, "FlatLightLaf must resolve non-dark Theme");
                        assertTrue(ui.headerPanel().getPreferredSize().width <= 1100,
                                "FlatLightLaf header width must fit 1100px");

                        javax.swing.LookAndFeel[] targets = {
                            new com.formdev.flatlaf.FlatDarkLaf(),
                            new com.formdev.flatlaf.FlatIntelliJLaf(),
                            new com.formdev.flatlaf.FlatDarculaLaf(),
                            new com.formdev.flatlaf.FlatLightLaf()
                        };
                        boolean[] expectedDark = {true, false, true, false};
                        java.awt.image.BufferedImage buf = new java.awt.image.BufferedImage(1100, 820,
                                java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        for (int i = 0; i < targets.length; i++) {
                            try {
                                javax.swing.UIManager.setLookAndFeel(targets[i]);
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                            SwingUtilities.updateComponentTreeUI(ui.root());
                            themythofcrawl.ui.theme.Theme.onThemeChanged();
                            ui.root().validate();
                            ui.root().doLayout();
                            themythofcrawl.ui.theme.Theme cur = themythofcrawl.ui.theme.Theme.current();
                            assertEquals(expectedDark[i], cur.dark,
                                    "Dark flag mismatch after switching to " + targets[i].getName());
                            assertInstanceOf(ThemedTree.ExtensionRenderer.class,
                                    ui.api.tree.tree.getCellRenderer(), "Tree renderer must survive FlatLaf switch");
                            assertInstanceOf(ThemedTree.ExtensionRenderer.class,
                                    ui.api.table.getColumnModel().getColumn(0).getCellRenderer(),
                                    "Table column renderer must survive FlatLaf switch");
                            java.awt.Graphics2D g = buf.createGraphics();
                            try {
                                ui.root().paint(g);
                            } finally {
                                g.dispose();
                            }
                        }
                        // Also exercise SearchField directly at non-zero bounds across FlatLight/Dark and Metal->FlatLaf
                        themythofcrawl.ui.kit.SearchField standaloneSearch = new themythofcrawl.ui.kit.SearchField("Search...", 18);
                        standaloneSearch.setSize(260, 28);
                        standaloneSearch.doLayout();
                        assertNotNull(standaloneSearch.getMargin(), "FlatLaf SearchField margin must never be null");
                        java.awt.image.BufferedImage searchImg = new java.awt.image.BufferedImage(260, 28,
                                java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        java.awt.Graphics2D sg = searchImg.createGraphics();
                        try {
                            standaloneSearch.paint(sg);
                            standaloneSearch.setText("query");
                            standaloneSearch.paint(sg);
                            javax.swing.UIManager.setLookAndFeel(new javax.swing.plaf.metal.MetalLookAndFeel());
                            SwingUtilities.updateComponentTreeUI(standaloneSearch);
                            assertTrue(standaloneSearch.getMargin().left >= 22);
                            standaloneSearch.paint(sg);
                            javax.swing.UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
                            SwingUtilities.updateComponentTreeUI(standaloneSearch);
                            assertNotNull(standaloneSearch.getMargin());
                            standaloneSearch.paint(sg);
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        } finally {
                            sg.dispose();
                        }
                    } finally {
                        ui.dispose();
                    }
                });
            } finally {
                e.close();
            }
        } finally {
            if (origLaf != null) {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        javax.swing.UIManager.setLookAndFeel(origLaf);
                    } catch (Exception ignored) {
                        // Restore best effort
                    }
                    themythofcrawl.ui.theme.Theme.onThemeChanged();
                });
            }
        }
    }

    private static <T> List<T> collect(Component root, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Container c) {
            for (Component ch : c.getComponents()) {
                out.addAll(collect(ch, type));
            }
        }
        return out;
    }
}
