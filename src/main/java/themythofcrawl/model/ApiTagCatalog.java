package themythofcrawl.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import themythofcrawl.core.Json;
import themythofcrawl.core.Text;

/**
 * Danh mục Tag phân loại API (đặc biệt là tag {@code REFRESH} cho các API GET tự động tải lại nội dung
 * sau mỗi hành động POST/PUT/DELETE), hỗ trợ thêm / sửa / xóa tùy biến.
 */
public final class ApiTagCatalog {
    public static final String TAG_REFRESH = "REFRESH";
    public static final String TAG_INIT = "INIT";
    public static final String TAG_POLLING = "POLLING";
    public static final String TAG_ACTION_CORE = "ACTION-CORE";
    /** V_8.2: set automatically while an API has an open bug mark. */
    public static final String TAG_BUG = "BUG";

    public record TagDef(
            String id,
            String label,
            String colorKey,
            String description,
            boolean builtIn
    ) {
        public TagDef {
            id = normalizeId(id);
            label = Text.blank(label) ? id : label.trim();
            colorKey = Text.blank(colorKey) ? "info" : colorKey.trim().toLowerCase(Locale.ROOT);
            description = description == null ? "" : description.trim();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", label);
            m.put("color_key", colorKey);
            m.put("description", description);
            m.put("built_in", builtIn);
            return m;
        }

        public static TagDef fromMap(Map<String, Object> m) {
            return new TagDef(
                    Json.str(m, "id", "TAG"),
                    Json.str(m, "label", "TAG"),
                    Json.str(m, "color_key", "info"),
                    Json.str(m, "description", ""),
                    Json.bool(m, "built_in", false)
            );
        }
    }

    private final Map<String, TagDef> tags = new LinkedHashMap<>();

    public ApiTagCatalog() {
        resetDefaults();
    }

    public static String normalizeId(String raw) {
        if (Text.blank(raw)) {
            return "TAG";
        }
        String s = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "-");
        return s.isEmpty() ? "TAG" : s;
    }

    public static List<TagDef> defaults() {
        return List.of(
                new TagDef(TAG_REFRESH, "REFRESH", "warn",
                        "API GET tự động tải lại dữ liệu sau mỗi hành động (POST/PUT/DELETE) — tránh làm nhiễu danh sách API mới", true),
                new TagDef(TAG_INIT, "INIT", "info",
                        "API khởi tạo / lấy cấu hình chung khi mở trang hoặc tải session", true),
                new TagDef(TAG_POLLING, "POLLING", "purple",
                        "API chạy ngầm định kỳ (heartbeat, thông báo, kiểm tra trạng thái)", true),
                new TagDef(TAG_ACTION_CORE, "ACTION-CORE", "accent",
                        "API hành động nghiệp vụ quan trọng cần ưu tiên rà soát phân quyền", true),
                new TagDef(TAG_BUG, "BUG", "danger", "API đang có lỗi được đánh dấu (xem tab Lỗi)", true)
        );
    }

    public synchronized void resetDefaults() {
        tags.clear();
        for (TagDef t : defaults()) {
            tags.put(t.id(), t);
        }
    }

    public synchronized List<TagDef> all() {
        return new ArrayList<>(tags.values());
    }

    public synchronized TagDef byId(String id) {
        if (Text.blank(id)) {
            return null;
        }
        return tags.get(normalizeId(id));
    }

    public synchronized void upsert(TagDef t) {
        if (t == null) {
            return;
        }
        tags.put(t.id(), t);
    }

    public synchronized boolean remove(String id) {
        if (Text.blank(id)) {
            return false;
        }
        String k = normalizeId(id);
        if (TAG_REFRESH.equals(k)) {
            return false;
        }
        return tags.remove(k) != null;
    }

    public synchronized String toJson() {
        List<Object> list = new ArrayList<>();
        for (TagDef t : tags.values()) {
            list.add(t.toMap());
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("tags", list);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public synchronized void loadJson(String raw) {
        resetDefaults();
        if (Text.blank(raw)) {
            return;
        }
        try {
            Map<String, Object> root = Json.parseObject(raw);
            List<Object> list = Json.list(root, "tags");
            if (list.isEmpty()) {
                return;
            }
            tags.clear();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    TagDef t = TagDef.fromMap((Map<String, Object>) m);
                    tags.put(t.id(), t);
                }
            }
            if (!tags.containsKey(TAG_REFRESH)) {
                tags.put(TAG_REFRESH, defaults().get(0));
            }
        } catch (RuntimeException ignored) {
            resetDefaults();
        }
    }
}
