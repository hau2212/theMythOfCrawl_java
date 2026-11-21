package themythofcrawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import themythofcrawl.app.Bridge;
import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Config;
import themythofcrawl.excel.TargetList;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ResultState;
import themythofcrawl.model.Role;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.model.Service;
import themythofcrawl.place.PlacementBook;

class EngineTest {
    static final Service SVC = new Service("api.example.test", 443, true);

    static final class FakeBridge implements Bridge {
        final List<String> repeater = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger sent = new AtomicInteger();
        volatile int status = 200;
        volatile int fail429 = 0;

        @Override
        public boolean inScope(String url) {
            return true;
        }

        @Override
        public Sent send(Service s, byte[] request) {
            sent.incrementAndGet();
            if (fail429 > 0) {
                fail429--;
                return new Sent("HTTP/1.1 429 Too Many\r\nRetry-After: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 1, null);
            }
            return new Sent(("HTTP/1.1 " + status + " OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":1}")
                    .getBytes(StandardCharsets.ISO_8859_1), 3, null);
        }

        @Override
        public void toRepeater(Service s, byte[] request, String tabName) {
            repeater.add(tabName);
        }

        @Override
        public void out(String line) {
        }

        @Override
        public void err(String line) {
        }
    }

    FakeBridge bridge;
    Prefs prefs;
    Engine e;

    @BeforeEach
    void setUp() {
        bridge = new FakeBridge();
        prefs = Prefs.memory();
        e = new Engine(bridge, prefs, null);
        Config c = e.config().copy();
        c.captureEnabled = true;
        c.sendProfile = Config.SendProfile.CUSTOM;
        c.sendDelayMs = 0;
        c.sendJitterPct = 0;
        e.applyConfig(c);
    }

    @AfterEach
    void tearDown() {
        e.close();
    }

    static Engine.Message msg(String method, String path) {
        byte[] req = (method + " " + path + " HTTP/1.1\r\nHost: api.example.test\r\nAuthorization: Bearer ORIG\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] resp = "HTTP/1.1 200 OK\r\n\r\n{}".getBytes(StandardCharsets.ISO_8859_1);
        return new Engine.Message(SVC, req, resp, "https://api.example.test" + path);
    }

    ApiGroup capture(String method, String path) {
        return e.process(msg(method, path), "Proxy", true, e.activeBranch().id());
    }

    void go(String path) {
        e.setDestination(path);
    }

    List<PlacementBook.Action> actions() {
        return e.book().log().stream().map(PlacementBook.Event::action).toList();
    }

    void config(java.util.function.Consumer<Config> f) {
        Config c = e.config().copy();
        f.accept(c);
        e.applyConfig(c);
    }

    @Test
    void captureClonesRolesFiltersAndDedups() {
        ApiGroup g = capture("POST", "/api/v1/transfer?x=1");
        assertNotNull(g);
        assertEquals(10, g.results().size());
        assertEquals("01_Admin", g.results().get(0).roleName());
        assertEquals("[G01:POST /api/v1/transfer] 01_Admin", g.results().get(0).tabName());
        assertEquals(200, g.origStatus());
        assertNull(capture("POST", "/api/v1/transfer?x=2"));          // dedup
        assertNull(capture("GET", "/static/app.js"));                 // static
        assertNull(capture("OPTIONS", "/api/v1/x"));                  // OPTIONS
        assertNull(capture("GET", "/home"));                          // not an API path (regex)
        assertEquals(4, e.skippedCount());
        assertTrue(g.pending());                                      // waits for confirmation by default
        assertEquals(1, e.groups().size());
        assertEquals(Role.ORIGINAL_LABEL, g.origResult().roleName());
    }

    @Test
    void allRolesOffStillCapturesAndSendsTheOriginal() throws Exception {
        e.roles().setAllEnabled(false);
        ApiGroup g = capture("POST", "/api/v1/a");
        assertTrue(g.results().isEmpty());
        assertEquals("API gốc (200)", themythofcrawl.core.Audit.summary(g));
        assertEquals(List.of(g.origResult()), e.defaultTargets(g, false));
        e.replay(g, e.defaultTargets(g, false));
        waitSends();
        assertEquals(1, bridge.sent.get());
        assertEquals(ResultState.DONE, g.origResult().state());
    }

    void waitSends() throws InterruptedException {
        for (int i = 0; i < 200 && e.activeSends() > 0; i++) {
            Thread.sleep(20);
        }
    }

    @Test
    void sendingRetries429AndColoursResults() throws Exception {
        ApiGroup g = capture("GET", "/api/v1/items");
        bridge.fail429 = 1;
        e.replay(g, List.of(g.results().get(0)));
        waitSends();
        RoleResult r = g.results().get(0);
        assertEquals(ResultState.DONE, r.state());
        assertEquals(200, r.status());
        assertEquals(2, r.sentCount());
        assertEquals(AuditLevel.OK, r.auditLevel());
        assertNotNull(e.responseOf(g, r));
    }

    @Test
    void pushToRepeaterCompactAndFull() throws Exception {
        ApiGroup g = capture("POST", "/api/v1/transfer");
        assertEquals("G01 POST /api/v1/transfer", e.toRepeater(g, List.of(g.origResult(), g.results().get(0)), true));
        e.toRepeater(g, List.of(g.results().get(9)), false);
        e.awaitCaptures();
        assertEquals(List.of("G01•00_GOC", "G01•01_Admin", "[G01:POST /api/v1/transfer] 10_Guest"), bridge.repeater);
        List<Map.Entry<String, Integer>> names = Engine.roleNamesIn(List.of(g));
        assertEquals("01_Admin", names.get(0).getKey());
        assertEquals(2, Engine.selectTargets(g, true, List.of("01_Admin"), false).size());
    }

    @Test
    void pendingConfirmMoveRevert() {
        go("/ > a > b");
        ApiGroup g = capture("POST", "/api/x");
        assertTrue(g.pending());
        assertEquals("/ > a > b", g.branch());
        assertNull(e.book().get(g.bookKey()).place);
        assertEquals(1, e.confirm(List.of(g), ""));
        assertEquals("/ > a > b", e.book().get(g.bookKey()).place);
        assertEquals(List.of(PlacementBook.Action.PENDING, PlacementBook.Action.CONFIRM), actions());
        assertEquals(1, e.moveToPath(List.of(g), "/ > x", ""));
        e.moveToPath(List.of(g), "/ > z", "");
        assertEquals(1, e.revert(List.of(g)));
        assertEquals("/ > x", g.branch());
        assertEquals(1, e.revert(List.of(g)));
        assertEquals("/ > a > b", g.branch());
        assertEquals(0, e.revert(List.of(g)));
        PlacementBook.Event move = e.book().log().stream().filter(ev -> ev.action() == PlacementBook.Action.MOVE).findFirst().get();
        assertTrue(e.revertEvent(move).contains("/ > a > b"));
    }

    @Test
    void neverConfirmedApiReturnsAfterClearAllAndPathsDeleted() {
        go("/ > a > b");
        capture("POST", "/api/send");
        e.clearAll();
        assertNull(e.branchByName("/ > a > b"));
        go("/ > s > f");
        ApiGroup g = capture("POST", "/api/send");
        assertEquals("/ > a > b", g.branch());
        assertTrue(g.placeHint().oldMissing());
        assertEquals("/ > s > f", g.placeHint().found());
        assertEquals(PlacementBook.Action.RETURN, actions().get(actions().size() - 1));
        e.moveToPath(List.of(g), g.placeHint().found(), "chọn nơi vừa gặp");
        assertEquals("/ > s > f", e.book().get(g.bookKey()).place);
    }

    @Test
    void duplicatesAtNewPlacesAreNumberedHiddenShown() {
        go("/ > a");
        ApiGroup first = capture("POST", "/api/send");
        e.confirm(List.of(first), "");
        assertNull(capture("POST", "/api/send"));                     // same place: skip
        go("/ > b");
        ApiGroup dup = capture("POST", "/api/send");
        assertNotNull(dup);
        assertEquals(2, dup.dupNo());
        assertEquals(2, first.dupTotal());
        assertTrue(dup.dupPending());
        assertNull(capture("POST", "/api/send"));                     // /b already has copy #2
        assertEquals(1, e.hide(List.of(dup), ""));
        assertFalse(e.groups().contains(dup));
        assertEquals(List.of(dup), e.hiddenGroups());
        assertEquals(1, e.show(List.of(dup)));
        assertTrue(e.groups().contains(dup));
        go("/ > c");
        ApiGroup third = capture("POST", "/api/send");
        assertEquals(3, third.dupNo());
        assertEquals(1, e.confirm(List.of(third), ""));
        assertFalse(third.pending() || third.dupPending());
        e.remove(List.of(first), "");
        assertEquals(2, dup.dupTotal());
        go("/ > b");
        assertNull(capture("POST", "/api/send"));                     // dedup memory kept by the other copies
        config(c -> c.dupNewPlace = false);
        go("/ > d");
        assertNull(capture("POST", "/api/send"));
    }

    @Test
    void roleScopesKeepResults() {
        e.setDestination("/ > contact > button");
        ApiGroup g1 = capture("GET", "/api/a");
        e.setDestination("/ > shop");
        ApiGroup g3 = capture("GET", "/api/c");
        RoleResult admin = g1.results().get(0);
        admin.setState(ResultState.DONE);
        assertEquals(1, e.applyRoleScope(List.of("02_Manager", "10_Guest"), false, RoleScopes.Scope.BRANCH, List.of("/ > contact"), true));
        assertEquals(8, g1.results().size());
        assertTrue(g1.results().get(0) == admin);
        assertEquals(10, g3.results().size());
        e.applyRoleScope(List.of("10_Guest"), true, RoleScopes.Scope.BRANCH, List.of("/ > contact > button"), true);
        assertTrue(g1.results().stream().anyMatch(r -> r.roleKey().equals("10_Guest")));
        assertEquals(1, e.removeBranchRule("/ > contact", true));
        e.applyRoleScope(List.of("01_Admin"), false, RoleScopes.Scope.API, List.of(g3), true);
        assertFalse(g3.results().stream().anyMatch(r -> r.roleKey().equals("01_Admin")));
        e.applyRoleScope(e.roles().all().stream().map(Role::name).toList(), false, RoleScopes.Scope.GLOBAL, List.of(), true);
        assertEquals(0, e.roles().enabledCount());
        // the sub-branch rule 'Guest on' still wins over the global flag
        assertEquals(List.of("10_Guest"), g1.results().stream().map(RoleResult::roleKey).toList());
        assertTrue(g3.results().isEmpty());
    }

    @Test
    void excelHitsCopyLabelsAndCounter() {
        TargetList tl = TargetList.fromRows(CoreTest.rows(new Object[] {"STT", "METHOD", "PATH", "POSITION"},
                new Object[] {200L, "POST", "/api/v1/transfer", "/ > contact > button \"send\""}), "f.xlsx", "S");
        e.setTargets(tl);
        ApiGroup g = capture("POST", "/api/v1/transfer");
        capture("POST", "/api/v1/transfer");
        assertEquals("/ > contact > button \"send\"", g.branch());
        assertEquals(tl.entries().get(0), g.target());
        assertEquals("2 lần (1 mới · 1 gặp lại)", e.hits().cell(tl.entries().get(0)));
        assertEquals("200. / > contact > button \"send\"", e.copyLabel(g, false));
        ApiGroup other = capture("GET", "/api/v1/other");
        assertEquals(other.branch(), e.copyLabel(other, true));
        config(c -> {
            c.copySttFallback = Config.CopyFallback.COUNTER;
            c.copyCounter = 5;
        });
        assertEquals("5. " + other.branch(), e.copy(List.of(other)));
        assertEquals("5. " + other.branch(), e.copy(List.of(other)));
        assertEquals(6, e.config().copyCounter);
        assertEquals(6, Config.fromMap(themythofcrawl.core.Json.parseObject(prefs.get(Engine.PREF_CONFIG))).copyCounter);
    }

    @Test
    void workspaceSurvivesReopen() throws Exception {
        MemoryStorage st = new MemoryStorage();
        e.open(st);
        go("/ > a > b");
        ApiGroup g = capture("POST", "/api/v1/transfer");
        e.confirm(List.of(g), "");
        e.replay(g, List.of(g.results().get(0)));
        waitSends();
        e.flush();
        e.open(new MemoryStorage());
        assertTrue(e.groups().isEmpty());
        e.open(st);
        assertEquals(1, e.groups().size());
        ApiGroup back = e.groups().get(0);
        assertEquals("/ > a > b", back.branch());
        assertEquals(10, back.results().size());
        assertEquals(200, back.results().get(0).status());
        assertNotNull(e.responseOf(back, back.results().get(0)));
        assertEquals("/ > a > b", e.activeBranch().name());
        assertEquals(back.gid(), e.book().get(back.bookKey()).gid);
        assertNull(capture("POST", "/api/v1/transfer"));               // dedup memory restored
        assertEquals(2, capture("GET", "/api/v1/next").gid());
    }

    @Test
    void allFourActionProtectionMethodsAndManualIdReplacement() throws Exception {
        // 1. Method 1: HOLD_AT_PROXY
        config(c -> c.holdActionAtProxy = true);
        byte[] heldReq = "DELETE /api/v1/cart/77 HTTP/1.1\r\nHost: api.example.test\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] getReq = "GET /api/v1/orders HTTP/1.1\r\nHost: api.example.test\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(e.shouldHoldAtProxy(SVC, heldReq, "https://api.example.test/api/v1/cart/77"));
        assertFalse(e.shouldHoldAtProxy(SVC, getReq, "https://api.example.test/api/v1/orders"));
        byte[] spoofResp = "HTTP/1.1 200 OK\r\nX-JCrawl-Held: true\r\n\r\n{\"held\":true}"
                .getBytes(StandardCharsets.ISO_8859_1);
        ApiGroup heldGroup = e.process(new Engine.Message(SVC, heldReq, spoofResp, "https://api.example.test/api/v1/cart/77"),
                "Proxy (Chặn thực thi - Giữ ID)", true, e.activeBranch().id());
        assertNotNull(heldGroup);
        assertTrue(heldGroup.heldAtProxy());
        config(c -> c.holdActionAtProxy = false);

        // 2. Harvest live IDs from GET /api/v1/orders (Method 3: LIVE_ID_POOL) & register POST /api/v1/orders (Method 4: PRODUCER)
        byte[] getOrdersReq = "GET /api/v1/orders HTTP/1.1\r\nHost: api.example.test\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] getOrdersResp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"data\":[{\"id\":79},{\"id\":80}]}"
                .getBytes(StandardCharsets.ISO_8859_1);
        e.process(new Engine.Message(SVC, getOrdersReq, getOrdersResp, "https://api.example.test/api/v1/orders"),
                "Proxy", true, e.activeBranch().id());
        List<String> liveIds = e.actionEngine().getAvailableLiveIds("/api/v1/orders/78", "78");
        assertTrue(liveIds.contains("79"));
        assertTrue(liveIds.contains("80"));

        byte[] postOrderReq = "POST /api/v1/orders HTTP/1.1\r\nHost: api.example.test\r\nContent-Type: application/json\r\nContent-Length: 14\r\n\r\n{\"item\":\"book\"}"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] postOrderResp = "HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n\r\n{\"id\":91}"
                .getBytes(StandardCharsets.ISO_8859_1);
        ApiGroup producerGroup = e.process(new Engine.Message(SVC, postOrderReq, postOrderResp, "https://api.example.test/api/v1/orders"),
                "Proxy", true, e.activeBranch().id());
        assertNotNull(producerGroup);
        assertEquals(Integer.valueOf(producerGroup.gid()), e.actionEngine().findProducerGid("/api/v1/orders/78"));

        // Capture a DELETE /api/v1/orders/78 that already executed during crawl (so 78 is marked consumed)
        ApiGroup delGroup = capture("DELETE", "/api/v1/orders/78");
        assertNotNull(delGroup);
        assertTrue(delGroup.hasFirstOkSnapshot());
        assertTrue(e.actionEngine().isConsumed("78"));

        // Method 2 - Option 2A: Inherit 200 OK snapshot without re-sending dead ID
        RoleResult adminRole = delGroup.results().get(0);
        e.inheritInitial2xx(delGroup, List.of(adminRole));
        assertEquals(200, adminRole.status());
        assertEquals(ResultState.DONE, adminRole.state());

        // Method 2 - Option 2C: Force raw send (simulate 400 error on dead ID) + Restore initial 200 OK
        bridge.status = 400;
        e.forceRawReplay(delGroup, List.of(delGroup.origResult()));
        waitSends();
        assertEquals(400, delGroup.origStatus());
        e.restoreInitial2xx(delGroup);
        assertEquals(200, delGroup.origStatus());
        bridge.status = 200;

        // Method 3 (V_8.2): next live ID of the SAME family, IDs created by POST first ("91"), then list IDs
        assertTrue(e.sendWithNextLiveId(delGroup, List.of(delGroup.origResult())));
        waitSends();
        assertEquals("/api/v1/orders/91", delGroup.path());
        assertEquals(200, delGroup.origStatus());

        // Method 3: Right-click manual ID replacement ("91" -> "88") at any time
        e.replaceIdAndSend(delGroup, "91", "88", true, List.of(delGroup.origResult()));
        waitSends();
        assertEquals("/api/v1/orders/88", delGroup.path());
        assertEquals(200, delGroup.origStatus());

        // Method 4: Call Producer POST /api/v1/orders -> extract new ID -> replace & send
        e.sendWithProducer(delGroup, producerGroup.gid(), List.of(delGroup.origResult()));
        waitSends();
        assertEquals(200, delGroup.origStatus());
    }
}
