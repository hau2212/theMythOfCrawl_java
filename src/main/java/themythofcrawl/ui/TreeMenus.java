package themythofcrawl.ui;

import java.awt.Component;
import java.util.List;

import javax.swing.JPopupMenu;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.GroupCluster;
import themythofcrawl.model.RoleScopes;
import themythofcrawl.place.PlacementBook;

/** Right-click menu of a branch tree (the API tab's, the Cây tab's and the pop-out window's). */
final class TreeMenus {
    private TreeMenus() {
    }

    static void show(MainPanel ui, BranchTree tree, Component c, int x, int y) {
        Engine engine = ui.engine;
        Node n = tree.selectedNode();
        if (n == null) {
            return;
        }
        List<ApiGroup> many = tree.selectedGroups();
        JPopupMenu m = new JPopupMenu();
        switch (n.kind) {
            case ROOT, FOLDER -> {
                String prefix = n.kind == Node.Kind.FOLDER ? (String) n.payload : "";
                String title = prefix.isEmpty() ? "Tất cả" : prefix;
                if (n.kind == Node.Kind.FOLDER) {
                    boolean isFocusedHere = tree.focusPath() != null && Hierarchy.same(tree.focusPath(), prefix);
                    if (isFocusedHere) {
                        m.add(Ui.item("Thoát chế độ Tập trung (Hiện lại toàn bộ cây)", Icons::focus,
                                () -> {
                                    if (tree == ui.api.tree) {
                                        ui.api.clearFocusBranch();
                                    } else {
                                        ui.treeTab.clearFocusBranch();
                                    }
                                }));
                    } else {
                        m.add(Ui.item("Tập trung vào nhánh \"" + Hierarchy.leaf(prefix) + "\" (Kèm nhánh con)", Icons::focus,
                                () -> {
                                    if (tree == ui.api.tree) {
                                        ui.api.setFocusBranch(prefix, true);
                                    } else {
                                        ui.treeTab.setFocusBranch(prefix, true);
                                    }
                                }));
                    }
                    m.add(Ui.item("Đặt \"" + title + "\" làm ĐÍCH cho request mới", Icons::arrowRight,
                            () -> ui.setDestination(prefix)));
                    m.add(Ui.item("Thêm mục con (Folder / Link / Button / Icon...)...", Icons::plus,
                            () -> ui.addSubfolder(prefix)));
                    m.add(Ui.item("Đổi loại & Icon nhánh \"" + Hierarchy.leaf(prefix) + "\"...", Icons::sliders,
                            () -> ui.editNodeTypeDialog(prefix)));
                    m.addSeparator();
                } else {
                    if (tree.focusPath() != null) {
                        m.add(Ui.item("Thoát chế độ Tập trung (Hiện lại toàn bộ cây)", Icons::focus,
                                () -> {
                                    if (tree == ui.api.tree) {
                                        ui.api.clearFocusBranch();
                                    } else {
                                        ui.treeTab.clearFocusBranch();
                                    }
                                }));
                        m.addSeparator();
                    }
                    m.add(Ui.item("Thêm mục con (Folder / Link / Button / Icon...)...", Icons::plus,
                            () -> ui.addSubfolder(null)));
                }
                m.add(Ui.item("Gửi (Replay) toàn bộ Nhánh \"" + title + "\"", t -> Icons.send(t.critical.text()), () -> {
                    List<themythofcrawl.core.ProxyInterceptQueue.PendingRequest> held = tree.selectedHeld();
                    int forwarded = 0;
                    for (var pr : held) {
                        if (engine.proxyQueue().resolve(pr.id(),
                                themythofcrawl.core.ProxyInterceptQueue.ResolutionKind.FORWARD, pr.originalRequest())) {
                            forwarded++;
                        }
                    }
                    if (!many.isEmpty()) {
                        ui.send(many);
                    }
                    if (forwarded > 0) {
                        ui.syncHeader();
                        ui.flash("Đã gửi lên Server " + forwarded + " request chờ duyệt"
                                + (!many.isEmpty() ? " + " + many.size() + " API" : "") + ".");
                    }
                }));
                if (n.kind == Node.Kind.FOLDER) {
                    m.addSeparator();
                    ui.placeItems(m, many);
                    m.addSeparator();
                    m.add(Ui.item("Đổi tên Nhánh \"" + title + "\" (Cập nhật cả cấp con)...", Icons::edit,
                            () -> ui.renameBranchDialog(prefix)));
                    m.add(Ui.item("Đẩy Nhánh \"" + title + "\" sang Burp Repeater", Icons::repeater, () -> ui.push(many)));
                    m.add(Ui.item("Đẩy / Gửi Nhánh \"" + title + "\" có chọn Role...", Icons::target,
                            () -> ui.pushDialog(false, many, "Nhánh \"" + title + "\"")));
                    m.add(Ui.item("Copy path nhánh \"" + title + "\"", t -> Icons.copy(t.muted), () -> {
                        Ui.copyToClipboard(prefix);
                        ui.flash("Đã copy path nhánh: " + prefix);
                    }));
                    m.add(Ui.item("Copy STT + Path của các API trong nhánh", t -> Icons.copy(t.muted), () -> ui.copy(many)));
                    m.add(Ui.item("Bật / Tắt Role cho nhánh \"" + title + "\"...", Icons::sliders,
                            () -> ui.roleScopeDialog(false, RoleScopes.Scope.BRANCH, List.of(prefix), null, null)));
                    boolean branchWait = engine.actionEngine().isBranchInterceptWait(prefix)
                            && engine.actionEngine().isInterceptWaitEnabled();
                    m.add(Ui.item(branchWait
                                    ? "Tắt Chặn chờ duyệt (Đợi tôi bấm Gửi) cho nhánh \"" + Hierarchy.leaf(prefix) + "\""
                                    : "Bật Chặn chờ duyệt (Đợi tôi bấm Gửi) cho nhánh \"" + Hierarchy.leaf(prefix) + "\"",
                            t -> Icons.strategyShield(branchWait ? t.danger.text() : t.accent.text(), 14),
                            () -> {
                                boolean next = !branchWait;
                                engine.toggleBranchInterceptWait(prefix, next);
                                ui.syncHeader();
                                ui.flash(next
                                        ? "Đã BẬT Chặn chờ duyệt cho nhánh \"" + prefix + "\" — request khớp sẽ hiện ngay trên Cây ở trạng thái [CHỜ DUYỆT]!"
                                        : "Đã TẮT Chặn chờ duyệt cho nhánh \"" + prefix + "\"");
                            }));
                    m.add(Ui.item("Luật chặn cho nhánh \"" + Hierarchy.leaf(prefix) + "\" (CRUD)...", Icons::strategyShield,
                            () -> ActionWorkbenchDialog.showForBranch(c, engine, prefix, () -> ui.tick(true))));
                    m.addSeparator();
                    m.add(Ui.item("Xóa Nhánh \"" + title + "\"...", t -> Icons.trash(t.danger.text()),
                            () -> ui.deleteBranchDialog(prefix)));
                }
            }
            case HELD_PENDING -> {
                var pr = BranchTree.heldOf(n);
                if (pr != null) {
                    m.add(Ui.item("Gửi lên Server ngay (Forward)", t -> Icons.send(t.accent.text(), 13), () -> {
                        engine.proxyQueue().resolve(pr.id(),
                                themythofcrawl.core.ProxyInterceptQueue.ResolutionKind.FORWARD, pr.originalRequest());
                        ui.syncHeader();
                        ui.flash("Đã gửi lên Server: " + pr.method() + " " + Text.shortPath(pr.path(), 40));
                    }));
                    m.add(Ui.item("Lưu nháp vào Cây & Giữ (Không đẩy Server)", t -> Icons.strategyShield(t.warning.text(), 13), () -> {
                        engine.proxyQueue().resolve(pr.id(),
                                themythofcrawl.core.ProxyInterceptQueue.ResolutionKind.HOLD_AND_SPOOF, pr.originalRequest());
                        ui.syncHeader();
                        ui.flash("Đã lưu nháp vào Cây (không gửi lên Server): " + pr.method() + " " + Text.shortPath(pr.path(), 40));
                    }));
                    m.add(Ui.item("Hủy bỏ (Drop — không gửi)", t -> Icons.stop(t.danger.text(), 13), () -> {
                        engine.proxyQueue().resolve(pr.id(),
                                themythofcrawl.core.ProxyInterceptQueue.ResolutionKind.DROP, null);
                        ui.syncHeader();
                        ui.flash("Đã hủy bỏ (Drop): " + pr.method() + " " + Text.shortPath(pr.path(), 40));
                    }));
                    m.addSeparator();
                    m.add(Ui.item("+ Cho qua luôn Endpoint này (ALLOW ưu tiên đầu) & Gửi", t -> Icons.check(t.success.text(), 13), () -> {
                        String host = pr.service() != null ? pr.service().host() : "";
                        engine.actionEngine().policyLedger().createAtTop(
                                themythofcrawl.model.ActionPolicy.quickAllowPath(host, pr.path(), List.of(pr.method()), "Cây chính"));
                        engine.saveActionPolicies();
                        engine.proxyQueue().resolve(pr.id(),
                                themythofcrawl.core.ProxyInterceptQueue.ResolutionKind.FORWARD, pr.originalRequest());
                        ui.syncHeader();
                        ui.flash("Đã thêm ngoại lệ ALLOW & gửi lên Server!");
                    }));
                    m.add(Ui.item("Mở Hàng chờ Chặn chi tiết...", Icons::strategyShield,
                            () -> ProxyInterceptDialog.showFloating(ui)));
                }
            }
            case APIS -> {
                String branch = (String) n.payload;
                String title = Hierarchy.leaf(branch);
                m.add(Ui.item("Gửi " + many.size() + " API trực tiếp của \"" + title + "\"", t -> Icons.send(t.critical.text()),
                        () -> ui.send(many)));
                m.add(Ui.item("Đẩy " + many.size() + " API sang Burp Repeater", Icons::repeater, () -> ui.push(many)));
                m.add(Ui.item("Đẩy / Gửi có chọn Role...", Icons::target,
                        () -> ui.pushDialog(false, many, "API của nhánh \"" + title + "\"")));
                m.add(Ui.item("Copy STT + Path của " + many.size() + " API", t -> Icons.copy(t.muted), () -> ui.copy(many)));
                m.add(Ui.item("Bật / Tắt Role cho các API này...", Icons::sliders,
                        () -> ui.roleScopeDialog(false, RoleScopes.Scope.API, null, many, null)));
                m.addSeparator();
                m.add(ui.clusterMenu(many));
                m.add(ui.tagMenu(many));
                m.addSeparator();
                ui.placeItems(m, many);
                m.addSeparator();
                m.add(Ui.item("Thêm mục con vào \"" + title + "\"...", Icons::plus, () -> ui.addSubfolder(branch)));
            }
            case CLUSTER -> {
                GroupCluster cl = (GroupCluster) n.payload;
                m.add(Ui.item(cl.collapsed() ? "Mở rộng Cụm [" + cl.name() + "]" : "Thu gọn Cụm [" + cl.name() + "]",
                        Icons::cluster, () -> {
                            engine.toggleClusterCollapsed(cl.id());
                            ui.tick(true);
                        }));
                m.add(Ui.item("Đổi tên Cụm hiển thị...", Icons::edit, () -> ui.editClusterDialog(cl)));
                m.add(Ui.item("Gỡ Cụm (giữ nguyên toàn bộ API trong nhánh)", Icons::undo, () -> {
                    engine.dissolveCluster(cl.id());
                    ui.tick(true);
                    ui.flash("Đã gỡ Cụm [" + cl.name() + "] — các API giữ nguyên vị trí");
                }));
                m.addSeparator();
                m.add(Ui.item("Gửi tất cả " + many.size() + " API trong Cụm", t -> Icons.send(t.critical.text()),
                        () -> ui.send(many)));
                m.add(Ui.item("Đẩy Cụm sang Burp Repeater", Icons::repeater, () -> ui.push(many)));
                m.add(Ui.item("Copy STT + Path của Cụm (" + many.size() + " API)", t -> Icons.copy(t.muted),
                        () -> ui.copy(many)));
                m.add(ui.tagMenu(many));
            }
            case GROUP, ORIG, ROLE -> {
                ApiGroup g = BranchTree.groupOf(n);
                themythofcrawl.model.RoleResult nodeRole = n.kind == Node.Kind.ROLE && n.payload instanceof themythofcrawl.model.RoleResult rr ? rr : null;
                List<ApiGroup> targetGs = many.isEmpty() ? List.of(g) : many;
                m.add(Ui.item(g.results().isEmpty() ? "Gửi API #" + g.gid() + " (API gốc - 0 role)"
                        : "Gửi API #" + g.gid() + " (" + g.results().size() + " Roles)", t -> Icons.send(t.critical.text()),
                        () -> ui.send(targetGs)));
                m.add(Ui.item("Sửa Request & Gửi lại (Composer)...", t -> Icons.edit(t.info.text()),
                        () -> RequestComposerDialog.showFloating(c, engine, g, nodeRole, () -> ui.tick(true))));
                m.add(ActionMenus.buildForApi(c, engine, g, nodeRole != null ? List.of(nodeRole) : null, () -> ui.tick(true)));
                m.add(Ui.item("Đánh dấu lỗi..." + (g.openBugCount() > 0 ? "   (đang có " + g.openBugCount() + ")" : ""),
                        t -> Icons.alertMark(t.danger.text(), 13), () -> BugsTab.BugMarkDialog.mark(ui, g, null,
                                nodeRole != null && !nodeRole.original() ? nodeRole.roleKey() : "", "")));
                m.add(Ui.item("Đẩy API #" + g.gid() + " sang Burp Repeater (Gọn)", Icons::repeater, () -> ui.push(g)));
                String label = engine.copyLabel(g, false);
                m.add(Ui.item("Copy STT + Path   (" + (label.length() > 60 ? label.substring(0, 60) : label) + ")",
                        t -> Icons.copy(t.muted), () -> ui.copy(targetGs)));
                m.add(Ui.item("Đẩy / Gửi API #" + g.gid() + " có chọn Role...", Icons::target,
                        () -> ui.pushDialog(false, List.of(g), Text.apiLabel(g.gid()))));
                m.add(Ui.item("Bật / Tắt Role cho API #" + g.gid() + "...", Icons::sliders,
                        () -> ui.roleScopeDialog(false, RoleScopes.Scope.API, null, targetGs, null)));
                m.addSeparator();
                m.add(ui.clusterMenu(targetGs));
                m.add(ui.tagMenu(targetGs));
                m.add(Ui.item("Xem chuỗi Gốc & Trùng (Lineage)...", Icons::dup, () -> ui.openLineage(g, c, x, y)));
                m.addSeparator();
                ui.placeItems(m, targetGs);
            }
            case PLACE -> {
                Object[] p = (Object[]) n.payload;
                ApiGroup g = (ApiGroup) p[0];
                String place = (String) p[1];
                if ("old".equals(p[2])) {
                    m.add(Ui.item("Giữ API #" + g.gid() + " ở vị trí cũ: " + place,
                            t -> Icons.check(t.success.text()), () -> engine.confirm(List.of(g), "giữ ở vị trí cũ")));
                } else {
                    m.add(Ui.item("Chuyển API #" + g.gid() + " sang nơi vừa gặp: " + place, Icons::move,
                            () -> engine.moveToPath(List.of(g), place, "chọn nơi vừa gặp")));
                }
                m.add(Ui.item("Chuyển sang đường dẫn khác...", Icons::move, () -> ui.moveDialog(List.of(g))));
                m.add(Ui.item("Xem chuỗi Gốc & Trùng (Lineage)...", Icons::dup, () -> ui.openLineage(g, c, x, y)));
                m.add(Ui.item("Lịch sử vị trí của API này", Icons::compass, () -> ui.showPlaceOf(g.bookKey())));
            }
            case GHOSTS -> m.add(Ui.item("Xem các API đã xóa trong tab Audit > Giữ chỗ", Icons::compass, () -> {
                ui.showPlaceOf(null);
                ui.audit.placeView(2);
            }));
            case GHOST -> {
                PlacementBook.Record r = (PlacementBook.Record) n.payload;
                m.add(Ui.item("Sửa vị trí giữ chỗ (" + (r.place == null ? "-" : r.place) + ")...", Icons::edit,
                        () -> ui.audit.editPlace(r)));
                m.add(Ui.item("Cho phép bắt lại ngay (xóa khóa chống trùng)", Icons::refresh,
                        () -> ui.flash("Đã xóa " + engine.allowRecapture(r.key) + " khóa chống trùng")));
                m.add(Ui.item("Quên giữ chỗ (lần sau bắt lại sẽ vào đích hiện tại)", Icons::error, () -> engine.forgetPlace(r.key)));
                m.add(Ui.item("Lịch sử trong tab Audit", Icons::compass, () -> ui.showPlaceOf(r.key)));
            }
            default -> {
                return;
            }
        }
        m.show(c, x, y);
    }
}
