package themythofcrawl.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.AuditLevel;
import themythofcrawl.core.Text;
import themythofcrawl.excel.TargetEntry;

/**
 * One captured API: the original request / response of the crawl account plus one virtual row per
 * role, where it sits in the branch tree, and its placement state (pending, duplicate copy, hidden).
 */
public final class ApiGroup {
    private final int gid;
    private volatile Service service;
    private volatile String method;
    private volatile String path;
    private volatile String url;
    private final String source;
    private final String created;
    private final long createdMillis;
    private volatile int branchId;
    private volatile String branch;
    private volatile String capturedAtBranch;
    private volatile Blob origRequest;
    private volatile Blob origResponse;
    private volatile Integer origStatus;
    private volatile Integer origLength;
    // Snapshot bảo vệ 2xx ban đầu (Method 2: LOCK_2XX)
    private volatile Blob firstOkRequest;
    private volatile Blob firstOkResponse;
    private volatile Integer firstOkStatus;
    private volatile Integer firstOkLength;
    private volatile String firstOkPath;
    // Cấu hình xử lý Action riêng cho từng API (có thể đổi bất kỳ lúc nào sau khi bắt)
    private volatile ActionEngine.ActionMode actionModeOverride;
    private volatile Integer producerGid;
    private volatile boolean heldAtProxy;
    private volatile String activeIdNote;
    private final RoleResult origResult;
    private volatile List<RoleResult> results = List.of();
    private volatile TargetEntry target;
    private final Map<String, Boolean> roleOverrides = new LinkedHashMap<>();
    private final LinkedHashSet<String> tags = new LinkedHashSet<>();
    /** V_8.2: bugs marked on this API ("Lỗi"). */
    private final java.util.ArrayList<BugMark> bugs = new java.util.ArrayList<>();
    /** V_7.0: roles whose own UI reached this endpoint while crawling ("Thấy bởi"). */
    private final LinkedHashSet<String> seenBy = new LinkedHashSet<>();
    private volatile boolean suspectedRefresh;
    private volatile String triggeredByAction;
    private volatile Integer copyStt;
    private volatile String copiedLabel;
    private volatile String bookKey;
    private volatile String dedupKey;
    private volatile boolean pending;
    private volatile boolean dupPending;
    private volatile int dupNo = 1;
    private volatile int dupTotal = 1;
    private volatile boolean hidden;
    private volatile PlaceHint placeHint;
    private final List<String> placeHistory = new ArrayList<>();
    private volatile AuditLevel auditLevel;
    private volatile String auditSummary;
    private int nextResultId = 1;

    public ApiGroup(int gid, Service service, String method, String path, String url, String source,
                    int branchId, String branch, Blob origRequest, Blob origResponse, String created, long createdMillis) {
        this.gid = gid;
        this.service = service;
        this.method = Text.nz(method);
        this.path = Text.nz(path);
        this.url = Text.nz(url);
        this.source = Text.nz(source);
        this.branchId = branchId;
        this.branch = Text.blank(branch) ? "Chức năng " + branchId : branch.trim();
        this.origRequest = origRequest;
        this.origResponse = origResponse;
        this.created = created == null ? Clock.hms() : created;
        this.createdMillis = createdMillis;
        this.origResult = new RoleResult(Role.ORIGINAL_LABEL, Role.ORIGINAL_PROFILE_NAME, -1, true, false, List.of());
        this.origResult.setState(origResponse != null ? ResultState.ORIGIN : ResultState.NOT_SENT);
    }

    public int gid() { return gid; }
    public Service service() { return service; }
    public void setService(Service svc) { if (svc != null) this.service = svc; }
    public String host() { return service.host(); }
    public int port() { return service.port(); }
    public String protocol() { return service.protocol(); }
    public String method() { return method; }
    public void setMethod(String method) { if (method != null && !method.isBlank()) this.method = method.trim().toUpperCase(java.util.Locale.ROOT); }
    public String path() { return path; }
    public String url() { return url; }

    public void setPathAndUrl(String newPath, String newUrl) {
        if (newPath != null && !newPath.isBlank()) this.path = newPath;
        if (newUrl != null && !newUrl.isBlank()) this.url = newUrl;
    }

    public String source() { return source; }
    public String created() { return created; }
    public long createdMillis() { return createdMillis; }
    public int branchId() { return branchId; }
    public String branch() { return branch; }

    public void setBranch(int id, String name) {
        branchId = id;
        branch = name;
    }

    public Blob origRequest() { return origRequest; }
    public Blob origResponse() { return origResponse; }

    public void setOriginal(Blob req, Blob resp) {
        origRequest = req;
        origResponse = resp;
    }

    public Integer origStatus() { return origStatus; }
    public Integer origLength() { return origLength; }

    public void setOrigMeta(Integer status, Integer length) {
        origStatus = status;
        origLength = length;
        if (status != null && status >= 200 && status < 300 && firstOkResponse == null && origResponse != null) {
            saveFirstOkSnapshot(origRequest, origResponse, status, length, path);
        }
    }

    public void saveFirstOkSnapshot(Blob req, Blob resp, Integer status, Integer length, String snapPath) {
        if (resp == null || status == null || status < 200 || status >= 300) return;
        this.firstOkRequest = req;
        this.firstOkResponse = resp;
        this.firstOkStatus = status;
        this.firstOkLength = length;
        this.firstOkPath = snapPath != null ? snapPath : this.path;
    }

    public boolean hasFirstOkSnapshot() {
        return firstOkResponse != null && firstOkStatus != null && firstOkStatus >= 200 && firstOkStatus < 300;
    }

    public Blob firstOkRequest() { return firstOkRequest; }
    public Blob firstOkResponse() { return firstOkResponse; }
    public Integer firstOkStatus() { return firstOkStatus; }
    public Integer firstOkLength() { return firstOkLength; }
    public String firstOkPath() { return firstOkPath; }

    public ActionEngine.ActionMode actionModeOverride() { return actionModeOverride; }
    public void setActionModeOverride(ActionEngine.ActionMode mode) { this.actionModeOverride = mode; }

    public Integer producerGid() { return producerGid; }
    public void setProducerGid(Integer gid) { this.producerGid = gid; }

    public boolean heldAtProxy() { return heldAtProxy; }
    public void setHeldAtProxy(boolean held) { this.heldAtProxy = held; }

    public String activeIdNote() { return activeIdNote; }
    public void setActiveIdNote(String note) { this.activeIdNote = note; }

    public RoleResult origResult() { return origResult; }
    public List<RoleResult> results() { return results; }
    public void setResults(List<RoleResult> r) { results = List.copyOf(r); }

    /** [API gốc] + roles. */
    public List<RoleResult> entries() {
        List<RoleResult> out = new ArrayList<>(results.size() + 1);
        out.add(origResult);
        out.addAll(results);
        return out;
    }

    public TargetEntry target() { return target; }
    public void setTarget(TargetEntry t) { target = t; }

    public synchronized Map<String, Boolean> roleOverrides() {
        return new LinkedHashMap<>(roleOverrides);
    }

    public synchronized void setRoleOverride(String role, Boolean on) {
        if (on == null) {
            roleOverrides.remove(role);
        } else {
            roleOverrides.put(role, on);
        }
    }

    public synchronized void clearRoleOverrides() {
        roleOverrides.clear();
    }

    public String capturedAtBranch() {
        return capturedAtBranch != null ? capturedAtBranch : branch;
    }

    public void setCapturedAtBranch(String b) {
        this.capturedAtBranch = b;
    }

    public synchronized java.util.List<BugMark> bugs() {
        return java.util.List.copyOf(bugs);
    }

    public synchronized int openBugCount() {
        int n = 0;
        for (BugMark b : bugs) {
            if (b.status().open()) {
                n++;
            }
        }
        return n;
    }

    /** Adds or replaces (same id) a bug mark. */
    public synchronized void putBug(BugMark b) {
        if (b == null) {
            return;
        }
        for (int i = 0; i < bugs.size(); i++) {
            if (bugs.get(i).id().equals(b.id())) {
                bugs.set(i, b);
                return;
            }
        }
        bugs.add(b);
    }

    public synchronized boolean removeBug(String bugId) {
        return bugs.removeIf(b -> b.id().equals(bugId));
    }

    public synchronized Set<String> seenBy() {
        return new LinkedHashSet<>(seenBy);
    }

    public synchronized boolean seenBy(String role) {
        return role != null && seenBy.contains(role);
    }

    /** Records that {@code role} reached this endpoint through its own UI; true when new. */
    public synchronized boolean addSeenBy(String role) {
        return !Text.blank(role) && seenBy.add(role.trim());
    }

    public synchronized void setSeenBy(Collection<String> roles) {
        seenBy.clear();
        if (roles != null) {
            for (String r : roles) {
                if (!Text.blank(r)) {
                    seenBy.add(r.trim());
                }
            }
        }
    }

    public synchronized Set<String> tags() {
        return new LinkedHashSet<>(tags);
    }

    public synchronized boolean hasTag(String tagId) {
        return tagId != null && tags.contains(ApiTagCatalog.normalizeId(tagId));
    }

    public synchronized boolean addTag(String tagId) {
        if (Text.blank(tagId)) {
            return false;
        }
        return tags.add(ApiTagCatalog.normalizeId(tagId));
    }

    public synchronized boolean removeTag(String tagId) {
        if (Text.blank(tagId)) {
            return false;
        }
        return tags.remove(ApiTagCatalog.normalizeId(tagId));
    }

    public synchronized void setTags(Collection<String> next) {
        tags.clear();
        if (next != null) {
            for (String t : next) {
                if (!Text.blank(t)) {
                    tags.add(ApiTagCatalog.normalizeId(t));
                }
            }
        }
    }

    public boolean suspectedRefresh() { return suspectedRefresh; }
    public void setSuspectedRefresh(boolean s) { suspectedRefresh = s; }
    public String triggeredByAction() { return triggeredByAction; }
    public void setTriggeredByAction(String a) { triggeredByAction = a; }

    public Integer copyStt() { return copyStt; }
    public void setCopyStt(Integer n) { copyStt = n; }
    public String copiedLabel() { return copiedLabel; }
    public void setCopiedLabel(String s) { copiedLabel = s; }
    public String bookKey() { return bookKey; }
    public void setBookKey(String k) { bookKey = k; }
    public String dedupKey() { return dedupKey; }
    public void setDedupKey(String k) { dedupKey = k; }
    public boolean pending() { return pending; }
    public void setPending(boolean p) { pending = p; }
    public boolean dupPending() { return dupPending; }
    public void setDupPending(boolean p) { dupPending = p; }
    public int dupNo() { return dupNo; }
    public void setDupNo(int n) { dupNo = n; }
    public int dupTotal() { return dupTotal; }
    public void setDupTotal(int n) { dupTotal = n; }
    public boolean hidden() { return hidden; }
    public void setHidden(boolean h) { hidden = h; }
    public PlaceHint placeHint() { return placeHint; }
    public void setPlaceHint(PlaceHint h) { placeHint = h; }

    public synchronized List<String> placeHistory() {
        return new ArrayList<>(placeHistory);
    }

    public synchronized void pushPlace(String previous) {
        placeHistory.add(previous);
        while (placeHistory.size() > 30) {
            placeHistory.remove(0);
        }
    }

    public synchronized String popPlace() {
        return placeHistory.isEmpty() ? null : placeHistory.remove(placeHistory.size() - 1);
    }

    public synchronized int nextResultId() {
        return nextResultId++;
    }

    public synchronized void seenResultId(int id) {
        nextResultId = Math.max(nextResultId, id + 1);
    }

    public AuditLevel auditLevel() { return auditLevel; }
    public String auditSummary() { return auditSummary; }

    public void setAudit(AuditLevel level, String summary) {
        auditLevel = level;
        auditSummary = summary;
    }

    public String endpoint() {
        return method + " " + Text.stripQuery(path);
    }
}
