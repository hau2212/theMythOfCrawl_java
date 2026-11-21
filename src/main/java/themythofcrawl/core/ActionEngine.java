package themythofcrawl.core;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import themythofcrawl.model.ActionInterceptRule;

/**
 * Engine xử lý 4 cơ chế bảo vệ & tái tạo trạng thái cho các Action API (POST / PUT / PATCH / DELETE)
 * trong quá trình Crawl và Gửi lại:
 * <ol>
 *   <li><b>HOLD_AT_PROXY</b>: Chặn trình duyệt thực thi tại Proxy (Spoof 200 OK về trình duyệt,
 *       giữ nguyên trạng thái trên Server để khi bấm Gửi trong Extension mới thực thi thật).</li>
 *   <li><b>LOCK_2XX</b>: Khóa bảo vệ kết quả 2xx ban đầu (Snapshot 200 OK) kèm 3 chế độ gửi lại:
 *       (2A) Kế thừa Response 200 OK ban đầu, (2B) Đẩy thật lên Server kèm đổi ID sống / gọi Producer,
 *       (2C) Cưỡng bức gửi lại nguyên bản + nút Khôi phục 200 OK bất kỳ lúc nào.</li>
 *   <li><b>LIVE_ID_POOL</b>: Tự động thu thập Giỏ ID sống từ các API GET/POST và cho phép chọn/đổi
 *       ID sống khác bất kỳ lúc nào (tự động hoặc qua menu Chuột phải).</li>
 *   <li><b>PRODUCER_RECREATE</b>: Tự động gọi lại API Tạo mới (Producer POST) để sinh ID mới trên
 *       Server rồi thay vào API hiện tại trước khi đẩy lên Server, kèm làm mới các trường Unique.</li>
 * </ol>
 */
public final class ActionEngine {

    public enum ActionMode {
        NONE("0. Nguyên bản (Không can thiệp — Tắt xử lý Action)",
                "Cho request đi thẳng nguyên bản lên Server, không chặn Proxy, không tự đổi ID hay kế thừa 200 ảo (200 -> 400/404 hiển thị trung thực)"),
        AUTO_SMART("Tự động thông minh (Kết hợp cả 4)", "Ưu tiên giữ 2xx, tự thay ID sống hoặc gọi Producer khi gặp 4xx"),
        HOLD_AT_PROXY("1. Chặn ở Proxy (Giữ trạng thái Server)", "Trình duyệt nhận 200 ảo; Server giữ nguyên ID cho tới khi bấm Gửi"),
        LOCK_2XX("2. Khóa bảo vệ 200 OK (Snapshot & 3 tùy chọn)", "Lưu bản sao 200 OK gốc; cho phép Kế thừa 200 OK, Đổi ID đẩy thật, hoặc Gửi cưỡng bức"),
        LIVE_ID_POOL("3. Giỏ ID sống (Tự động nạp & Đổi ID)", "Thu thập ID từ API danh sách/chi tiết để thay thế ID đã bị xóa"),
        PRODUCER_RECREATE("4. Gọi API Tạo mới (Producer -> ID mới)", "Gọi POST tạo bản ghi mới lấy ID tươi trước khi gửi DELETE/PUT/POST"),
        INTERCEPT_WAIT("5. Chặn chờ duyệt trực tiếp (Đợi tôi bấm Gửi)", "Chặn đứng request tại Proxy và hiện ngay trong Hàng chờ Chặn để bạn xem/sửa rồi bấm Gửi lên Server");

        public final String label;
        public final String description;

        ActionMode(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String shortTitle() {
            return switch (this) {
                case NONE -> "Nguyên bản (Tắt)";
                case AUTO_SMART -> "Tự động thông minh";
                case HOLD_AT_PROXY -> "Giữ ở Proxy";
                case LOCK_2XX -> "Khóa 200 OK";
                case LIVE_ID_POOL -> "Kho ID Sống";
                case PRODUCER_RECREATE -> "Gọi API Tạo mới";
                case INTERCEPT_WAIT -> "Chặn chờ duyệt";
            };
        }
    }

    public enum Lock2xxStrategy {
        INHERIT_200("Cách 1A: Kế thừa kết quả 200 OK ban đầu (Không gửi lại ID đã xóa lên Server)"),
        REAL_SEND_FRESH_ID("Cách 1B: Đẩy thật lên Server kèm Tự đổi ID sống / Gọi API Tạo mới (Đạt 200 OK mới)"),
        FORCE_RAW_SEND("Cách 2: Cưỡng bức gửi lại nguyên bản lên Server (Chấp nhận rủi ro 4xx, có thể Khôi phục 200 OK)");

        public final String label;

        Lock2xxStrategy(String label) {
            this.label = label;
        }
    }

    public static final Set<String> ACTION_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final Pattern ACTION_PATH_HINT = Pattern.compile(
            "(?i)/(delete|remove|destroy|cancel|revoke|archive|close|approve|reject|update|edit|create|add|submit|confirm|pay|checkout)(/|$|\\?)");

    // Pattern nhận diện ID trên URL path: số nguyên >= 1 chữ số, UUID, hoặc chuỗi hex/mongo ObjectId 16-36 ký tự
    private static final Pattern PATH_SEGMENT_ID = Pattern.compile(
            "^(\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{16,32})$");

    // Pattern nhận diện ID trong JSON response/request: "id": 123, "order_id": "78", "userId": 15, "uuid": "..."
    private static final Pattern JSON_ID_FIELD = Pattern.compile(
            "\"([a-zA-Z0-9_]*(?:id|Id|ID|uuid|Uuid|code|Code))\"\\s*:\\s*(?:\"([0-9a-zA-Z_-]{1,36})\"|(\\d+))");

    // Pattern nhận diện query/form parameter ID: id=123, order_id=78, itemId=99
    private static final Pattern QUERY_ID_PARAM = Pattern.compile(
            "(?:^|[?&])([a-zA-Z0-9_]*(?:id|Id|ID|uuid|code))=([0-9a-zA-Z_-]{1,36})");

    // Pattern nhận diện các trường unique trong JSON cần làm mới khi gửi lại POST tạo mới
    private static final Pattern JSON_UNIQUE_FIELD = Pattern.compile(
            "\"(email|username|user_name|code|slug|phone|nonce|idempotency_key|client_req_id|reference|sku|serial)\"\\s*:\\s*\"([^\"]+)\"");

    // Giỏ ID sống theo tên tài nguyên (ví dụ: "orders" -> ["77", "78", "79"])
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> resourceLiveIds = new ConcurrentHashMap<>();
    // Tập hợp các ID đã bị tiêu thụ (đã gửi DELETE/Action thành công)
    private final Set<String> consumedIds = ConcurrentHashMap.newKeySet();
    // Giỏ ID sống toàn cục theo thứ tự mới nhất
    private final CopyOnWriteArrayList<String> globalLiveIds = new CopyOnWriteArrayList<>();
    // Bản đồ tài nguyên -> GID của API Producer (POST tạo mới tài nguyên đó)
    private final ConcurrentHashMap<String, Integer> resourceProducers = new ConcurrentHashMap<>();
    // Danh sách các mẫu path/endpoint được bật chế độ Chặn tại Proxy (HOLD_AT_PROXY) riêng lẻ
    private final Set<String> proxyHoldEndpoints = ConcurrentHashMap.newKeySet();
    private volatile boolean holdAllActionAtProxy = false;
    private volatile Lock2xxStrategy defaultLock2xxStrategy = Lock2xxStrategy.REAL_SEND_FRESH_ID;
    private final AtomicInteger uniqueSeq = new AtomicInteger(100);

    // Chế độ #5 (INTERCEPT_WAIT): Chặn đứng request tại Proxy và đợi người dùng bấm Gửi / Hủy trong Extension
    private volatile boolean interceptWaitEnabled = false;
    private volatile boolean interceptWaitAllScope = true;
    private final Set<String> interceptWaitMethods = ConcurrentHashMap.newKeySet();
    private final Set<String> interceptWaitBranches = ConcurrentHashMap.newKeySet();
    private final Set<String> interceptWaitEndpoints = ConcurrentHashMap.newKeySet();

    public ActionEngine() {
        interceptWaitMethods.addAll(ACTION_METHODS);
    }

    public static boolean isActionMethod(String method) {
        if (method == null) return false;
        return ACTION_METHODS.contains(method.toUpperCase(Locale.ROOT));
    }

    public static boolean isDestructiveOrMutating(String method, String path) {
        if (method == null) return false;
        String m = method.toUpperCase(Locale.ROOT);
        if ("DELETE".equals(m) || "PUT".equals(m) || "PATCH".equals(m)) return true;
        if ("POST".equals(m)) return true;
        return path != null && ACTION_PATH_HINT.matcher(path).find();
    }

    public boolean isHoldAllActionAtProxy() {
        return holdAllActionAtProxy;
    }

    public void setHoldAllActionAtProxy(boolean holdAll) {
        this.holdAllActionAtProxy = holdAll;
    }

    public boolean isInterceptWaitEnabled() {
        return interceptWaitEnabled;
    }

    public void setInterceptWaitEnabled(boolean enabled) {
        this.interceptWaitEnabled = enabled;
    }

    public boolean isInterceptWaitAllScope() {
        return interceptWaitAllScope;
    }

    public void setInterceptWaitAllScope(boolean allScope) {
        this.interceptWaitAllScope = allScope;
    }

    public Set<String> getInterceptWaitMethods() {
        return new LinkedHashSet<>(interceptWaitMethods);
    }

    public void setInterceptWaitMethods(Collection<String> methods) {
        interceptWaitMethods.clear();
        if (methods != null) {
            for (String m : methods) {
                if (m != null && !m.isBlank()) {
                    interceptWaitMethods.add(m.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
    }

    public void toggleInterceptWaitMethod(String method, boolean on) {
        if (method == null || method.isBlank()) {
            return;
        }
        String m = method.trim().toUpperCase(Locale.ROOT);
        if (on) {
            interceptWaitMethods.add(m);
        } else {
            interceptWaitMethods.remove(m);
        }
    }

    public Set<String> getInterceptWaitBranches() {
        return new LinkedHashSet<>(interceptWaitBranches);
    }

    public void setBranchInterceptWait(String branchPath, boolean on) {
        if (branchPath == null || branchPath.isBlank()) {
            return;
        }
        String norm = Hierarchy.normalize(branchPath);
        if (on) {
            interceptWaitBranches.add(norm);
        } else {
            interceptWaitBranches.remove(norm);
        }
    }

    public boolean isBranchInterceptWait(String branchPath) {
        if (branchPath == null || branchPath.isBlank()) {
            return false;
        }
        String norm = Hierarchy.normalize(branchPath);
        for (String b : interceptWaitBranches) {
            if (b.equalsIgnoreCase(norm)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> getInterceptWaitEndpoints() {
        return new LinkedHashSet<>(interceptWaitEndpoints);
    }

    public void setEndpointInterceptWait(String method, String path, boolean on) {
        String key = endpointHoldKey(method, path);
        if (on) {
            interceptWaitEndpoints.add(key);
        } else {
            interceptWaitEndpoints.remove(key);
        }
    }

    public boolean isEndpointInterceptWait(String method, String path) {
        return interceptWaitEndpoints.contains(endpointHoldKey(method, path));
    }

    /**
     * Checks whether the incoming Proxy request matches the user's Live Intercept Wait configuration
     * (by specific endpoint, active branch/sub-branch, or global scope + selected HTTP methods).
     * Returns a concise Vietnamese reason when matched, or {@code null} when not matched.
     */
    public String matchInterceptWaitReason(String activeBranch, String method, String path) {
        if (!interceptWaitEnabled) {
            return null;
        }
        String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        // 1. Specific API endpoint match (any method explicitly pinned by user)
        if (isEndpointInterceptWait(m, path)) {
            return "Chặn API: " + m + " " + normalizePathTemplate(path);
        }
        boolean methodOk = interceptWaitMethods.isEmpty() || interceptWaitMethods.contains(m);
        // 2. Specific branch / path match (exact or sub-branch)
        if (!interceptWaitBranches.isEmpty() && methodOk && activeBranch != null && !activeBranch.isBlank()) {
            String cur = Hierarchy.normalize(activeBranch);
            for (String b : interceptWaitBranches) {
                if (cur.equalsIgnoreCase(b)
                        || cur.toLowerCase(Locale.ROOT).startsWith((b + " > ").toLowerCase(Locale.ROOT))) {
                    return "Chặn nhánh: " + b + " (" + m + ")";
                }
            }
        }
        // 3. Global scope match for selected HTTP methods
        if (interceptWaitAllScope && methodOk) {
            return "Chặn toàn bộ (" + m + ")";
        }
        return null;
    }

    // V_3.4_beta: Unified Policy Ledger & Live Action Gate Auditor
    private final ActionPolicyLedger policyLedger = new ActionPolicyLedger();
    private final ActionGateAuditLog gateAuditLog = new ActionGateAuditLog();

    public ActionPolicyLedger policyLedger() {
        return policyLedger;
    }

    public ActionGateAuditLog gateAuditLog() {
        return gateAuditLog;
    }

    public void clearAllProxyControls() {
        holdAllActionAtProxy = false;
        proxyHoldEndpoints.clear();
        interceptWaitEnabled = false;
        interceptWaitAllScope = false;
        interceptWaitBranches.clear();
        interceptWaitEndpoints.clear();
        interceptRules.clear();
        policyLedger.resetAllToNone();
    }

    public boolean hasAnyActiveProxyIntercept() {
        return interceptWaitEnabled
                || holdAllActionAtProxy
                || !proxyHoldEndpoints.isEmpty()
                || activeBlockRuleCount() > 0
                || policyLedger.activeBlockingCount() > 0;
    }

    public Lock2xxStrategy getDefaultLock2xxStrategy() {
        return defaultLock2xxStrategy;
    }

    public void setDefaultLock2xxStrategy(Lock2xxStrategy strategy) {
        if (strategy != null) this.defaultLock2xxStrategy = strategy;
    }

    public void setEndpointProxyHold(String method, String path, boolean hold) {
        String key = endpointHoldKey(method, path);
        if (hold) proxyHoldEndpoints.add(key);
        else proxyHoldEndpoints.remove(key);
    }

    public boolean isEndpointProxyHold(String method, String path) {
        return proxyHoldEndpoints.contains(endpointHoldKey(method, path));
    }

    public Set<String> getProxyHoldEndpoints() {
        return new LinkedHashSet<>(proxyHoldEndpoints);
    }

    private static String endpointHoldKey(String method, String path) {
        String m = method == null ? "*" : method.toUpperCase(Locale.ROOT);
        String normPath = normalizePathTemplate(path);
        return m + " " + normPath;
    }

    /**
     * Chuẩn hóa path thành dạng template tài nguyên, ví dụ "/api/v1/orders/78/cancel" -> "/api/v1/orders/{id}/cancel".
     */
    public static String normalizePathTemplate(String path) {
        if (path == null || path.isEmpty()) return "/";
        String clean = Text.stripQuery(path);
        String[] parts = clean.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append('/');
            if (PATH_SEGMENT_ID.matcher(p).matches()) {
                sb.append("{id}");
            } else {
                sb.append(p);
            }
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /**
     * Trích xuất khóa tài nguyên chính từ URL path (ví dụ "/api/v1/orders/78/invoice" -> "orders").
     */
    public static String extractResourceKey(String path) {
        if (path == null || path.isEmpty()) return "default";
        String clean = Text.stripQuery(path);
        String[] parts = clean.split("/");
        String lastNoun = "default";
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) continue;
            String lower = p.toLowerCase(Locale.ROOT);
            if (lower.equals("api") || lower.matches("v\\d+") || lower.equals("rest") || lower.equals("public") || lower.equals("private")) {
                continue;
            }
            if (PATH_SEGMENT_ID.matcher(p).matches()) {
                return lastNoun;
            }
            if (!ACTION_PATH_HINT.matcher("/" + lower).find()) {
                lastNoun = lower;
            }
        }
        return lastNoun;
    }

    private static byte[] bodyBytes(byte[] msg) {
        if (msg == null || msg.length == 0) return new byte[0];
        int off = HttpBytes.bodyOffset(msg);
        if (off >= msg.length) return new byte[0];
        return Arrays.copyOfRange(msg, off, msg.length);
    }

    private static String headerText(byte[] msg) {
        if (msg == null || msg.length == 0) return "";
        int off = HttpBytes.bodyOffset(msg);
        int end = off;
        while (end > 0 && (msg[end - 1] == '\n' || msg[end - 1] == '\r')) {
            end--;
        }
        return new String(msg, 0, end, StandardCharsets.ISO_8859_1);
    }

    // =================================================================== V_8.2 ID pool by API family
    /** Where a live ID came from (shown as "Nguồn" in the ID pool). */
    public record LiveIdSource(String family, String id, String how, String api, int status, long atMs) {
    }

    private final ConcurrentHashMap<String, LiveIdSource> liveIdSources = new ConcurrentHashMap<>();

    /**
     * "Họ API": the collection an endpoint acts on, from its path template. The API that CREATES an ID and
     * the APIs that USE it share it:
     * <pre>
     *   POST   /api/a/b/c            -> /api/a/b/c
     *   GET    /api/a/b/c/15         -> /api/a/b/c
     *   DELETE /api/a/b/c/15         -> /api/a/b/c
     *   POST   /api/orders/15/cancel -> /api/orders
     *   GET    /api/orders/15/items  -> /api/orders/{id}/items
     * </pre>
     */
    public static String familyKey(String path) {
        String tpl = normalizePathTemplate(path);
        List<String> segs = new ArrayList<>();
        for (String p : tpl.split("/")) {
            if (!p.isEmpty()) {
                segs.add(p);
            }
        }
        if (!segs.isEmpty() && isActionWord(segs.get(segs.size() - 1)) && segs.size() >= 2
                && "{id}".equals(segs.get(segs.size() - 2))) {
            segs.remove(segs.size() - 1);
        }
        if (!segs.isEmpty() && "{id}".equals(segs.get(segs.size() - 1))) {
            segs.remove(segs.size() - 1);
        }
        if (!segs.isEmpty() && isActionWord(segs.get(segs.size() - 1)) && segs.size() >= 2) {
            segs.remove(segs.size() - 1);
        }
        return segs.isEmpty() ? "/" : "/" + String.join("/", segs);
    }

    private static boolean isActionWord(String seg) {
        return ACTION_PATH_HINT.matcher("/" + seg.toLowerCase(Locale.ROOT)).find();
    }

    /** True when the template has no ID after the family (a collection: list or create). */
    static boolean isCollection(String path) {
        String tpl = normalizePathTemplate(path);
        return tpl.equals(familyKey(path));
    }

    /** JSON fields that name the ID of THIS family's objects: id, _id, uuid, guid, order_id / orderId for /orders. */
    static List<String> familyIdsFromBody(byte[] body, String family) {
        List<String> out = new ArrayList<>();
        if (body == null || body.length == 0) {
            return out;
        }
        String last = family.substring(family.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String singular = last.endsWith("ies") ? last.substring(0, last.length() - 3) + "y"
                : (last.endsWith("s") && last.length() > 1 ? last.substring(0, last.length() - 1) : last);
        Set<String> names = new LinkedHashSet<>(List.of("id", "_id", "uuid", "guid"));
        for (String n : new String[] {singular, last}) {
            names.add(n + "id");
            names.add(n + "_id");
            names.add(n + "uuid");
            names.add(n + "_uuid");
        }
        Matcher m = JSON_ID_FIELD.matcher(new String(body, StandardCharsets.UTF_8));
        while (m.find()) {
            if (!names.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                continue;
            }
            String val = m.group(2) != null ? m.group(2) : m.group(3);
            if (val != null && !val.isBlank() && !out.contains(val)) {
                out.add(val);
            }
        }
        return out;
    }

    /** Adds an ID to a family with its source. */
    public void addLiveId(String family, String id, String how, String api, int status) {
        addLiveId(family, id);
        String fam = (family == null || family.isBlank()) ? "default" : family.toLowerCase(Locale.ROOT);
        liveIdSources.put(fam + "|" + id, new LiveIdSource(fam, id, how, api, status, System.currentTimeMillis()));
    }

    /** Source of an ID in a family, or null (added by hand / imported). */
    public LiveIdSource liveIdSource(String family, String id) {
        String fam = (family == null || family.isBlank()) ? "default" : family.toLowerCase(Locale.ROOT);
        return liveIdSources.get(fam + "|" + id);
    }

    /**
     * Thu thập ID sống — V_8.2: CHỈ từ response 2xx, CHỈ cho đúng họ API của request (không gom ID bất kỳ):
     * <ul>
     *   <li>POST tới collection 2xx: ID vừa tạo (Location / trường id) -> "tạo mới", và API này là API tạo mới của họ;</li>
     *   <li>GET collection 2xx: các trường id / {tên}_id của chính họ đó -> "danh sách";</li>
     *   <li>GET / PUT / PATCH 1 đối tượng 2xx: ID trên path -> "đọc / sửa được";</li>
     *   <li>DELETE 2xx (hoặc POST .../delete, /cancel...): ID đó -> đã dùng hết.</li>
     * </ul>
     */
    public void harvest(String method, String path, byte[] request, byte[] response, int status, int gid) {
        if (!autoHarvest || path == null || status < 200 || status >= 300) {
            return;
        }
        String family = familyKey(path);
        String m = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        String api = m + " " + Text.stripQuery(path);
        String lowerPath = Text.stripQuery(path).toLowerCase(Locale.ROOT);
        boolean destructive = "DELETE".equals(m)
                || lowerPath.contains("delete") || lowerPath.contains("remove") || lowerPath.contains("cancel")
                || lowerPath.contains("destroy") || lowerPath.contains("revoke");
        List<String> pathIds = extractPathIds(path);
        if (destructive) {
            for (String id : pathIds.isEmpty() ? detectIds(path, request) : pathIds) {
                markConsumed(id);
            }
            return;
        }
        boolean collection = isCollection(path);
        if ("POST".equals(m) && collection) {
            resourceProducers.put(family.toLowerCase(Locale.ROOT), gid);
            String created = extractCreatedIdForFamily(response, family);
            if (created != null) {
                addLiveId(family, created, "tạo mới", api, status);
            }
            return;
        }
        if ("GET".equals(m) && collection) {
            for (String id : familyIdsFromBody(bodyBytes(response), family)) {
                if (!consumedIds.contains(id)) {
                    addLiveId(family, id, "danh sách", api, status);
                }
            }
            return;
        }
        if (!pathIds.isEmpty()) {
            String id = pathIds.get(pathIds.size() - 1);
            if (!consumedIds.contains(id)) {
                addLiveId(family, id, "GET".equals(m) ? "đọc được" : "sửa được", api, status);
            }
        }
    }

    /** The created ID of a POST to {@code family}: Location header, then the family's own id fields. */
    public String extractCreatedIdForFamily(byte[] response, String family) {
        if (response == null || response.length == 0) {
            return null;
        }
        String loc = HttpBytes.header(response, "Location");
        if (loc != null && !loc.isBlank()) {
            List<String> locIds = extractPathIds(loc.trim());
            if (!locIds.isEmpty()) {
                return locIds.get(locIds.size() - 1);
            }
        }
        List<String> ids = familyIdsFromBody(bodyBytes(response), family);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public void addLiveId(String resource, String id) {
        if (id == null || id.isBlank()) return;
        String res = (resource == null || resource.isBlank()) ? "default" : resource.toLowerCase(Locale.ROOT);
        consumedIds.remove(id);
        CopyOnWriteArrayList<String> list = resourceLiveIds.computeIfAbsent(res, k -> new CopyOnWriteArrayList<>());
        if (!list.contains(id)) {
            list.add(id);
        }
        if (!globalLiveIds.contains(id)) {
            globalLiveIds.add(id);
        }
    }

    public void markConsumed(String id) {
        if (id == null || id.isBlank()) return;
        consumedIds.add(id);
    }

    public boolean isConsumed(String id) {
        return id != null && consumedIds.contains(id);
    }

    public Set<String> getConsumedIds() {
        return new LinkedHashSet<>(consumedIds);
    }

    /**
     * Lấy danh sách các ID sống khả dụng cho một API (ưu tiên cùng tài nguyên, loại trừ ID đang dùng và ID đã xóa).
     */
    public List<String> getAvailableLiveIds(String path, String excludeId) {
        List<String> result = new ArrayList<>();
        String family = familyKey(path).toLowerCase(Locale.ROOT);
        // newest first, IDs created by POST before IDs only seen in lists
        CopyOnWriteArrayList<String> byFam = resourceLiveIds.get(family);
        if (byFam != null) {
            List<String> rev = new ArrayList<>(byFam);
            java.util.Collections.reverse(rev);
            for (String pass : new String[] {"tạo mới", null}) {
                for (String id : rev) {
                    LiveIdSource src = liveIdSources.get(family + "|" + id);
                    boolean created = src != null && "tạo mới".equals(src.how());
                    if ((pass == null || created) && !consumedIds.contains(id) && !id.equals(excludeId) && !result.contains(id)) {
                        result.add(id);
                    }
                }
            }
        }
        // IDs added by hand under the resource noun (legacy pool / CSV) still count for that noun
        CopyOnWriteArrayList<String> byNoun = resourceLiveIds.get(extractResourceKey(path));
        if (byNoun != null && byNoun != byFam) {
            for (String id : byNoun) {
                if (!consumedIds.contains(id) && !id.equals(excludeId) && !result.contains(id)) {
                    result.add(id);
                }
            }
        }
        return result;
    }

    public Map<String, List<String>> getAllResourceLiveIds() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (Map.Entry<String, CopyOnWriteArrayList<String>> e : resourceLiveIds.entrySet()) {
            map.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return map;
    }

    /**
     * Tìm ID sống tiếp theo phù hợp nhất để thay cho ID cũ của API.
     */
    public String nextLiveId(String path, String currentId) {
        List<String> avail = getAvailableLiveIds(path, currentId);
        if (!avail.isEmpty()) {
            return avail.get(0);
        }
        return null;
    }

    public Integer findProducerGid(String path) {
        Integer byFamily = resourceProducers.get(familyKey(path).toLowerCase(Locale.ROOT));
        return byFamily != null ? byFamily : resourceProducers.get(extractResourceKey(path));
    }

    public void registerProducer(String resource, int gid) {
        if (resource != null && !resource.isBlank()) {
            resourceProducers.put(resource.toLowerCase(Locale.ROOT), gid);
        }
    }

    /**
     * Phát hiện tất cả các ID đang được truyền trong API (theo thứ tự ưu tiên: Path -> Query -> Body JSON/Form).
     */
    public List<String> detectIds(String path, byte[] request) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (path != null) {
            ids.addAll(extractPathIds(path));
            int qIdx = path.indexOf('?');
            if (qIdx >= 0 && qIdx + 1 < path.length()) {
                Matcher qm = QUERY_ID_PARAM.matcher(path.substring(qIdx));
                while (qm.find()) {
                    ids.add(qm.group(2));
                }
            }
        }
        if (request != null && request.length > 0) {
            byte[] body = bodyBytes(request);
            if (body.length > 0) {
                ids.addAll(extractIdsFromBody(body));
                String bodyStr = new String(body, StandardCharsets.UTF_8);
                Matcher fm = QUERY_ID_PARAM.matcher("&" + bodyStr);
                while (fm.find()) {
                    ids.add(fm.group(2));
                }
            }
        }
        return new ArrayList<>(ids);
    }

    public static List<String> extractPathIds(String path) {
        List<String> list = new ArrayList<>();
        if (path == null) return list;
        String clean = Text.stripQuery(path);
        for (String seg : clean.split("/")) {
            if (seg.isEmpty()) continue;
            if (PATH_SEGMENT_ID.matcher(seg).matches()) {
                list.add(seg);
            }
        }
        return list;
    }

    public static List<String> extractIdsFromBody(byte[] bodyBytes) {
        List<String> list = new ArrayList<>();
        if (bodyBytes == null || bodyBytes.length == 0) return list;
        String text = new String(bodyBytes, StandardCharsets.UTF_8);
        Matcher m = JSON_ID_FIELD.matcher(text);
        while (m.find()) {
            String val = m.group(2) != null ? m.group(2) : m.group(3);
            if (val != null && !val.isBlank() && !list.contains(val)) {
                list.add(val);
            }
        }
        return list;
    }

    /**
     * Trích xuất ID vừa được tạo từ Response của một Producer API (POST tạo mới).
     */
    public String extractCreatedId(byte[] response) {
        if (response == null || response.length == 0) return null;
        String loc = HttpBytes.header(response, "Location");
        if (loc != null && !loc.isBlank()) {
            List<String> locIds = extractPathIds(loc.trim());
            if (!locIds.isEmpty()) return locIds.get(locIds.size() - 1);
        }
        List<String> bodyIds = extractIdsFromBody(bodyBytes(response));
        if (!bodyIds.isEmpty()) {
            return bodyIds.get(0);
        }
        return null;
    }

    /**
     * Thay thế một ID cũ bằng ID mới trên đường dẫn path.
     */
    public static String replaceIdInPath(String path, String oldId, String newId) {
        if (path == null || oldId == null || newId == null || oldId.isEmpty()) return path;
        String escaped = Pattern.quote(oldId);
        // Thay trong path segment (/oldId/ hoặc /oldId$ hoặc /oldId?)
        String updated = path.replaceAll("(?<=/)" + escaped + "(?=/|\\?|$)", Matcher.quoteReplacement(newId));
        // Thay trong query param (=oldId& hoặc =oldId$)
        updated = updated.replaceAll("(?<==)" + escaped + "(?=&|$)", Matcher.quoteReplacement(newId));
        return updated;
    }

    /**
     * Thay thế một ID cũ bằng ID mới trong toàn bộ raw HTTP request (Request-Line, Query và Body)
     * và tự động cập nhật lại header Content-Length.
     */
    public static byte[] replaceIdInRequest(byte[] rawRequest, String oldId, String newId) {
        if (rawRequest == null || rawRequest.length == 0 || oldId == null || newId == null || oldId.isEmpty()) {
            return rawRequest;
        }
        String head = headerText(rawRequest);
        byte[] body = bodyBytes(rawRequest);

        String[] lines = head.split("\r?\n");
        if (lines.length > 0) {
            String[] rl = lines[0].split(" ", 3);
            if (rl.length >= 2) {
                rl[1] = replaceIdInPath(rl[1], oldId, newId);
                lines[0] = String.join(" ", rl);
            }
        }

        String escaped = Pattern.quote(oldId);
        if (body.length > 0) {
            String bodyStr = new String(body, StandardCharsets.UTF_8);
            // Thay trong JSON ("id": 78 hoặc "id": "78")
            String updatedBody = bodyStr.replaceAll(
                    "(\"[a-zA-Z0-9_]*(?:id|Id|ID|uuid|code)\"\\s*:\\s*\")" + escaped + "(\")",
                    "$1" + Matcher.quoteReplacement(newId) + "$2");
            updatedBody = updatedBody.replaceAll(
                    "(\"[a-zA-Z0-9_]*(?:id|Id|ID|uuid|code)\"\\s*:\\s*)" + escaped + "(?=\\s*[,}\\]])",
                    "$1" + Matcher.quoteReplacement(newId));
            // Thay trong form-urlencoded (id=78)
            updatedBody = updatedBody.replaceAll(
                    "(?<=^|[?&])([a-zA-Z0-9_]*(?:id|Id|ID|uuid|code)=)" + escaped + "(?=&|$)",
                    "$1" + Matcher.quoteReplacement(newId));
            body = updatedBody.getBytes(StandardCharsets.UTF_8);
        }

        return rebuildRequestWithContentLength(lines, body);
    }

    /**
     * Làm mới các trường Unique (email, username, code, phone, nonce...) trong body JSON/Form
     * để tránh lỗi 409 Conflict / Duplicate khi gửi lại API POST tạo mới.
     */
    public byte[] refreshUniqueFields(byte[] rawRequest) {
        if (rawRequest == null || rawRequest.length == 0) return rawRequest;
        String head = headerText(rawRequest);
        byte[] body = bodyBytes(rawRequest);
        if (body.length == 0) return rawRequest;

        int seq = uniqueSeq.incrementAndGet();
        String bodyStr = new String(body, StandardCharsets.UTF_8);
        Matcher m = JSON_UNIQUE_FIELD.matcher(bodyStr);
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        while (m.find()) {
            changed = true;
            String field = m.group(1);
            String val = m.group(2);
            String newVal;
            if (val.contains("@")) {
                int at = val.indexOf('@');
                String local = val.substring(0, at).replaceAll("\\+\\d+$", "");
                newVal = local + "+" + seq + val.substring(at);
            } else {
                String base = val.replaceAll("_\\d+$", "");
                newVal = base + "_" + seq;
            }
            m.appendReplacement(sb, "\"" + field + "\":\"" + Matcher.quoteReplacement(newVal) + "\"");
        }
        m.appendTail(sb);
        if (!changed) return rawRequest;

        byte[] newBody = sb.toString().getBytes(StandardCharsets.UTF_8);
        return rebuildRequestWithContentLength(head.split("\r?\n"), newBody);
    }

    private static byte[] rebuildRequestWithContentLength(String[] headerLines, byte[] body) {
        StringBuilder h = new StringBuilder();
        boolean hasCl = false;
        for (int i = 0; i < headerLines.length; i++) {
            String line = headerLines[i];
            if (i > 0 && line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                h.append("Content-Length: ").append(body.length).append("\r\n");
                hasCl = true;
            } else {
                h.append(line).append("\r\n");
            }
        }
        if (!hasCl && body.length > 0) {
            h.append("Content-Length: ").append(body.length).append("\r\n");
        }
        h.append("\r\n");
        byte[] headBytes = h.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(body, 0, out, headBytes.length, body.length);
        return out;
    }

    // =========================================================================
    // V2.1 Action Workbench Engine Upgrades:
    // Auto-suggest JSONPath, live extractor, multi-location swap preview, CSV pool
    // =========================================================================

    public record SuggestedPath(String expr, String sampleValue, String fieldName, boolean prioritized) {
        @Override
        public String toString() {
            return expr + "  =  " + sampleValue;
        }
    }

    public record SwapPreview(String beforeRequestLine, String afterRequestLine,
                              String beforeBodySnippet, String afterBodySnippet,
                              int replacements, List<String> locations) {
    }

    private volatile boolean autoHarvest = true;

    public boolean isAutoHarvest() {
        return autoHarvest;
    }

    public void setAutoHarvest(boolean enabled) {
        this.autoHarvest = enabled;
    }

    public void restoreConsumed(String id) {
        if (id != null) {
            consumedIds.remove(id);
        }
    }

    public void clearConsumed() {
        consumedIds.clear();
    }

    public boolean removeLiveId(String resource, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String res = (resource == null || resource.isBlank()) ? "default" : resource.toLowerCase(Locale.ROOT);
        CopyOnWriteArrayList<String> list = resourceLiveIds.get(res);
        boolean removed = list != null && list.remove(id);
        if (removed) {
            boolean stillElsewhere = resourceLiveIds.values().stream().anyMatch(l -> l.contains(id));
            if (!stillElsewhere) {
                globalLiveIds.remove(id);
            }
        }
        return removed;
    }

    public int addBulkLiveIds(String resource, String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return 0;
        }
        int added = 0;
        for (String tok : rawText.split("[,;\\s\\r\\n]+")) {
            String clean = tok.trim();
            if (!clean.isEmpty()) {
                addLiveId(resource, clean);
                added++;
            }
        }
        return added;
    }

    public String exportPoolCsv() {
        StringBuilder sb = new StringBuilder("resource,id,status\n");
        for (Map.Entry<String, CopyOnWriteArrayList<String>> e : resourceLiveIds.entrySet()) {
            for (String id : e.getValue()) {
                sb.append(e.getKey()).append(',').append(id).append(',')
                        .append(consumedIds.contains(id) ? "consumed" : "alive").append('\n');
            }
        }
        return sb.toString();
    }

    public int importPoolCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : csv.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.toLowerCase(Locale.ROOT).startsWith("resource,")) {
                continue;
            }
            String[] cols = trimmed.split(",", 3);
            if (cols.length >= 2) {
                String res = cols[0].trim();
                String id = cols[1].trim();
                String st = cols.length >= 3 ? cols[2].trim().toLowerCase(Locale.ROOT) : "alive";
                if (!res.isEmpty() && !id.isEmpty()) {
                    addLiveId(res, id);
                    if ("consumed".equals(st)) {
                        markConsumed(id);
                    }
                    count++;
                }
            }
        }
        return count;
    }

    public Map<String, Integer> getAllProducers() {
        return new LinkedHashMap<>(resourceProducers);
    }

    /**
     * Quét Response HTTP (Header Location + JSON body) và tự động gợi ý các đường dẫn trích xuất ID (JSONPath)
     * kèm giá trị thật tìm thấy để hiển thị trực quan trong Producer Chain Builder.
     */
    public static List<SuggestedPath> suggestJsonPaths(byte[] response) {
        List<SuggestedPath> out = new ArrayList<>();
        if (response == null || response.length == 0) {
            return out;
        }
        String loc = HttpBytes.header(response, "Location");
        if (loc != null && !loc.isBlank()) {
            List<String> ids = extractPathIds(loc.trim());
            if (!ids.isEmpty()) {
                out.add(new SuggestedPath("header:Location", ids.get(ids.size() - 1), "Location", true));
            }
        }
        byte[] body = bodyBytes(response);
        if (body.length > 0) {
            out.addAll(suggestJsonPaths(new String(body, StandardCharsets.UTF_8)));
        }
        return out;
    }

    public static List<SuggestedPath> suggestJsonPaths(String jsonBody) {
        List<SuggestedPath> out = new ArrayList<>();
        if (jsonBody == null || jsonBody.isBlank()) {
            return out;
        }
        String trimmed = jsonBody.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                Object parsed = Json.parse(trimmed);
                collectJsonSuggestions(parsed, "$", 0, out);
            } catch (RuntimeException ignored) {
                // fallback to regex below
            }
        }
        if (out.isEmpty()) {
            Matcher m = JSON_ID_FIELD.matcher(trimmed);
            Set<String> seen = new LinkedHashSet<>();
            while (m.find()) {
                String k = m.group(1);
                String v = m.group(2) != null ? m.group(2) : m.group(3);
                String expr = "$." + k;
                if (v != null && seen.add(expr)) {
                    out.add(new SuggestedPath(expr, v, k, true));
                }
            }
        }
        out.sort(Comparator.comparing((SuggestedPath s) -> !s.prioritized()).thenComparing(SuggestedPath::expr));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectJsonSuggestions(Object node, String path, int depth, List<SuggestedPath> out) {
        if (node == null || depth > 5 || out.size() >= 32) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object val = e.getValue();
                String childPath = path + "." + key;
                if (val instanceof String || val instanceof Number) {
                    String s = String.valueOf(val);
                    if (!s.isBlank() && s.length() <= 64) {
                        String kl = key.toLowerCase(Locale.ROOT);
                        boolean isIdLike = kl.equals("id") || kl.endsWith("id") || kl.endsWith("_id")
                                || kl.contains("uuid") || kl.contains("code") || kl.contains("token") || kl.contains("key");
                        if (isIdLike || depth <= 1) {
                            out.add(new SuggestedPath(childPath, s, key, isIdLike));
                        }
                    }
                } else {
                    collectJsonSuggestions(val, childPath, depth + 1, out);
                }
            }
        } else if (node instanceof List<?> list && !list.isEmpty()) {
            collectJsonSuggestions(list.get(0), path + "[0]", depth + 1, out);
        }
    }

    /**
     * Trích xuất giá trị từ HTTP Response theo biểu thức đơn giản:
     * {@code $.data.id}, {@code $.items[0].id}, {@code header:Location}, hoặc {@code regex:...}.
     */
    public static String extractByExpr(byte[] response, String expr) {
        if (response == null || response.length == 0 || expr == null || expr.isBlank()) {
            return null;
        }
        String e = expr.trim();
        if (e.toLowerCase(Locale.ROOT).startsWith("header:")) {
            String headerName = e.substring(7).trim();
            String hval = HttpBytes.header(response, headerName);
            if (hval == null || hval.isBlank()) {
                return null;
            }
            List<String> ids = extractPathIds(hval.trim());
            return ids.isEmpty() ? hval.trim() : ids.get(ids.size() - 1);
        }
        byte[] body = bodyBytes(response);
        if (body.length == 0) {
            return null;
        }
        String bodyStr = new String(body, StandardCharsets.UTF_8);
        if (e.toLowerCase(Locale.ROOT).startsWith("regex:")) {
            try {
                Matcher m = Pattern.compile(e.substring(6).trim()).matcher(bodyStr);
                if (m.find()) {
                    return m.groupCount() >= 1 ? m.group(1) : m.group(0);
                }
            } catch (RuntimeException ignored) {
                return null;
            }
            return null;
        }
        // Simple JSONPath: $.a.b[0].id
        try {
            Object cur = Json.parse(bodyStr.trim());
            String path = e.startsWith("$.") ? e.substring(2) : (e.startsWith("$") ? e.substring(1) : e);
            for (String seg : path.split("\\.")) {
                if (seg.isEmpty() || cur == null) {
                    continue;
                }
                int br = seg.indexOf('[');
                if (br >= 0 && seg.endsWith("]")) {
                    String key = seg.substring(0, br);
                    int idx = Integer.parseInt(seg.substring(br + 1, seg.length() - 1));
                    if (!key.isEmpty() && cur instanceof Map<?, ?> m) {
                        cur = m.get(key);
                    }
                    if (cur instanceof List<?> list && idx >= 0 && idx < list.size()) {
                        cur = list.get(idx);
                    } else {
                        return null;
                    }
                } else if (cur instanceof Map<?, ?> m) {
                    cur = m.get(seg);
                } else {
                    return null;
                }
            }
            return cur == null ? null : String.valueOf(cur);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Thay ID chọn lọc theo từng vị trí (Path / Query / Body / Header) và trả về bản xem trước trực quan.
     */
    public static SwapPreview previewSwap(byte[] rawRequest, String oldId, String newId,
                                          boolean swapPath, boolean swapQuery, boolean swapBody, boolean swapHeader) {
        if (rawRequest == null || rawRequest.length == 0) {
            return new SwapPreview("", "", "", "", 0, List.of());
        }
        String beforeHead = headerText(rawRequest);
        String[] beforeLines = beforeHead.split("\r?\n");
        String beforeRl = beforeLines.length > 0 ? beforeLines[0] : "";
        String beforeBody = new String(bodyBytes(rawRequest), StandardCharsets.UTF_8);

        if (oldId == null || oldId.isEmpty() || newId == null || newId.isEmpty()) {
            return new SwapPreview(beforeRl, beforeRl, snippet(beforeBody), snippet(beforeBody), 0, List.of());
        }

        List<String> locs = new ArrayList<>();
        int count = 0;
        String[] lines = Arrays.copyOf(beforeLines, beforeLines.length);
        String escaped = Pattern.quote(oldId);

        if (lines.length > 0) {
            String[] rl = lines[0].split(" ", 3);
            if (rl.length >= 2) {
                String fullTarget = rl[1];
                int q = fullTarget.indexOf('?');
                String pathPart = q >= 0 ? fullTarget.substring(0, q) : fullTarget;
                String queryPart = q >= 0 ? fullTarget.substring(q) : "";

                if (swapPath) {
                    String np = pathPart.replaceAll("(?<=/)" + escaped + "(?=/|$)", Matcher.quoteReplacement(newId));
                    if (!np.equals(pathPart)) {
                        pathPart = np;
                        count++;
                        locs.add("URL Path");
                    }
                }
                if (swapQuery && !queryPart.isEmpty()) {
                    String nq = queryPart.replaceAll("(?<==)" + escaped + "(?=&|$)", Matcher.quoteReplacement(newId));
                    if (!nq.equals(queryPart)) {
                        queryPart = nq;
                        count++;
                        locs.add("Query");
                    }
                }
                rl[1] = pathPart + queryPart;
                lines[0] = String.join(" ", rl);
            }
        }
        if (swapHeader) {
            for (int i = 1; i < lines.length; i++) {
                String nl = lines[i].replaceAll("(?<=[:/\\s=])" + escaped + "(?=$|[/\\s;&,])", Matcher.quoteReplacement(newId));
                if (!nl.equals(lines[i])) {
                    lines[i] = nl;
                    count++;
                    if (!locs.contains("Header")) {
                        locs.add("Header");
                    }
                }
            }
        }
        String afterBody = beforeBody;
        if (swapBody && !beforeBody.isEmpty()) {
            String ub = beforeBody.replaceAll(
                    "(\"[a-zA-Z0-9_]*(?:id|Id|ID|uuid|code)\"\\s*:\\s*\")" + escaped + "(\")",
                    "$1" + Matcher.quoteReplacement(newId) + "$2");
            ub = ub.replaceAll(
                    "(\"[a-zA-Z0-9_]*(?:id|Id|ID|uuid|code)\"\\s*:\\s*)" + escaped + "(?=\\s*[,}\\]])",
                    "$1" + Matcher.quoteReplacement(newId));
            ub = ub.replaceAll(
                    "(?<=^|[?&])([a-zA-Z0-9_]*(?:id|Id|ID|uuid|code)=)" + escaped + "(?=&|$)",
                    "$1" + Matcher.quoteReplacement(newId));
            if (!ub.equals(beforeBody)) {
                afterBody = ub;
                count++;
                locs.add("Body");
            }
        }
        String afterRl = lines.length > 0 ? lines[0] : "";
        return new SwapPreview(beforeRl, afterRl, snippet(beforeBody), snippet(afterBody), count, locs);
    }

    private static String snippet(String s) {
        if (s == null || s.isBlank()) {
            return "(Không có body)";
        }
        String flat = s.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() > 140 ? flat.substring(0, 140) + "..." : flat;
    }

    // =========================================================================
    // V2.2 Proactive Proxy Request Interception Rules (Branch/Path + Method Matrix)
    // =========================================================================

    public record InterceptDecision(ActionInterceptRule.Effect effect, ActionInterceptRule rule, int ruleIndex) {
        public boolean matched() {
            return rule != null && effect != null;
        }
    }

    private final CopyOnWriteArrayList<ActionInterceptRule> interceptRules = new CopyOnWriteArrayList<>();

    public List<ActionInterceptRule> interceptRules() {
        return new ArrayList<>(interceptRules);
    }

    public int activeBlockRuleCount() {
        int count = 0;
        for (ActionInterceptRule r : interceptRules) {
            if (r.enabled() && r.effect().isBlock()) {
                count++;
            }
        }
        return count;
    }

    public synchronized void setInterceptRules(List<ActionInterceptRule> rules) {
        interceptRules.clear();
        if (rules != null) {
            for (ActionInterceptRule r : rules) {
                if (r != null) {
                    interceptRules.add(r.copy());
                }
            }
        }
    }

    public synchronized ActionInterceptRule upsertInterceptRule(ActionInterceptRule rule) {
        if (rule == null) {
            return null;
        }
        ActionInterceptRule copy = rule.copy();
        for (int i = 0; i < interceptRules.size(); i++) {
            if (interceptRules.get(i).id().equals(copy.id())) {
                interceptRules.set(i, copy);
                return copy;
            }
        }
        interceptRules.add(copy);
        return copy;
    }

    public synchronized boolean removeInterceptRule(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            return false;
        }
        return interceptRules.removeIf(r -> ruleId.equals(r.id()));
    }

    public synchronized boolean moveInterceptRule(String ruleId, int delta) {
        if (ruleId == null || delta == 0) {
            return false;
        }
        int idx = -1;
        for (int i = 0; i < interceptRules.size(); i++) {
            if (ruleId.equals(interceptRules.get(i).id())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return false;
        }
        int target = idx + delta;
        if (target < 0 || target >= interceptRules.size()) {
            return false;
        }
        ActionInterceptRule item = interceptRules.remove(idx);
        interceptRules.add(target, item);
        return true;
    }

    public synchronized boolean toggleInterceptRule(String ruleId) {
        if (ruleId == null) {
            return false;
        }
        for (ActionInterceptRule r : interceptRules) {
            if (ruleId.equals(r.id())) {
                r.setEnabled(!r.enabled());
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluates active proactive Proxy rules in top-to-bottom priority order.
     * First matching enabled rule wins (ALLOW stops evaluation and permits request through).
     */
    public InterceptDecision evaluateInterceptRules(String activeBranch, String host, String method, String path) {
        for (int i = 0; i < interceptRules.size(); i++) {
            ActionInterceptRule r = interceptRules.get(i);
            if (r.matches(activeBranch, host, method, path)) {
                return new InterceptDecision(r.effect(), r, i);
            }
        }
        return new InterceptDecision(null, null, -1);
    }

    public synchronized void loadInterceptRulesJson(String json) {
        setInterceptRules(ActionInterceptRule.listFromJson(json));
    }

    public synchronized String interceptRulesToJson() {
        return ActionInterceptRule.listToJson(interceptRules);
    }
}
