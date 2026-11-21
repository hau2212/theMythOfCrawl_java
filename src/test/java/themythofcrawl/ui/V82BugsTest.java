package themythofcrawl.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import themythofcrawl.app.Engine;
import themythofcrawl.app.MemoryStorage;
import themythofcrawl.app.Prefs;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.ApiTagCatalog;
import themythofcrawl.model.BugMark;
import themythofcrawl.store.FileStorage;
import themythofcrawl.store.Workspaces;

/** V_8.2: bug marks on APIs, BUG tag sync, Lỗi tab counts / filters, persistence. */
class V82BugsTest {

    @Test
    void markEditRemoveAndPersist(@TempDir Path dir) throws Exception {
        Prefs prefs = Prefs.memory();
        Workspaces ws = new Workspaces(prefs, Prefs.memory(), dir.resolve("x.jar").toString());
        ws.setRoot(dir);
        Engine e = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        FileStorage st = ws.start("p", "Project");
        e.open(st);
        e.roles().setAll(V70MultiRoleTeamTest.roles());
        ApiGroup g = e.process(V70MultiRoleTeamTest.msg("GET", "/api/admin/users", "ADMIN_TOKEN"), "Proxy", true,
                e.activeBranch().id());
        BugMark b = BugMark.create(BugMark.Type.PRIVILEGE, BugMark.Severity.HIGH, BugMark.Status.CONFIRMED, "02_User",
                "User gọi được danh sách admin", "A");
        e.putBug(g, b);
        assertTrue(g.hasTag(ApiTagCatalog.TAG_BUG), "open bug -> BUG tag");
        assertEquals(1, e.bugRows().size());
        assertTrue(e.bugsJson().contains("API5"));

        e.putBug(g, b.with(b.type(), b.severity(), BugMark.Status.FIXED, b.role(), b.note()));
        assertEquals(1, e.bugRows().size(), "editing keeps one mark");
        assertFalse(g.hasTag(ApiTagCatalog.TAG_BUG), "fixed -> no open bug -> tag removed");

        e.putBug(g, BugMark.create(BugMark.Type.IDOR, BugMark.Severity.CRITICAL, BugMark.Status.SUSPECT, "", "", "A"));
        e.flush();
        Engine e2 = V70MultiRoleTeamTest.engine(new V70MultiRoleTeamTest.Server(), "A");
        e2.open(ws.open(st.dir()));
        ApiGroup g2 = e2.group(g.gid());
        assertNotNull(g2);
        assertEquals(2, g2.bugs().size(), "bug marks survive reopening the workspace");
        assertEquals(1, g2.openBugCount());

        SwingUtilities.invokeAndWait(() -> {
            MainPanel ui = new MainPanel(e, ws, new UiSmokeTest.HeadlessHost(), "bugs");
            try {
                ui.selectTab(ui.bugs.panel);
                ui.bugs.reload();
                assertEquals(1, ui.bugs.table.getRowCount(), "default filter shows open bugs only");
            } finally {
                ui.dispose();
            }
        });
        assertTrue(e.removeBug(g, b.id()));
        e.close();
        e2.close();
    }
}
