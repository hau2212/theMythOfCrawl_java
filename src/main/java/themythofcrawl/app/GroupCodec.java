package themythofcrawl.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import themythofcrawl.core.Json;
import themythofcrawl.excel.TargetEntry;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.HeaderRule;
import themythofcrawl.model.PlaceHint;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.Service;

/** One API <-> its JSON document in the workspace (messages are stored next to it, read lazily). */
public final class GroupCodec {
    public static final String ORIG_REQ = "orig.req";
    public static final String ORIG_RESP = "orig.resp";

    private GroupCodec() {
    }

    public static String reqName(RoleResult r) {
        return r.original() ? "orig.custom.req" : "r" + r.id() + ".req";
    }

    public static String respName(RoleResult r) {
        return r.original() ? "orig.sent.resp" : "r" + r.id() + ".resp";
    }

    static Map<String, Object> result(RoleResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.roleName());
        m.put("key", r.roleKey());
        m.put("index", r.roleIndex());
        m.put("stripped", r.strippedAuth());
        List<Object> rules = new ArrayList<>();
        for (HeaderRule h : r.rules()) {
            rules.add(h.toMap());
        }
        m.put("rules", rules);
        m.put("status", r.status());
        m.put("length", r.length());
        m.put("elapsed", r.elapsedMs());
        m.put("state", r.state());
        m.put("error", r.error());
        m.put("sent", r.sentCount());
        m.put("tab", r.tabName());
        m.put("custom_req", r.customRequest() != null);
        m.put("resp", r.responseBlob() != null);
        return m;
    }

    public static String toJson(ApiGroup g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gid", g.gid());
        m.put("host", g.host());
        m.put("port", g.port());
        m.put("secure", g.service().secure());
        m.put("method", g.method());
        m.put("path", g.path());
        m.put("url", g.url());
        m.put("source", g.source());
        m.put("created", g.created());
        m.put("created_ms", g.createdMillis());
        m.put("branch_id", g.branchId());
        m.put("branch", g.branch());
        m.put("orig_status", g.origStatus());
        m.put("orig_length", g.origLength());
        m.put("has_orig_resp", g.origResponse() != null);
        m.put("target_row", g.target() != null ? g.target().rowNo() : null);
        m.put("overrides", g.roleOverrides());
        m.put("copy_stt", g.copyStt());
        m.put("copied", g.copiedLabel());
        m.put("book_key", g.bookKey());
        m.put("dedup_key", g.dedupKey());
        m.put("pending", g.pending());
        m.put("dup_pending", g.dupPending());
        m.put("dup_no", g.dupNo());
        m.put("hidden", g.hidden());
        m.put("captured_at_branch", g.capturedAtBranch());
        m.put("tags", new ArrayList<>(g.tags()));
        m.put("seen_by", new ArrayList<>(g.seenBy()));
        List<Object> bugList = new ArrayList<>();
        for (themythofcrawl.model.BugMark b : g.bugs()) {
            bugList.add(b.toMap());
        }
        m.put("bugs", bugList);
        m.put("suspected_refresh", g.suspectedRefresh());
        m.put("triggered_by_action", g.triggeredByAction());
        PlaceHint h = g.placeHint();
        if (h != null) {
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("old", h.old());
            hm.put("found", h.found());
            hm.put("old_missing", h.oldMissing());
            hm.put("deleted", h.deleted());
            m.put("hint", hm);
        }
        m.put("history", g.placeHistory());
        m.put("orig", result(g.origResult()));
        List<Object> rs = new ArrayList<>();
        for (RoleResult r : g.results()) {
            rs.add(result(r));
        }
        m.put("results", rs);
        return Json.write(m);
    }

    @SuppressWarnings("unchecked")
    static void fill(RoleResult r, Map<String, Object> m, Storage st, int gid) {
        Object s = m.get("status");
        r.setStatus(s instanceof Number n ? n.intValue() : null);
        Object l = m.get("length");
        r.setLength(l instanceof Number n ? n.intValue() : null);
        Object e = m.get("elapsed");
        r.setElapsedMs(e instanceof Number n ? n.longValue() : null);
        String state = Json.str(m, "state", r.state());
        // a send that was running when Burp closed did not finish
        r.setState(state.equals("Queued") || state.equals("Sending") || state.equals("Chờ thử lại")
                ? (r.status() != null ? "Done" : "Not sent") : state);
        r.setError(Json.str(m, "error", ""));
        r.setSentCount((int) Json.num(m, "sent", 0));
        r.setTabName(Json.str(m, "tab", ""));
        if (Json.bool(m, "custom_req", false)) {
            r.setCustomRequest(st.blob(gid, reqName(r)));
        }
        if (Json.bool(m, "resp", false)) {
            r.setResponse(st.blob(gid, respName(r)));
        }
    }

    @SuppressWarnings("unchecked")
    public static ApiGroup fromJson(String doc, Storage st, IntFunction<TargetEntry> targetByRow) {
        Map<String, Object> m = Json.parseObject(doc);
        int gid = (int) Json.num(m, "gid", 0);
        Service svc = new Service(Json.str(m, "host", ""), (int) Json.num(m, "port", 443), Json.bool(m, "secure", true));
        ApiGroup g = new ApiGroup(gid, svc, Json.str(m, "method", "GET"), Json.str(m, "path", "/"), Json.str(m, "url", ""),
                Json.str(m, "source", ""), (int) Json.num(m, "branch_id", 1), Json.str(m, "branch", ""),
                st.blob(gid, ORIG_REQ), Json.bool(m, "has_orig_resp", false) ? st.blob(gid, ORIG_RESP) : null,
                Json.str(m, "created", null), Json.num(m, "created_ms", 0));
        Object os = m.get("orig_status");
        Object ol = m.get("orig_length");
        g.setOrigMeta(os instanceof Number n ? n.intValue() : null, ol instanceof Number n2 ? n2.intValue() : null);
        Object row = m.get("target_row");
        if (row instanceof Number n && targetByRow != null) {
            g.setTarget(targetByRow.apply(n.intValue()));
        }
        Json.obj(m, "overrides").forEach((k, v) -> g.setRoleOverride(k, v instanceof Boolean b ? b : null));
        Object cs = m.get("copy_stt");
        g.setCopyStt(cs instanceof Number n ? n.intValue() : null);
        g.setCopiedLabel(Json.str(m, "copied", null));
        g.setBookKey(Json.str(m, "book_key", null));
        g.setDedupKey(Json.str(m, "dedup_key", null));
        g.setPending(Json.bool(m, "pending", false));
        g.setDupPending(Json.bool(m, "dup_pending", false));
        g.setDupNo((int) Json.num(m, "dup_no", 1));
        g.setHidden(Json.bool(m, "hidden", false));
        String capAt = Json.str(m, "captured_at_branch", null);
        if (capAt != null && !capAt.isBlank()) {
            g.setCapturedAtBranch(capAt);
        }
        for (Object t : Json.list(m, "tags")) {
            if (t != null) {
                g.addTag(String.valueOf(t));
            }
        }
        List<String> seen = new ArrayList<>();
        for (Object t : Json.list(m, "seen_by")) {
            if (t != null) {
                seen.add(String.valueOf(t));
            }
        }
        g.setSeenBy(seen);
        for (Object o : Json.list(m, "bugs")) {
            if (o instanceof Map<?, ?> bm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) bm;
                g.putBug(themythofcrawl.model.BugMark.fromMap(mm));
            }
        }
        g.setSuspectedRefresh(Json.bool(m, "suspected_refresh", false));
        g.setTriggeredByAction(Json.str(m, "triggered_by_action", null));
        Map<String, Object> hm = Json.obj(m, "hint");
        if (!hm.isEmpty()) {
            g.setPlaceHint(new PlaceHint(Json.str(hm, "old", ""), Json.str(hm, "found", ""), Json.bool(hm, "old_missing", false),
                    (int) Json.num(hm, "deleted", 0)));
        }
        for (Object p : Json.list(m, "history")) {
            g.pushPlace(String.valueOf(p));
        }
        Map<String, Object> om = Json.obj(m, "orig");
        RoleResult orig = g.origResult();
        fill(orig, om, st, gid);
        orig.setRules(rules(om));
        orig.setRoleName(Json.str(om, "name", orig.roleName()));
        List<RoleResult> results = new ArrayList<>();
        for (Object o : Json.list(m, "results")) {
            Map<String, Object> rm = (Map<String, Object>) o;
            RoleResult r = new RoleResult(Json.str(rm, "name", ""), Json.str(rm, "key", null), (int) Json.num(rm, "index", 0),
                    false, Json.bool(rm, "stripped", false), rules(rm));
            r.setId((int) Json.num(rm, "id", 0));
            g.seenResultId(r.id());
            fill(r, rm, st, gid);
            results.add(r);
        }
        g.setResults(results);
        return g;
    }

    @SuppressWarnings("unchecked")
    static List<HeaderRule> rules(Map<String, Object> m) {
        List<HeaderRule> out = new ArrayList<>();
        for (Object o : Json.list(m, "rules")) {
            Map<String, Object> h = (Map<String, Object>) o;
            HeaderRule r = HeaderRule.of(h.get("name"), h.get("value"), h.get("mode"));
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }
}
