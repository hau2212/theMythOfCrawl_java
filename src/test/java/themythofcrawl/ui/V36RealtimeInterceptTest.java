package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.burp.Extension;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.ActionEngine.ActionMode;
import themythofcrawl.core.Config;
import themythofcrawl.core.ProxyInterceptQueue.PendingRequest;
import themythofcrawl.core.ProxyInterceptQueue.ResolutionKind;
import themythofcrawl.model.ActionInterceptRule;
import themythofcrawl.model.ActionPolicy;
import themythofcrawl.model.Service;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.kit.ModernToggleSwitch;

/**
 * Verification suite for V_3.6_beta:
 * 1) Immutable version metadata (V_3.6_beta)
 * 2) Real-time TreeTab push sync on ProxyInterceptQueue enqueue/resolve WITHOUT tick() or tab switching,
 *    including automatic expansion of previously collapsed folders.
 * 3) Real-time ApiTab &amp; ActionPolicyLedger push sync across Top Bar and open views.
 * 4) Top Bar 1-click GET / Action-only / All-methods intercept control.
 */
final class V36RealtimeInterceptTest {

    private static final Service SVC = new Service("app.example.com", 443, true);

    private static byte[] rawReq(String method, String path, String body) {
        String b = body == null ? "" : body;
        String raw = method + " " + path + " HTTP/1.1\r\n"
                + "Host: app.example.com\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + b.getBytes(StandardCharsets.ISO_8859_1).length + "\r\n"
                + "\r\n" + b;
        return raw.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] rawResp(int code, String body) {
        String b = body == null ? "" : body;
        String raw = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + b.getBytes(StandardCharsets.ISO_8859_1).length + "\r\n"
                + "\r\n" + b;
        return raw.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static final class StubBridge implements Bridge {
        @Override public boolean inScope(String url) { return true; }
        @Override public Sent send(Service s, byte[] req) { return new Sent(rawResp(200, "{\"ok\":true}"), 9, null); }
        @Override public void toRepeater(Service service, byte[] request, String tabName) { }
        @Override public void out(String line) { }
        @Override public void err(String line) { }
    }

    private static Engine newEngine(Prefs prefs) {
        Engine e = new Engine(new StubBridge(), prefs, null);
        e.open(new MemoryStorage());
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.autoSend = false;
        c.confirmNewApis = false;
        c.ignoreStatic = false;
        e.applyConfig(c);
        return e;
    }

    private static Workspaces newWorkspaces(Prefs prefs, Path dir) {
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        return ws;
    }

    @Test
    void versionMetadataIsV36Beta() throws Exception {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("version.properties"))) {
            props.load(in);
        }
        assertEquals("7", props.getProperty("major"));
        assertEquals("2", props.getProperty("minor"));
        assertEquals("V_7.2_beta", props.getProperty("releaseTag"));
        assertEquals("7.2-beta", props.getProperty("versionName"));
        assertEquals("theMythOf-JCrawl_V_7.2_beta.jar", props.getProperty("jarName"));
        assertEquals("7.2-beta", Extension.VERSION);
        assertEquals("7.2-beta", Diagnostics.VERSION);

        String wf = Files.readString(Path.of(".github/workflows/build.yml"), StandardCharsets.UTF_8);
        assertTrue(wf.contains("IMMUTABLE GUARD"), "CI workflow must preserve immutable release guard");
    }

    @Test
    void realtimeTreeTabAndCollapsedFolderAutoExpandWithoutTabSwitching(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        e.setDestination("Orders > Checkout");
        e.process(UiSmokeTest.msg("GET", "/api/v1/orders/10"), "Proxy", true, e.activeBranch().id());
        Workspaces ws = newWorkspaces(prefs, dir);

        AtomicReference<MainPanel> uiRef = new AtomicReference<>();
        try {
            edt(() -> {
                MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v36-rt-tree");
                uiRef.set(ui);
                JTabbedPane tabs = findFirst(ui.root(), JTabbedPane.class);
                assertNotNull(tabs);
                // Switch to Cây tab once and stop there (NO further tab switches!)
                ui.selectTab(ui.treeTab.panel);
                ui.treeTab.tree.rebuild(false);

                // Collapse the target folder to verify auto-expansion on real-time intercept
                Node checkoutFolder = findFolderByName((DefaultMutableTreeNode) ui.treeTab.tree.model.getRoot(), "Orders > Checkout");
                assertNotNull(checkoutFolder);
                ui.treeTab.tree.tree.collapsePath(new TreePath(checkoutFolder.getPath()));
                assertFalse(ui.treeTab.tree.tree.isExpanded(new TreePath(checkoutFolder.getPath())));
            });

            // Simulate Proxy worker thread intercepting a request (NO tick(), NO tab switch!)
            PendingRequest pr = e.proxyQueue().enqueue(
                    SVC, "POST", "/api/v1/orders/save", "https://app.example.com/api/v1/orders/save",
                    "Orders > Checkout", "Chặn toàn bộ (POST)", null,
                    rawReq("POST", "/api/v1/orders/save", "{\"confirm\":true}"));

            // Flush EDT queue only (no timer tick, no tab switch!)
            edt(() -> {
                MainPanel ui = uiRef.get();
                Node heldNode = findHeldNode((DefaultMutableTreeNode) ui.treeTab.tree.model.getRoot(), pr.id());
                assertNotNull(heldNode, "HELD_PENDING node must appear in TreeTab in real time without switching tabs");
                assertEquals(Node.Kind.HELD_PENDING, heldNode.kind);

                // Parent folder must have auto-expanded so the red node is immediately visible
                Node checkoutFolder = findFolderByName((DefaultMutableTreeNode) ui.treeTab.tree.model.getRoot(), "Orders > Checkout");
                assertNotNull(checkoutFolder);
                assertTrue(ui.treeTab.tree.tree.isExpanded(new TreePath(checkoutFolder.getPath())),
                        "Collapsed target branch must auto-expand when a held request arrives");
                assertTrue(ui.treeTab.headerMetaText().contains("1 chờ duyệt"),
                        "TreeTab headerMeta must update live with pending count, got: " + ui.treeTab.headerMetaText());

                // Select the held node in TreeTab so shownHeld is active
                ui.treeTab.tree.tree.setSelectionPath(new TreePath(heldNode.getPath()));
                assertNotNull(ui.treeTab.shownHeld());
            });

            // Resolve externally (e.g., from ProxyInterceptDialog or another thread) — NO tick(), NO tab switch!
            assertTrue(e.proxyQueue().resolve(pr.id(), ResolutionKind.FORWARD, null));

            edt(() -> {
                MainPanel ui = uiRef.get();
                Node heldAfter = findHeldNode((DefaultMutableTreeNode) ui.treeTab.tree.model.getRoot(), pr.id());
                assertNull(heldAfter, "Resolved HELD_PENDING node must disappear from TreeTab in real time");
                assertNull(ui.treeTab.shownHeld(), "TreeTab shownHeld must clear automatically when resolved externally");
                assertFalse(ui.treeTab.headerMetaText().contains("chờ duyệt"),
                        "TreeTab headerMeta must clear pending count live, got: " + ui.treeTab.headerMetaText());
            });
        } finally {
            edt(() -> {
                if (uiRef.get() != null) {
                    uiRef.get().dispose();
                }
            });
            e.close();
        }
    }

    @Test
    void realtimeApiTabAndPolicyLedgerSyncWithoutTabSwitching(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        e.setDestination("Toppage");
        e.process(UiSmokeTest.msg("GET", "/"), "Proxy", true, e.activeBranch().id());
        Workspaces ws = newWorkspaces(prefs, dir);

        AtomicReference<MainPanel> uiRef = new AtomicReference<>();
        try {
            edt(() -> {
                MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "v36-rt-api");
                uiRef.set(ui);
                JTabbedPane tabs = findFirst(ui.root(), JTabbedPane.class);
                assertNotNull(tabs);
                ui.selectTab(ui.api.panel);
                ui.api.tree.rebuild(false);
            });

            // Enqueue held request while user sits on API tab (no tick, no tab switch!)
            PendingRequest pr = e.proxyQueue().enqueue(
                    SVC, "DELETE", "/api/v1/items/55", "https://app.example.com/api/v1/items/55",
                    "Toppage", "Chặn toàn bộ (DELETE)", null,
                    rawReq("DELETE", "/api/v1/items/55", ""));

            edt(() -> {
                MainPanel ui = uiRef.get();
                Node heldNode = findHeldNode((DefaultMutableTreeNode) ui.api.tree.model.getRoot(), pr.id());
                assertNotNull(heldNode, "ApiTab tree must show HELD_PENDING node in real time without tab switching");
            });

            // Resolve externally
            assertTrue(e.proxyQueue().resolve(pr.id(), ResolutionKind.DROP, null));
            edt(() -> {
                MainPanel ui = uiRef.get();
                assertNull(findHeldNode((DefaultMutableTreeNode) ui.api.tree.model.getRoot(), pr.id()),
                        "ApiTab tree must remove resolved HELD_PENDING node in real time");
            });

            // Direct ActionPolicyLedger mutation must update Top Bar switch in real time (no manual syncHeader!)
            e.actionEngine().policyLedger().upsert(ActionPolicy.quickBranchWait(
                    "Toppage", List.of("POST", "DELETE"), "RealtimeTest"));
            edt(() -> {
                MainPanel ui = uiRef.get();
                List<ModernToggleSwitch> switches = findAll(ui.headerPanel(), ModernToggleSwitch.class);
                assertFalse(switches.isEmpty());
                assertTrue(switches.get(0).isSelected(),
                        "Top Bar ModernToggleSwitch must flip ON in real time when a blocking policy is added to ledger");
            });
        } finally {
            edt(() -> {
                if (uiRef.get() != null) {
                    uiRef.get().dispose();
                }
            });
            e.close();
        }
    }

    @Test
    void topBarQuickMethodToggleIncludesGetAndRevertsToActionOnly() {
        Prefs prefs = Prefs.memory();
        Engine e = newEngine(prefs);
        try {
            e.setDestination("Toppage");
            // Default Mode #5 blocks POST/PUT/PATCH/DELETE only (NOT GET)
            e.applyQuickActionMode(ActionMode.INTERCEPT_WAIT);
            assertNull(e.evaluateProxyIntercept(SVC, rawReq("GET", "/api/v1/orders", ""), "https://app.example.com/api/v1/orders"),
                    "Default Mode #5 should not intercept GET until enabled");
            var postOutcome = e.evaluateProxyIntercept(SVC, rawReq("POST", "/api/v1/orders", "{}"), "https://app.example.com/api/v1/orders");
            assertNotNull(postOutcome);
            assertTrue(postOutcome.shouldWait());
            assertTrue(e.interceptMethodsSummary().contains("không chặn GET"));

            // 1-click enable GET from Top Bar
            e.toggleInterceptGetFromHeader(true);
            assertTrue(e.interceptMethodsSummary().contains("GET"));
            var getOutcome = e.evaluateProxyIntercept(SVC, rawReq("GET", "/api/v1/orders", ""), "https://app.example.com/api/v1/orders");
            assertNotNull(getOutcome, "GET must be intercepted after enabling GET from Top Bar");
            assertTrue(getOutcome.shouldWait());

            // 1-click All methods
            e.setInterceptMethodsFromHeader(ActionInterceptRule.ALL_METHODS, true);
            assertTrue(e.interceptMethodsSummary().contains("Tất cả"));

            // 1-click revert to Action-only (POST, PUT, PATCH, DELETE)
            e.setInterceptMethodsFromHeader(ActionEngine.ACTION_METHODS, true);
            assertNull(e.evaluateProxyIntercept(SVC, rawReq("GET", "/api/v1/orders", ""), "https://app.example.com/api/v1/orders"));
            var delOutcome = e.evaluateProxyIntercept(SVC, rawReq("DELETE", "/api/v1/orders/1", ""), "https://app.example.com/api/v1/orders/1");
            assertNotNull(delOutcome);
            assertTrue(delOutcome.shouldWait());
        } finally {
            e.close();
        }
    }

    private static Node findHeldNode(DefaultMutableTreeNode root, long heldId) {
        if (root instanceof Node n && n.kind == Node.Kind.HELD_PENDING) {
            PendingRequest pr = BranchTree.heldOf(n);
            if (pr != null && pr.id() == heldId) {
                return n;
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            Node hit = findHeldNode((DefaultMutableTreeNode) root.getChildAt(i), heldId);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Node findFolderByName(DefaultMutableTreeNode root, String branchPath) {
        if (root instanceof Node n && n.kind == Node.Kind.FOLDER && branchPath.equals(n.payload)) {
            return n;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            Node hit = findFolderByName((DefaultMutableTreeNode) root.getChildAt(i), branchPath);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static <T extends Component> T findFirst(Container root, Class<T> type) {
        List<T> all = findAll(root, type);
        return all.isEmpty() ? null : all.get(0);
    }

    private static <T extends Component> List<T> findAll(Container root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends Component> void collect(Component c, Class<T> type, List<T> out) {
        if (type.isInstance(c)) {
            out.add(type.cast(c));
        }
        if (c instanceof Container ct) {
            for (Component ch : ct.getComponents()) {
                collect(ch, type, out);
            }
        }
    }

    private static void edt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
            return;
        }
        AtomicReference<Throwable> err = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                err.set(t);
            }
        });
        if (err.get() != null) {
            throw new AssertionError("EDT failure", err.get());
        }
    }
}
