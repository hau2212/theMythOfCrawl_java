package themythofcrawl.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * V_8.2: one bug found on an API ("Lỗi"), so testers can mark APIs, track them in the Lỗi tab and report.
 * Types follow the OWASP API Security Top 10 (2023) with short Vietnamese names.
 */
public record BugMark(String id, Type type, Severity severity, Status status, String role, String note, String by,
                      long atMs) {

    /** Loại lỗi — short name for the table, OWASP code and one-line meaning for the hover card. */
    public enum Type {
        IDOR("Dữ liệu người khác", "API1 · BOLA / IDOR", "Đổi ID là xem / sửa / xóa được đối tượng của người khác"),
        PRIVILEGE("Vượt quyền", "API5 · BFLA", "Role thấp gọi được chức năng chỉ dành cho role cao (admin...)"),
        FIELD("Trường cấm", "API3 · BOPLA", "Lộ trường nhạy cảm hoặc sửa được trường không được phép (role, giá...)"),
        AUTH("Đăng nhập", "API2 · Broken Authentication", "Token / phiên yếu, không hết hạn, bỏ qua đăng nhập"),
        RATE("Không giới hạn", "API4 · Resource Consumption", "Gọi liên tục không bị chặn, không phân trang, tải nặng"),
        FLOW("Nghiệp vụ", "API6 · Business Flow", "Lạm dụng luồng nghiệp vụ: mua giá 0, dùng mã nhiều lần..."),
        SSRF("SSRF", "API7 · SSRF", "Server gọi tới URL do người dùng đưa vào"),
        CONFIG("Cấu hình", "API8 · Misconfiguration", "CORS, header bảo mật, lỗi lộ stack trace, debug bật"),
        LEAK("Lộ thông tin", "Information disclosure", "Lộ email, số điện thoại, token, phiên bản... không cần thiết"),
        OTHER("Khác", "—", "Lỗi khác");

        public final String label;
        public final String code;
        public final String explain;

        Type(String label, String code, String explain) {
            this.label = label;
            this.code = code;
            this.explain = explain;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Mức độ. */
    public enum Severity {
        CRITICAL("Nghiêm trọng", "danger"),
        HIGH("Cao", "critical"),
        MEDIUM("Trung bình", "warn"),
        LOW("Thấp", "info"),
        INFO("Thông tin", "neutral");

        public final String label;
        public final String colorKey;

        Severity(String label, String colorKey) {
            this.label = label;
            this.colorKey = colorKey;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Trạng thái xử lý. */
    public enum Status {
        SUSPECT("Nghi ngờ"),
        CONFIRMED("Đã xác nhận"),
        REPORTED("Đã báo"),
        FIXED("Đã sửa"),
        IGNORED("Bỏ qua");

        public final String label;

        Status(String label) {
            this.label = label;
        }

        public boolean open() {
            return this == SUSPECT || this == CONFIRMED || this == REPORTED;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public BugMark {
        id = Text.blank(id) ? "bug-" + UUID.randomUUID().toString().substring(0, 8) : id;
        type = type == null ? Type.OTHER : type;
        severity = severity == null ? Severity.MEDIUM : severity;
        status = status == null ? Status.SUSPECT : status;
        role = Text.nz(role).trim();
        note = Text.nz(note).trim();
        by = Text.nz(by).trim();
        atMs = atMs > 0 ? atMs : System.currentTimeMillis();
    }

    public static BugMark create(Type type, Severity severity, Status status, String role, String note, String by) {
        return new BugMark(null, type, severity, status, role, note, by, 0L);
    }

    public BugMark with(Type t, Severity s, Status st, String r, String n) {
        return new BugMark(id, t, s, st, r, n, by, atMs);
    }

    /** "Dữ liệu người khác · Cao · Nghi ngờ". */
    public String shortText() {
        return type.label + " · " + severity.label + " · " + status.label;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type.name());
        m.put("severity", severity.name());
        m.put("status", status.name());
        m.put("role", role);
        m.put("note", note);
        m.put("by", by);
        m.put("at", atMs);
        return m;
    }

    public static BugMark fromMap(Map<String, Object> m) {
        return new BugMark(Json.str(m, "id", null), parse(Type.class, Json.str(m, "type", ""), Type.OTHER),
                parse(Severity.class, Json.str(m, "severity", ""), Severity.MEDIUM),
                parse(Status.class, Json.str(m, "status", ""), Status.SUSPECT), Json.str(m, "role", ""),
                Json.str(m, "note", ""), Json.str(m, "by", ""), (long) Json.num(m, "at", 0));
    }

    private static <E extends Enum<E>> E parse(Class<E> c, String raw, E fallback) {
        try {
            return Enum.valueOf(c, raw);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
