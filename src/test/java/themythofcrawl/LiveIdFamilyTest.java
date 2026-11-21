package themythofcrawl;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import themythofcrawl.core.ActionEngine;

/** V_8.2: the ID pool only keeps IDs of the SAME API family, only from successful responses. */
class LiveIdFamilyTest {
    static byte[] resp(int st, String body) {
        return ("HTTP/1.1 " + st + " X\r\nContent-Type: application/json\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void familyKeyGroupsCreatorAndUsers() {
        assertEquals("/api/a/b/c", ActionEngine.familyKey("/api/a/b/c"));
        assertEquals("/api/a/b/c", ActionEngine.familyKey("/api/a/b/c/15"));
        assertEquals("/api/orders", ActionEngine.familyKey("/api/orders/15/cancel"));
        assertEquals("/api/orders", ActionEngine.familyKey("/api/orders/delete/15"));
        assertEquals("/api/orders/{id}/items", ActionEngine.familyKey("/api/orders/15/items/3?x=1"));
    }

    @Test
    void onlySameFamilyAndOnly2xx() {
        ActionEngine ae = new ActionEngine();
        // another family's list must not leak into /api/a/b/c
        ae.harvest("GET", "/api/users", null, resp(200, "{\"data\":[{\"id\":501,\"orderId\":9}]}"), 200, 1);
        // failed create gives nothing
        ae.harvest("POST", "/api/a/b/c", null, resp(400, "{\"id\":7}"), 400, 2);
        assertTrue(ae.getAvailableLiveIds("/api/a/b/c/15", "15").isEmpty(), "no ID from other families / failed calls");
        // successful create in the family
        ae.harvest("POST", "/api/a/b/c", null, resp(201, "{\"id\":42,\"ownerId\":501}"), 201, 3);
        assertEquals(List.of("42"), ae.getAvailableLiveIds("/api/a/b/c/15", "15"), "only the created id, not ownerId");
        assertEquals("tạo mới", ae.liveIdSource("/api/a/b/c", "42").how());
        assertEquals(Integer.valueOf(3), ae.findProducerGid("/api/a/b/c/15"));
        // list of the family adds its own ids (c_id / cId style too), created ids stay first
        ae.harvest("GET", "/api/a/b/c", null, resp(200, "{\"items\":[{\"id\":43},{\"c_id\":44},{\"userId\":999}]}"), 200, 4);
        assertEquals(List.of("42", "44", "43"), ae.getAvailableLiveIds("/api/a/b/c/15", "15"));
        // a successful DELETE uses the id up
        ae.harvest("DELETE", "/api/a/b/c/42", null, resp(204, ""), 204, 5);
        assertFalse(ae.getAvailableLiveIds("/api/a/b/c/15", "15").contains("42"));
        assertEquals(List.of("501"), ae.getAvailableLiveIds("/api/users/1", "1"), "users list gives users ids, never orderId");
    }
}
