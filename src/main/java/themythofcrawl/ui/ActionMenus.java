package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.Config;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Blob;
import themythofcrawl.model.RoleResult;
import themythofcrawl.ui.theme.Theme;

/**
 * Streamlined Action API right-click &amp; toolbar menu v2.1:
 * replaces the legacy 18-item cryptic PP1..PP4 menu with 5 crystal-clear actions
 * backed by the visual 4-tab {@link ActionWorkbenchDialog}.
 */
public final class ActionMenus {
    private ActionMenus() {
    }

    /**
     * Concise right-click submenu for any API (Tree, API Table, Role Table, History).
     */
    public static JMenu buildForApi(Component parent, Engine engine, ApiGroup g,
                                    Collection<RoleResult> explicitTargets, Runnable onAfterAction) {
        Theme th = Theme.current();
        String methodTag = g != null ? g.method() : "API";
        JMenu root = new JMenu("Bảo vệ dữ liệu & Đổi ID [" + methodTag + "]");
        root.setIcon(Icons.workbench(th.accent.text(), 14));
        if (g == null) {
            JMenuItem none = new JMenuItem("(Chưa chọn API)");
            none.setEnabled(false);
            root.add(none);
            return root;
        }
        populateApiActionItems(root, parent, engine, g, explicitTargets, onAfterAction);
        return root;
    }

    /**
     * Populates 5 clean, jargon-free Action items into a JMenu or JPopupMenu.
     */
    public static void populateApiActionItems(Component menuContainer, Component parent, Engine engine, ApiGroup g,
                                              Collection<RoleResult> explicitTargets, Runnable onAfterAction) {
        Theme th = Theme.current();
        Collection<RoleResult> targets = (explicitTargets == null || explicitTargets.isEmpty())
                ? engine.defaultTargets(g, true) : explicitTargets;
        byte[] origBytes = Blob.bytesOf(g.origRequest());
        List<String> detectedIds = engine.actionEngine().detectIds(g.path(), origBytes);
        String currentId = detectedIds.isEmpty() ? null : detectedIds.get(0);
        String resource = ActionEngine.extractResourceKey(g.path());
        List<String> liveIds = engine.actionEngine().getAvailableLiveIds(g.path(), currentId);
        boolean hasSnap = g.hasFirstOkSnapshot();
        boolean epHeld = engine.actionEngine().isEndpointProxyHold(g.method(), g.path());

        // Compact status header
        String idSummary = currentId != null
                ? "ID [" + currentId + "]" + (engine.actionEngine().isConsumed(currentId) ? " · Đã tiêu thụ" : " · Còn sống")
                : "Không thấy ID tĩnh";
        JMenuItem info = new JMenuItem(Text.apiLabel(g.gid()) + " · " + resource + " · " + idSummary);
        info.setEnabled(false);
        addItem(menuContainer, info);
        addSep(menuContainer);

        // 1. Open visual 6-tab Action Workbench (Strategy + Ledger + Audit + Pool + Chain + Lock200)
        JMenuItem openWb = new JMenuItem("Bảo vệ dữ liệu cho API này (Action Workbench ở mục Nâng cao)...",
                Icons.workbench(th.accent.text(), 14));
        openWb.addActionListener(Ui.safe(e -> DataProtectionDialog.show(parent, engine, g, onAfterAction)));
        addItem(menuContainer, openWb);

        // 2. Quick Request Composer & Live Replay
        RoleResult initialRole = (explicitTargets != null && !explicitTargets.isEmpty())
                ? explicitTargets.iterator().next() : null;
        JMenuItem composerItem = new JMenuItem("Sửa Request & Gửi lại (Composer)...", Icons.edit(th.info.text()));
        composerItem.addActionListener(Ui.safe(e -> RequestComposerDialog.showFloating(parent, engine, g, initialRole, onAfterAction)));
        addItem(menuContainer, composerItem);

        // 3. Quick send with next live ID
        JMenuItem quickSwap = new JMenuItem(
                !liveIds.isEmpty()
                        ? "Gửi nhanh với ID sống kế tiếp (-> " + liveIds.get(0) + ")"
                        : "Gửi nhanh với ID sống kế tiếp (Kho đang trống)",
                Icons.strategySwap(th.success.text(), 14));
        quickSwap.setEnabled(currentId != null && !liveIds.isEmpty());
        quickSwap.addActionListener(Ui.safe(e -> {
            engine.sendWithNextLiveId(g, targets);
            if (onAfterAction != null) onAfterAction.run();
        }));
        addItem(menuContainer, quickSwap);

        // 4. Pick another ID from Live Pool or manual swap
        JMenu pickMenu = new JMenu("Chọn ID khác từ Kho (" + liveIds.size() + " ID sống) & Gửi");
        pickMenu.setIcon(Icons.idPool(th.info.text(), 14));
        if (!liveIds.isEmpty()) {
            int limit = Math.min(liveIds.size(), 15);
            for (int i = 0; i < limit; i++) {
                String cand = liveIds.get(i);
                JMenuItem idItem = new JMenuItem(currentId != null
                        ? "Đổi [" + currentId + "] -> [" + cand + "] & Gửi"
                        : "Dùng ID [" + cand + "] & Gửi");
                idItem.addActionListener(Ui.safe(e -> {
                    engine.replaceIdAndSend(g, currentId, cand, true, targets);
                    if (onAfterAction != null) onAfterAction.run();
                }));
                pickMenu.add(idItem);
            }
            pickMenu.addSeparator();
        }
        JMenuItem manualItem = new JMenuItem("Nhập ID thủ công & Gửi...", Icons.edit(th.fg));
        manualItem.addActionListener(Ui.safe(e -> showManualIdReplaceDialog(parent, engine, g, detectedIds, liveIds, targets, onAfterAction)));
        pickMenu.add(manualItem);
        JMenuItem openPoolItem = new JMenuItem("Mở Kho ID Sống & Nạp hàng loạt...", Icons.idPool(th.success.text(), 14));
        openPoolItem.addActionListener(Ui.safe(e -> ActionWorkbenchDialog.show(parent, engine, g, targets,
                ActionWorkbenchDialog.TAB_POOL, onAfterAction)));
        pickMenu.add(openPoolItem);
        addItem(menuContainer, pickMenu);

        // 5. Producer Chain
        JMenuItem chainItem = new JMenuItem("Chuỗi Tạo Mới -> Hành Động (API tạo mới)...", Icons.strategyChain(th.accent.text(), 14));
        chainItem.addActionListener(Ui.safe(e -> ActionWorkbenchDialog.show(parent, engine, g, targets,
                ActionWorkbenchDialog.TAB_CHAIN, onAfterAction)));
        addItem(menuContainer, chainItem);

        // 6. Lock 200 OK & Replay
        JMenuItem lockItem = new JMenuItem(
                hasSnap ? "Dùng lại kết quả 200 cũ & Chọn cách gửi lại (Snapshot " + g.firstOkStatus() + ")..."
                        : "Dùng lại kết quả 200 cũ & Chọn cách gửi lại...",
                Icons.strategySnapshot(th.info.text(), 14));
        lockItem.addActionListener(Ui.safe(e -> ActionWorkbenchDialog.show(parent, engine, g, targets,
                ActionWorkbenchDialog.TAB_LOCK200, onAfterAction)));
        addItem(menuContainer, lockItem);

        // 7. Proxy Intercept / Policy Control & Reversible Release submenu (total items == 9 <= 9!)
        boolean epWait = engine.actionEngine().isEndpointInterceptWait(g.method(), g.path())
                && engine.actionEngine().isInterceptWaitEnabled();
        boolean hasOverride = g.actionModeOverride() != null || epHeld || epWait;
        JMenu proxyMenu = new JMenu(epWait ? "Chặn Proxy / Chính sách [ĐANG CHẶN]"
                : (hasOverride ? "Chặn Proxy / Chính sách [ĐANG CAN THIỆP]" : "Chặn Proxy / Chính sách cho API này"));
        proxyMenu.setIcon(Icons.strategyShield(epWait ? th.danger.text() : th.warning.text(), 14));

        JCheckBoxMenuItem waitEpItem = new JCheckBoxMenuItem("Chặn chờ duyệt (Đợi tôi bấm Gửi) cho API này", epWait);
        waitEpItem.setIcon(Icons.strategyShield(th.danger.text(), 14));
        waitEpItem.addActionListener(Ui.safe(e -> {
            boolean next = waitEpItem.isSelected();
            engine.toggleEndpointInterceptWait(g, next);
            if (next) {
                MainPanel.openInterceptQueue(engine);
            }
            if (onAfterAction != null) onAfterAction.run();
        }));
        proxyMenu.add(waitEpItem);

        JCheckBoxMenuItem holdEpItem = new JCheckBoxMenuItem("Giữ ở Proxy cho endpoint này (200 ảo / Không gửi Server)", epHeld);
        holdEpItem.setIcon(Icons.strategyShield(th.warning.text(), 14));
        holdEpItem.addActionListener(Ui.safe(e -> {
            engine.toggleEndpointProxyHold(g, holdEpItem.isSelected());
            if (onAfterAction != null) onAfterAction.run();
        }));
        proxyMenu.add(holdEpItem);

        proxyMenu.addSeparator();
        JMenuItem releaseApiItem = new JMenuItem("Tắt chặn API này (Nguyên bản — Server trả thật)",
                Icons.undo(th.success.text()));
        releaseApiItem.addActionListener(Ui.safe(e -> {
            engine.releaseApiToNone(g, false);
            if (onAfterAction != null) onAfterAction.run();
        }));
        proxyMenu.add(releaseApiItem);

        JMenuItem openLedgerItem = new JMenuItem("Mở Luật chặn cho API này...", Icons.sliders(th.accent.text()));
        openLedgerItem.addActionListener(Ui.safe(e -> ActionWorkbenchDialog.show(parent, engine, g, targets,
                ActionWorkbenchDialog.TAB_LEDGER, onAfterAction)));
        proxyMenu.add(openLedgerItem);

        JMenuItem openQueueItem = new JMenuItem("Mở Hàng chờ Chặn Proxy trực tiếp...", Icons.strategyShield(th.accent.text(), 14));
        openQueueItem.addActionListener(Ui.safe(e -> MainPanel.openInterceptQueue(engine)));
        proxyMenu.add(openQueueItem);
        addItem(menuContainer, proxyMenu);
    }

    /**
     * Streamlined toolbar dropdown for Action API controls.
     */
    public static void populateToolbarDropdown(JPopupMenu menu, Component parent, Engine engine,
                                               Supplier<ApiGroup> activeGroupSupplier, Runnable onAfterAction) {
        Theme th = Theme.current();
        Config cfg = engine.config();
        ApiGroup active = activeGroupSupplier != null ? activeGroupSupplier.get() : null;

        JMenuItem gate = new JMenuItem("Cổng chặn Proxy: chặn host / path / method nào, chặn ra sao...",
                Icons.strategyShield(th.danger.text(), 14));
        gate.addActionListener(Ui.safe(e -> {
            MainPanel mp = MainPanel.forEngine(engine);
            if (mp != null) {
                ProxyGateDialog.show(mp);
            }
        }));
        menu.add(gate);
        JMenuItem protect = new JMenuItem("Bảo vệ dữ liệu: 2 câu hỏi + danh sách luật + Thử luật...",
                Icons.strategyShield(th.accent.text(), 14));
        protect.addActionListener(Ui.safe(e -> DataProtectionDialog.show(parent, engine, active, onAfterAction)));
        menu.add(protect);
        JMenuItem openWorkbench = new JMenuItem("Nâng cao: Action Workbench (Kho ID sống, Chuỗi tạo mới, Dò giao thức)...",
                Icons.workbench(th.accent.text(), 14));
        openWorkbench.addActionListener(Ui.safe(e -> ActionWorkbenchDialog.show(parent, engine, active, null,
                ActionWorkbenchDialog.TAB_STRATEGY, onAfterAction)));
        menu.add(openWorkbench);

        int ledgerActive = engine.actionEngine().policyLedger().activeCount();
        JMenuItem openLedger = new JMenuItem("Luật chặn (" + ledgerActive + " đang bật)...",
                Icons.sliders(th.accent.text()));
        openLedger.addActionListener(Ui.safe(e -> ActionWorkbenchDialog.show(parent, engine, active, null,
                ActionWorkbenchDialog.TAB_LEDGER, onAfterAction)));
        menu.add(openLedger);

        int auditedCount = engine.actionEngine().gateAuditLog().size();
        JMenuItem openAudit = new JMenuItem("Dò Giao thức Action & Mô phỏng hàng loạt (" + auditedCount + " đã ghi)...",
                Icons.playTest(th.info.text(), 14));
        openAudit.addActionListener(Ui.safe(e -> ActionWorkbenchDialog.show(parent, engine, active, null,
                ActionWorkbenchDialog.TAB_PROTOCOL_AUDIT, onAfterAction)));
        menu.add(openAudit);

        if (active != null) {
            JMenuItem openComposer = new JMenuItem("Sửa Request & Gửi lại: " + Text.apiLabel(active.gid()) + " [" + active.method() + "]...",
                    Icons.edit(th.info.text()));
            openComposer.addActionListener(Ui.safe(e -> RequestComposerDialog.showFloating(parent, engine, active, null, onAfterAction)));
            menu.add(openComposer);
        }

        menu.addSeparator();
        Map<String, List<String>> poolMap = engine.actionEngine().getAllResourceLiveIds();
        int totalIds = poolMap.values().stream().mapToInt(List::size).sum();
        JMenuItem openPool = new JMenuItem("Quản lý Kho ID Sống (" + totalIds + " ID · " + poolMap.size() + " tài nguyên)...",
                Icons.idPool(th.success.text(), 14));
        openPool.addActionListener(Ui.safe(e -> ActionWorkbenchDialog.show(parent, engine, active, null,
                ActionWorkbenchDialog.TAB_POOL, onAfterAction)));
        menu.add(openPool);

        JMenuItem openChain = new JMenuItem("Thiết lập Chuỗi Tạo Mới -> Hành Động...", Icons.strategyChain(th.accent.text(), 14));
        openChain.addActionListener(Ui.safe(e -> ActionWorkbenchDialog.show(parent, engine, active, null,
                ActionWorkbenchDialog.TAB_CHAIN, onAfterAction)));
        menu.add(openChain);

        menu.addSeparator();
        JCheckBoxMenuItem holdAll = new JCheckBoxMenuItem("Chặn tại Proxy cho mọi Action API khi Crawl", cfg.holdActionAtProxy);
        holdAll.setIcon(Icons.strategyShield(th.warning.text(), 14));
        holdAll.addActionListener(Ui.safe(e -> {
            Config next = engine.config().copy();
            next.holdActionAtProxy = holdAll.isSelected();
            engine.applyConfig(next);
            if (onAfterAction != null) onAfterAction.run();
        }));
        menu.add(holdAll);

        JMenuItem resetAllItem = new JMenuItem("Tắt chặn toàn bộ — về Nguyên bản (Reset All)",
                Icons.undo(th.danger.text()));
        resetAllItem.addActionListener(Ui.safe(e -> {
            engine.resetAllActionControls();
            if (onAfterAction != null) onAfterAction.run();
        }));
        menu.add(resetAllItem);

        if (active != null) {
            menu.addSeparator();
            JMenu activeSub = buildForApi(parent, engine, active, null, onAfterAction);
            activeSub.setText("Thao tác ngay: " + Text.apiLabel(active.gid()) + " [" + active.method() + " "
                    + Text.shortPath(active.path(), 24) + "]");
            menu.add(activeSub);
        }
    }

    /** Quick modal for typing a custom ID to replace &amp; send immediately. */
    public static void showManualIdReplaceDialog(Component parent, Engine engine, ApiGroup g,
                                                 List<String> detectedIds, List<String> liveIds,
                                                 Collection<RoleResult> targets, Runnable onAfterAction) {
        Window win = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dlg = new JDialog(Ui.ownerWindow(win), "Đổi ID & Gửi ngay — " + Text.apiLabel(g.gid()) + " " + g.method() + " " + g.path(),
                JDialog.ModalityType.APPLICATION_MODAL);
        Theme th = Theme.current();

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBackground(th.surface);
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
        form.add(new JLabel("ID cũ cần thay trong Request:"), gc);
        JComboBox<String> oldIdCombo = new JComboBox<>(detectedIds.toArray(new String[0]));
        oldIdCombo.setEditable(true);
        if (detectedIds.isEmpty()) {
            oldIdCombo.setSelectedItem("");
        }
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(oldIdCombo, gc);

        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0;
        form.add(new JLabel("ID mới (hoặc chọn từ Kho ID sống):"), gc);
        JComboBox<String> newIdCombo = new JComboBox<>(liveIds.toArray(new String[0]));
        newIdCombo.setEditable(true);
        if (!liveIds.isEmpty()) {
            newIdCombo.setSelectedIndex(0);
        }
        gc.gridx = 1; gc.weightx = 1.0;
        form.add(newIdCombo, gc);

        JCheckBox updateGroupChk = new JCheckBox("Cập nhật ID mới làm trạng thái chính của API này", true);
        updateGroupChk.setOpaque(false);
        gc.gridx = 0; gc.gridy = 2; gc.gridwidth = 2;
        form.add(updateGroupChk, gc);

        JCheckBox alsoAddToPoolChk = new JCheckBox("Lưu ID mới vào Kho ID sống của '"
                + ActionEngine.extractResourceKey(g.path()) + "'", true);
        alsoAddToPoolChk.setOpaque(false);
        gc.gridy = 3;
        form.add(alsoAddToPoolChk, gc);

        root.add(form, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        JButton cancelBtn = Ui.button("Hủy", dlg::dispose);
        JButton sendBtn = Ui.accent("Thay ID & Gửi ngay", t -> Icons.send(t.accent.onSolid()), t -> t.accent, () -> {
            Object oldObj = oldIdCombo.getEditor().getItem();
            Object newObj = newIdCombo.getEditor().getItem();
            String oldId = oldObj != null ? oldObj.toString().trim() : "";
            String newId = newObj != null ? newObj.toString().trim() : "";
            if (newId.isEmpty()) {
                JOptionPane.showMessageDialog(Ui.ownerWindow(dlg), "Vui lòng nhập hoặc chọn ID mới.", "Thiếu ID mới", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (alsoAddToPoolChk.isSelected()) {
                engine.actionEngine().addLiveId(ActionEngine.familyKey(g.path()), newId, "nhập tay", g.method() + " " + g.path(), 0);
            }
            engine.replaceIdAndSend(g, oldId, newId, updateGroupChk.isSelected(), targets);
            dlg.dispose();
            if (onAfterAction != null) onAfterAction.run();
        });
        actions.add(cancelBtn);
        actions.add(sendBtn);
        root.add(actions, BorderLayout.SOUTH);

        dlg.setContentPane(root);
        Theme.track(dlg);
        dlg.pack();
        dlg.setMinimumSize(new Dimension(520, 210));
        dlg.setLocationRelativeTo(win);
        dlg.setVisible(true);
    }

    private static void addItem(Component container, JMenuItem item) {
        if (container instanceof JMenu m) {
            m.add(item);
        } else if (container instanceof JPopupMenu p) {
            p.add(item);
        }
    }

    private static void addSep(Component container) {
        if (container instanceof JMenu m) {
            m.addSeparator();
        } else if (container instanceof JPopupMenu p) {
            p.addSeparator();
        }
    }
}
