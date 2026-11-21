package themythofcrawl.export;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import themythofcrawl.app.Engine;
import themythofcrawl.core.HttpBytes;
import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.RoleResult;

/**
 * V_7.0: HAR 1.2 export of captured APIs (original request + response, optionally every role) so the
 * test-management server can import them directly, without replaying anything. Each entry carries the
 * JCrawl test-case fields in {@code _jcrawl} (API #, branch, Thấy bởi, role, verdict).
 */
public final class HarExport {
    private HarExport() {
    }

    public static String build(Engine engine, Collection<ApiGroup> groups, boolean onlyOrig2xx, boolean includeRoles) {
        List<Object> entries = new ArrayList<>();
        for (ApiGroup g : groups) {
            Integer st = g.origStatus();
            if (onlyOrig2xx && (st == null || st < 200 || st >= 300)) {
                continue;
            }
            List<RoleResult> rows = new ArrayList<>();
            rows.add(g.origResult());
            if (includeRoles) {
                rows.addAll(g.results());
            }
            for (RoleResult r : rows) {
                byte[] req = engine.requestOf(g, r);
                byte[] resp = engine.responseOf(g, r);
                if (req == null) {
                    continue;
                }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("startedDateTime", Instant.ofEpochMilli(g.createdMillis()).toString());
                e.put("time", 0);
                e.put("request", request(g, req));
                e.put("response", response(resp));
                e.put("cache", Map.of());
                e.put("timings", Map.of("send", 0, "wait", 0, "receive", 0));
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("api", g.gid());
                meta.put("branch", g.branch());
                meta.put("seen_by", new ArrayList<>(g.seenBy()));
                meta.put("role", r.original() ? Engine.CRAWL_ACCOUNT : r.roleKey());
                meta.put("verdict", Text.nz(r.auditBadge()));
                meta.put("tags", new ArrayList<>(g.tags()));
                e.put("_jcrawl", meta);
                entries.add(e);
            }
        }
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("version", "1.2");
        log.put("creator", Map.of("name", "theMythOf-JCrawl", "version", "7.2-beta"));
        log.put("entries", entries);
        return Json.write(Map.of("log", log));
    }

    private static Map<String, Object> request(ApiGroup g, byte[] raw) {
        String head = head(raw);
        String[] lines = head.split("\r?\n");
        String[] rl = lines.length > 0 ? lines[0].split(" ") : new String[0];
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", rl.length > 0 ? rl[0] : g.method());
        m.put("url", g.url());
        m.put("httpVersion", rl.length > 2 ? rl[2] : "HTTP/1.1");
        m.put("cookies", List.of());
        m.put("headers", headers(lines));
        m.put("queryString", List.of());
        String body = body(raw);
        if (!body.isEmpty()) {
            String ct = HttpBytes.header(raw, "Content-Type");
            m.put("postData", Map.of("mimeType", ct == null ? "" : ct, "text", body));
        }
        m.put("headersSize", -1);
        m.put("bodySize", body.length());
        return m;
    }

    private static Map<String, Object> response(byte[] raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (raw == null) {
            m.put("status", 0);
            m.put("statusText", "");
            m.put("httpVersion", "HTTP/1.1");
            m.put("cookies", List.of());
            m.put("headers", List.of());
            m.put("content", Map.of("size", 0, "mimeType", "", "text", ""));
            m.put("redirectURL", "");
            m.put("headersSize", -1);
            m.put("bodySize", 0);
            return m;
        }
        String head = head(raw);
        String[] lines = head.split("\r?\n");
        String[] sl = lines.length > 0 ? lines[0].split(" ", 3) : new String[0];
        Integer code = HttpBytes.statusCode(raw);
        String body = body(raw);
        String ct = HttpBytes.header(raw, "Content-Type");
        m.put("status", code == null ? 0 : code);
        m.put("statusText", sl.length > 2 ? sl[2] : "");
        m.put("httpVersion", sl.length > 0 ? sl[0] : "HTTP/1.1");
        m.put("cookies", List.of());
        m.put("headers", headers(lines));
        m.put("content", Map.of("size", body.length(), "mimeType", ct == null ? "" : ct, "text", body));
        m.put("redirectURL", "");
        m.put("headersSize", -1);
        m.put("bodySize", body.length());
        return m;
    }

    private static List<Object> headers(String[] lines) {
        List<Object> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) {
                out.add(Map.of("name", lines[i].substring(0, c).trim(), "value", lines[i].substring(c + 1).trim()));
            }
        }
        return out;
    }

    private static String head(byte[] raw) {
        int off = Math.max(0, Math.min(HttpBytes.bodyOffset(raw), raw.length));
        return new String(raw, 0, off, StandardCharsets.ISO_8859_1).trim();
    }

    private static String body(byte[] raw) {
        int off = Math.max(0, Math.min(HttpBytes.bodyOffset(raw), raw.length));
        return new String(raw, off, raw.length - off, StandardCharsets.UTF_8);
    }
}
