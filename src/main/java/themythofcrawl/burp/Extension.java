package themythofcrawl.burp;

import java.awt.Component;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.http.InterceptedRequest;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyRequestHandler;
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction;
import burp.api.montoya.proxy.http.ProxyRequestToBeSentAction;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.ForwardDeliveryGuard;
import themythofcrawl.core.ProxyInterceptQueue;
import themythofcrawl.core.Text;
import themythofcrawl.model.ActionInterceptRule;
import themythofcrawl.model.Branch;
import themythofcrawl.model.Service;
import themythofcrawl.rolereq.RoleRules;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;
import themythofcrawl.ui.MainPanel;

/** Burp entry point (Montoya API). */
public final class Extension implements BurpExtension {
    public static final String VERSION = "7.2-beta";

    private static final long BRIDGE_TTL_MS = 15_000L;
    private static final Method SPOOF_METHOD = resolveStaticMethod(
            RequestToBeSentAction.class, "spoof", HttpResponse.class);
    private static final Method DROP_METHOD = resolveStaticMethod(
            RequestToBeSentAction.class, "drop");

    private record TimedSpoof(String rawResponse, long expiresAtMs) {
        boolean alive(long now) {
            return now <= expiresAtMs;
        }
    }

    /** True when the host Burp Suite Montoya runtime supports {@code RequestToBeSentAction.spoof(HttpResponse)}. */
    public static boolean supportsSpoof() {
        return SPOOF_METHOD != null;
    }

    /** True when the host Burp Suite Montoya runtime supports {@code RequestToBeSentAction.drop()}. */
    public static boolean supportsHttpDrop() {
        return DROP_METHOD != null;
    }

    private static Method resolveStaticMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static RequestToBeSentAction invokeSpoof(HttpResponse response) {
        if (SPOOF_METHOD == null || response == null) {
            return null;
        }
        try {
            Object res = SPOOF_METHOD.invoke(null, response);
            return res instanceof RequestToBeSentAction act ? act : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static RequestToBeSentAction invokeDrop() {
        if (DROP_METHOD == null) {
            return null;
        }
        try {
            Object res = DROP_METHOD.invoke(null);
            return res instanceof RequestToBeSentAction act ? act : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String exactFingerprint(Service svc, byte[] raw) {
        if (svc == null || raw == null) {
            return "";
        }
        return svc.host().toLowerCase(Locale.ROOT) + ":" + svc.port() + ":" + svc.secure()
                + "|" + Arrays.hashCode(raw) + "|" + raw.length;
    }

    static String routeFingerprint(Service svc, byte[] raw) {
        if (svc == null || raw == null) {
            return "";
        }
        String[] rl = Text.requestLine(raw);
        if (rl == null) {
            return "";
        }
        return svc.host().toLowerCase(Locale.ROOT) + ":" + svc.port() + ":" + svc.secure()
                + "|" + rl[0].toUpperCase(Locale.ROOT) + "|" + rl[1];
    }

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName(Text.EXT_NAME);
        Prefs prefs = MontoyaPrefs.of(api.persistence().preferences());
        Prefs project = null;
        try {
            project = MontoyaPrefs.of(api.persistence().extensionData());
        } catch (RuntimeException ignored) {
            // Community edition / temporary project: no project storage
        }
        Engine engine = new Engine(new MontoyaBridge(api), prefs, RoleRules.INSTANCE);
        Workspaces workspaces = new Workspaces(prefs, project, safeFilename(api));
        String projectId = projectId(api);
        String projectName = new MontoyaUiHost(api).projectName();
        try {
            FileStorage st = workspaces.start(projectId, projectName);
            engine.open(st);
            workspaces.remember(projectId, st);
        } catch (RuntimeException e) {
            api.logging().logToError(Text.EXT_NAME + ": không mở được workspace (" + e + "), dữ liệu chỉ nằm trong bộ nhớ.");
        }

        MainPanel[] panel = new MainPanel[1];
        String[] loadLine = new String[1];
        Runnable build = () -> {
            panel[0] = new MainPanel(engine, workspaces, new MontoyaUiHost(api), projectId);
            api.userInterface().registerSuiteTab(Text.EXT_NAME, panel[0].root());
            loadLine[0] = themythofcrawl.ui.Diagnostics.loadLogLine();
        };
        try {
            SwingUtilities.invokeAndWait(build);
        } catch (Exception e) {
            api.logging().logToError(Text.EXT_NAME + ": lỗi dựng giao diện: " + e);
        }

        // Bridges ProxyRequestHandler -> HttpHandler when returning a spoofed 200 OK (Montoya 2026.7+)
        ConcurrentHashMap<String, TimedSpoof> pendingSpoofsExact = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, TimedSpoof> pendingSpoofsRoute = new ConcurrentHashMap<>();
        // Guarantees FORWARD always reaches the Server & Logger++ even if the browser closed/timed out its Proxy socket
        ForwardDeliveryGuard forwardGuard = new ForwardDeliveryGuard();

        // Primary Proxy Request Handler — works across ALL Burp Suite Montoya versions (2023 -> 2026.7+)
        api.proxy().registerRequestHandler(new ProxyRequestHandler() {
            @Override
            public ProxyRequestReceivedAction handleRequestReceived(InterceptedRequest req) {
                try {
                    var svc = req.httpService();
                    Service service = new Service(svc.host(), svc.port(), svc.secure());
                    byte[] raw = req.toByteArray().getBytes();
                    String url = req.url();
                    Engine.ProxyInterceptOutcome outcome = engine.evaluateProxyIntercept(service, raw, url);
                    if (outcome == null || outcome.effect() == ActionInterceptRule.Effect.ALLOW) {
                        return ProxyRequestReceivedAction.continueWith(req);
                    }

                    // Mode #5: Live Interactive Intercept Wait — hold the Proxy worker thread until user decides
                    if (outcome.shouldWait()) {
                        String[] rl = Text.requestLine(raw);
                        String method = rl != null ? rl[0] : req.method();
                        String path = rl != null ? rl[1] : req.path();
                        String branchName = engine.activeBranch().name();
                        engine.log("CHẶN CHỜ DUYỆT [" + method + " " + path + "] (" + outcome.reason()
                                + ") — mở Hàng chờ Chặn để bấm Gửi lên Server hoặc Hủy.", null);
                        ProxyInterceptQueue.Resolution res = engine.proxyQueue().enqueueAndAwait(
                                service, method, path, url, branchName, outcome.reason(),
                                outcome.spoofResponse(), raw, ProxyInterceptQueue.DEFAULT_TIMEOUT_MS);
                        boolean wasEdited = res.editedRequest() != null
                                && res.editedRequest().length > 0
                                && !Arrays.equals(res.editedRequest(), raw);
                        byte[] finalBytes = wasEdited ? res.editedRequest() : raw;

                        switch (res.kind()) {
                            case FORWARD -> {
                                // Preserve the original InterceptedRequest when unedited (keeps HTTP/2 framing intact)
                                HttpRequest finalReq = wasEdited
                                        ? HttpRequest.httpRequest(req.httpService(), ByteArray.byteArray(finalBytes))
                                        : req;
                                engine.recordGateAuditOutcome(outcome.auditSeq(), "Đã bấm Gửi lên Server");
                                engine.log("ĐÃ GỬI LÊN SERVER [" + method + " " + path + "] từ Hàng chờ Chặn.", null);
                                int msgId = req.messageId();
                                byte[] fallbackBytes = Arrays.copyOf(finalBytes, finalBytes.length);
                                forwardGuard.arm(msgId, () -> {
                                    try {
                                        HttpRequest directReq = MontoyaBridge.request(service, fallbackBytes);
                                        HttpRequestResponse rr = api.http().sendRequest(directReq);
                                        byte[] respBytes = (rr != null && rr.hasResponse())
                                                ? rr.response().toByteArray().getBytes() : null;
                                        int st = (rr != null && rr.hasResponse()) ? rr.response().statusCode() : 0;
                                        engine.recordGateAuditOutcome(outcome.auditSeq(),
                                                "Gửi trực tiếp (Proxy socket đóng) -> HTTP " + st);
                                        engine.log("Proxy kết nối trình duyệt đã đóng -> Tự động đẩy thẳng lên Server ["
                                                + method + " " + path + "] -> HTTP " + st + ".", null);
                                        engine.onProxyMessage(new Engine.Message(service, fallbackBytes, respBytes, url));
                                    } catch (Throwable ex) {
                                        api.logging().logToError(Text.EXT_NAME + " forward-fallback: " + ex);
                                        engine.log("Lỗi đẩy trực tiếp [" + method + " " + path + "]: " + ex.getMessage(), null);
                                    }
                                });
                                return ProxyRequestReceivedAction.doNotIntercept(finalReq);
                            }
                            case HOLD_AND_SPOOF -> {
                                engine.recordGateAuditOutcome(outcome.auditSeq(), "Giữ lại + trả 200 ảo");
                                engine.onProxyHeldRequest(service, finalBytes, url, outcome.matchedRule(),
                                        "Proxy-Wait[" + outcome.reason() + "]");
                                if (supportsSpoof() && res.spoofResponse() != null) {
                                    recordPendingSpoof(pendingSpoofsExact, pendingSpoofsRoute,
                                            service, raw, res.spoofResponse());
                                    return ProxyRequestReceivedAction.doNotIntercept(req);
                                }
                                return ProxyRequestReceivedAction.drop();
                            }
                            case DROP_AND_SAVE -> {
                                engine.recordGateAuditOutcome(outcome.auditSeq(), "Đã Drop & Lưu Extension");
                                engine.onProxyHeldRequest(service, finalBytes, url, outcome.matchedRule(),
                                        "Proxy-Wait[" + outcome.reason() + "]");
                                return ProxyRequestReceivedAction.drop();
                            }
                            case DROP -> {
                                engine.recordGateAuditOutcome(outcome.auditSeq(), "Đã Hủy (Drop)");
                                engine.log("ĐÃ HỦY (DROP) [" + method + " " + path + "] từ Hàng chờ Chặn.", null);
                                return ProxyRequestReceivedAction.drop();
                            }
                        }
                    }

                    // Proactive BLOCK_DROP or Strategy #1 HOLD_AT_PROXY / BLOCK_SPOOF
                    if (outcome.shouldBlock()) {
                        engine.onProxyHeldRequest(service, raw, url, outcome.matchedRule());
                        if (outcome.effect() == ActionInterceptRule.Effect.BLOCK_DROP) {
                            return ProxyRequestReceivedAction.drop();
                        }
                        if (supportsSpoof() && outcome.spoofResponse() != null) {
                            recordPendingSpoof(pendingSpoofsExact, pendingSpoofsRoute,
                                    service, raw, outcome.spoofResponse());
                            return ProxyRequestReceivedAction.doNotIntercept(req);
                        }
                        // Fallback for older Burp versions without RequestToBeSentAction.spoof():
                        // dropping at Proxy guarantees Server NEVER executes the request while keeping it in Extension!
                        return ProxyRequestReceivedAction.drop();
                    }
                } catch (Throwable e) {
                    api.logging().logToError(Text.EXT_NAME + " proxy-request-handler: " + e);
                }
                return ProxyRequestReceivedAction.continueWith(req);
            }

            @Override
            public ProxyRequestToBeSentAction handleRequestToBeSent(InterceptedRequest req) {
                try {
                    forwardGuard.markProxyDispatched(req.messageId());
                } catch (Throwable ignored) {
                    // never break Proxy request sending
                }
                return ProxyRequestToBeSentAction.continueWith(req);
            }
        });

        // Secondary HttpHandler: completes spoofed 200 OK responses scheduled by ProxyRequestHandler
        api.http().registerHttpHandler(new HttpHandler() {
            @Override
            public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent req) {
                try {
                    if (req.toolSource() != null && req.toolSource().isFromTool(ToolType.PROXY)
                            && (!pendingSpoofsExact.isEmpty() || !pendingSpoofsRoute.isEmpty())) {
                        var svc = req.httpService();
                        Service service = new Service(svc.host(), svc.port(), svc.secure());
                        byte[] raw = req.toByteArray().getBytes();
                        String spoof = consumePendingSpoof(pendingSpoofsExact, pendingSpoofsRoute, service, raw);
                        if (spoof != null) {
                            RequestToBeSentAction spoofed = invokeSpoof(HttpResponse.httpResponse(spoof));
                            if (spoofed != null) {
                                return spoofed;
                            }
                            RequestToBeSentAction dropped = invokeDrop();
                            if (dropped != null) {
                                return dropped;
                            }
                        }
                    }
                } catch (Throwable e) {
                    api.logging().logToError(Text.EXT_NAME + " proxy-spoof: " + e);
                }
                return RequestToBeSentAction.continueWith(req);
            }

            @Override
            public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived resp) {
                return ResponseReceivedAction.continueWith(resp);
            }
        });

        api.proxy().registerResponseHandler(new ProxyResponseHandler() {
            @Override
            public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse r) {
                return ProxyResponseReceivedAction.continueWith(r);
            }

            @Override
            public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse r) {
                try {
                    if ("true".equalsIgnoreCase(r.headerValue("X-JCrawl-Held"))) {
                        return ProxyResponseToBeSentAction.continueWith(r);
                    }
                    var req = r.initiatingRequest();
                    var svc = req.httpService();
                    engine.onProxyMessage(new Engine.Message(new Service(svc.host(), svc.port(), svc.secure()),
                            req.toByteArray().getBytes(), r.toByteArray().getBytes(), req.url()));
                } catch (RuntimeException e) {
                    api.logging().logToError(Text.EXT_NAME + " capture: " + e);
                }
                return ProxyResponseToBeSentAction.continueWith(r);
            }
        });

        api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                List<HttpRequestResponse> sel = new ArrayList<>(event.selectedRequestResponses());
                event.messageEditorRequestResponse().ifPresent(m -> sel.add(m.requestResponse()));
                List<Engine.Message> ms = new ArrayList<>();
                for (HttpRequestResponse rr : sel) {
                    if (rr.request() == null) {
                        continue;
                    }
                    var svc = rr.httpService();
                    ms.add(new Engine.Message(new Service(svc.host(), svc.port(), svc.secure()),
                            rr.request().toByteArray().getBytes(),
                            rr.hasResponse() ? rr.response().toByteArray().getBytes() : null, rr.request().url()));
                }
                if (ms.isEmpty()) {
                    return List.of();
                }
                Branch cur = engine.activeBranch();
                List<Component> items = new ArrayList<>();
                JMenuItem a = new JMenuItem(Text.EXT_NAME + ": Clone to '" + cur.name() + "' (" + ms.size() + " message(s))");
                a.addActionListener(e -> engine.submitManual(ms, false, cur.id()));
                JMenuItem b = new JMenuItem(Text.EXT_NAME + ": Clone to NEW Group Path... (" + ms.size() + " message(s))");
                b.addActionListener(e -> {
                    Object name = JOptionPane.showInputDialog(panel[0] == null ? null : panel[0].root(),
                            "Nhập đường dẫn nhánh (VD: Toppage > menu > button \"Search\"):", cur.name());
                    if (name != null && !name.toString().isBlank()) {
                        engine.submitManual(ms, false, engine.startNewBranch(name.toString()).id());
                    }
                });
                JMenuItem c = new JMenuItem(Text.EXT_NAME + ": Clone to '" + cur.name() + "' (apply capture filters + dedup)");
                c.addActionListener(e -> engine.submitManual(ms, true, cur.id()));
                items.add(a);
                items.add(b);
                items.add(c);
                return items;
            }
        });

        api.extension().registerUnloadingHandler(() -> {
            forwardGuard.close();
            engine.proxyQueue().releaseAll(ProxyInterceptQueue.ResolutionKind.DROP);
            if (panel[0] != null) {
                panel[0].dispose();
            }
            engine.close();
        });
        engine.log(loadLine[0] != null ? loadLine[0] : themythofcrawl.ui.Diagnostics.loadLogLine(), null);
    }

    private static void recordPendingSpoof(
            ConcurrentHashMap<String, TimedSpoof> exactMap,
            ConcurrentHashMap<String, TimedSpoof> routeMap,
            Service service,
            byte[] raw,
            String spoofResponse) {
        long now = System.currentTimeMillis();
        if (exactMap.size() > 64) {
            exactMap.entrySet().removeIf(e -> !e.getValue().alive(now));
            routeMap.entrySet().removeIf(e -> !e.getValue().alive(now));
        }
        TimedSpoof entry = new TimedSpoof(spoofResponse, now + BRIDGE_TTL_MS);
        String exact = exactFingerprint(service, raw);
        if (!exact.isEmpty()) {
            exactMap.put(exact, entry);
        }
        String route = routeFingerprint(service, raw);
        if (!route.isEmpty()) {
            routeMap.put(route, entry);
        }
    }

    private static String consumePendingSpoof(
            ConcurrentHashMap<String, TimedSpoof> exactMap,
            ConcurrentHashMap<String, TimedSpoof> routeMap,
            Service service,
            byte[] raw) {
        long now = System.currentTimeMillis();
        String exact = exactFingerprint(service, raw);
        String route = routeFingerprint(service, raw);
        TimedSpoof hit = !exact.isEmpty() ? exactMap.remove(exact) : null;
        if (hit != null) {
            if (!route.isEmpty()) {
                routeMap.remove(route);
            }
            return hit.alive(now) ? hit.rawResponse() : null;
        }
        TimedSpoof routeHit = !route.isEmpty() ? routeMap.remove(route) : null;
        if (routeHit != null && routeHit.alive(now)) {
            return routeHit.rawResponse();
        }
        return null;
    }

    private static String safeFilename(MontoyaApi api) {
        try {
            return api.extension().filename();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String projectId(MontoyaApi api) {
        try {
            return api.project().id();
        } catch (RuntimeException e) {
            return "default";
        }
    }
}
