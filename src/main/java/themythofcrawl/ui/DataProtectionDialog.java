package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.ActionPolicyLedger;
import themythofcrawl.core.Config;
import themythofcrawl.core.DataProtection;
import themythofcrawl.model.ActionPolicy;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.ui.theme.Theme;

/**
 * V_7.0 "Bảo vệ dữ liệu": the whole Action area as two plain questions and one list of rules written
 * as sentences, with a dry-run "Thử luật" box. The old 5-tab Action Workbench stays reachable as
 * "Nâng cao" for power users.
 */
final class DataProtectionDialog {
    private DataProtectionDialog() {
    }

    static void show(Component parent, Engine engine, ApiGroup focus, Runnable onChanged) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dlg = new JDialog(Ui.ownerWindow(owner), "Bảo vệ dữ liệu khi crawl", java.awt.Dialog.ModalityType.MODELESS);
        dlg.setContentPane(build(dlg, engine, focus, onChanged));
        dlg.setSize(new Dimension(980, 720));
        dlg.setLocationRelativeTo(parent);
        Ui.fastScroll(dlg.getContentPane());
        dlg.setVisible(true);
    }

    static JPanel build(Component parent, Engine engine, ApiGroup focus, Runnable onChanged) {
        Theme th = Theme.current();
        ActionPolicyLedger ledger = engine.actionEngine().policyLedger();
        Runnable changed = () -> {
            engine.saveActionPolicies();
            engine.saveInterceptRules();
            if (onChanged != null) {
                onChanged.run();
            }
        };
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        // ---------------------------------------------------------------- legacy banner
        JPanel banner = new JPanel(new BorderLayout(8, 0));
        banner.setOpaque(true);
        banner.setBackground(th.warning.fill());
        banner.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(th.warning.border()),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        JLabel bannerText = new JLabel();
        bannerText.setForeground(th.warning.text());
        JButton migrate = Ui.solidPrimary("Gộp vào danh sách luật", t -> Icons.check(t.accent.onSolid(), 12));
        banner.add(bannerText, BorderLayout.CENTER);
        banner.add(migrate, BorderLayout.EAST);

        // ---------------------------------------------------------------- question 1 = Cổng chặn Proxy
        JPanel q1 = section("1. Khi trình duyệt gửi request — chặn ở Proxy hay cho đi? (Cổng chặn Proxy)");
        JLabel gateLine = Ui.bold(" ");
        JButton openGate = Ui.solidPrimary("Thiết lập Cổng chặn Proxy (host / path / method / cách chặn)...",
                t -> Icons.strategyShield(t.accent.onSolid(), 12));
        openGate.addActionListener(Ui.safe(e -> {
            MainPanel mp = MainPanel.forEngine(engine);
            if (mp != null) {
                ProxyGateDialog.show(mp);
            }
        }));
        q1.add(gateLine, BorderLayout.CENTER);
        q1.add(Ui.flow(FlowLayout.LEFT, openGate,
                Ui.muted("Luật riêng bên dưới (theo nhánh / API) luôn thắng cổng chặn chung.")), BorderLayout.SOUTH);
        // ---------------------------------------------------------------- rules
        JPanel rulesSec = section("Danh sách luật (đọc từ trên xuống — luật đầu tiên khớp sẽ được áp dụng):");
        DefaultListModel<ActionPolicy> rulesModel = new DefaultListModel<>();
        JList<ActionPolicy> rulesList = new JList<>(rulesModel);
        rulesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rulesList.setCellRenderer((list, value, index, sel, focusCell) -> {
            JLabel l = new JLabel((index + 1) + ". " + (value.enabled() ? "" : "[TẮT] ") + DataProtection.sentence(value)
                    + (value.hitCount() > 0 ? "   (đã khớp " + value.hitCount() + " lần)" : ""));
            l.setOpaque(true);
            l.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            l.setFont(th.baseFont);
            l.setBackground(sel ? th.selectionBg : th.surface);
            l.setForeground(sel ? th.selectionFg : (value.enabled() ? th.fg : th.muted));
            return l;
        });
        JScrollPane rulesScroll = new JScrollPane(rulesList);
        rulesScroll.setPreferredSize(new Dimension(600, 200));
        rulesSec.add(rulesScroll, BorderLayout.CENTER);
        JButton add = Ui.solidPrimary("Thêm luật...", t -> Icons.check(t.accent.onSolid(), 12));
        JButton toggle = Ui.secondaryBtn("Bật / Tắt", t -> Icons.sliders(t.fg));
        JButton up = Ui.secondaryBtn("Lên", null);
        JButton down = Ui.secondaryBtn("Xuống", null);
        JButton del = Ui.dangerBtn("Xóa", t -> Icons.error(t.danger.text(), 12), null);
        JButton undo = Ui.secondaryBtn("Hoàn tác xóa", t -> Icons.undo(t.fg));
        rulesSec.add(Ui.flow(FlowLayout.LEFT, add, toggle, up, down, del, undo), BorderLayout.SOUTH);

        // ---------------------------------------------------------------- dry run
        JPanel testSec = section("Thử luật (không gửi gì): request này sẽ bị xử lý ra sao?");
        JComboBox<String> tMethod = new JComboBox<>(new String[] {"POST", "PUT", "PATCH", "DELETE", "GET"});
        JTextField tPath = new JTextField(focus != null ? focus.path() : "/api/orders/15", 28);
        List<String> branches = new ArrayList<>(engine.knownPaths());
        java.util.Collections.sort(branches);
        JComboBox<String> tBranch = new JComboBox<>(branches.toArray(new String[0]));
        tBranch.setEditable(true);
        tBranch.setSelectedItem(focus != null ? focus.branch() : engine.activeBranch().name());
        if (focus != null) {
            tMethod.setSelectedItem(focus.method());
        }
        JLabel tResult = Ui.bold(" ");
        JButton tRun = Ui.secondaryBtn("Thử", t -> Icons.playTest(t.fg, 12));
        Runnable runTest = () -> tResult.setText(engine.explainProtection(String.valueOf(tMethod.getSelectedItem()),
                focus != null ? focus.host() : "", tPath.getText().trim(), String.valueOf(tBranch.getSelectedItem())));
        tRun.addActionListener(Ui.safe(e -> runTest.run()));
        testSec.add(Ui.flow(FlowLayout.LEFT, tMethod, tPath, Ui.muted("ở nhánh"), tBranch, tRun), BorderLayout.CENTER);
        testSec.add(tResult, BorderLayout.SOUTH);

        // ---------------------------------------------------------------- question 2
        JPanel q2 = section("2. Khi GỬI LẠI theo role mà ID trong request đã bị xóa / dùng hết:");
        JComboBox<DataProtection.Resend> defResend = new JComboBox<>(DataProtection.Resend.values());
        defResend.setSelectedItem(DataProtection.Resend.of(engine.config().actionMode));
        defResend.addActionListener(Ui.safe(e -> {
            Config c = engine.config().copy();
            c.actionMode = ((DataProtection.Resend) defResend.getSelectedItem()).mode;
            engine.applyConfig(c);
            if (onChanged != null) {
                onChanged.run();
            }
        }));
        JLabel ioIn = new JLabel(" ");
        JLabel ioOut = new JLabel(" ");
        Runnable showIo = () -> {
            DataProtection.Resend r = (DataProtection.Resend) defResend.getSelectedItem();
            ioIn.setText("Đầu vào:  " + r.input);
            ioOut.setText("Đầu ra:   " + r.output);
        };
        defResend.addActionListener(Ui.safe(e -> showIo.run()));
        showIo.run();
        JPanel ioCard = new JPanel(new GridLayout(0, 1, 0, 2));
        ioCard.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(th.info.border()),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        ioCard.setBackground(th.info.fill());
        ioCard.add(ioIn);
        ioCard.add(ioOut);
        JPanel q2rows = new JPanel(new GridLayout(0, 1, 0, 4));
        q2rows.setOpaque(false);
        q2rows.add(Ui.flow(FlowLayout.LEFT, Ui.muted("Mặc định cho mọi API:"), defResend,
                Ui.muted("Tự động = giữ kết quả 2xx, gặp 4xx thì thử thay ID còn sống hoặc tạo bản ghi mới.")));
        if (focus != null) {
            JComboBox<DataProtection.Resend> apiResend = new JComboBox<>(DataProtection.Resend.values());
            apiResend.setSelectedItem(DataProtection.Resend.of(engine.effectiveActionMode(focus)));
            apiResend.addActionListener(Ui.safe(e -> {
                engine.setApiActionMode(focus, ((DataProtection.Resend) apiResend.getSelectedItem()).mode);
                if (onChanged != null) {
                    onChanged.run();
                }
            }));
            q2rows.add(Ui.flow(FlowLayout.LEFT, Ui.muted("Riêng API #" + focus.gid() + " " + focus.method() + " "
                    + focus.path() + ":"), apiResend));
            if (focus.activeIdNote() != null && !focus.activeIdNote().isBlank()) {
                q2rows.add(Ui.muted("Lần gửi gần nhất: " + focus.activeIdNote()));
            }
        }
        q2.add(q2rows, BorderLayout.CENTER);
        q2.add(ioCard, BorderLayout.SOUTH);

        // ---------------------------------------------------------------- refresh + wiring
        Runnable reload = () -> {
            int legacy = DataProtection.legacyCount(engine.actionEngine(), engine.config());
            banner.setVisible(legacy > 0);
            bannerText.setText("Còn " + legacy + " cấu hình chặn kiểu cũ nằm ngoài danh sách luật (công tắc giữ ở Proxy, chặn chờ"
                    + " theo nhánh/API, luật Proxy cũ). Gộp vào để chỉ còn MỘT nơi cần xem.");
            gateLine.setText(engine.proxyGate().sentence());
            ActionPolicy keep = rulesList.getSelectedValue();
            rulesModel.clear();
            for (ActionPolicy p : ledger.all()) {
                if (p.stage().appliesToProxy()) {
                    rulesModel.addElement(p);
                }
            }
            if (keep != null) {
                for (int i = 0; i < rulesModel.size(); i++) {
                    if (rulesModel.get(i).id().equals(keep.id())) {
                        rulesList.setSelectedIndex(i);
                    }
                }
            }
            runTest.run();
        };
        migrate.addActionListener(Ui.safe(e -> {
            Config c = engine.config().copy();
            int n = DataProtection.migrateLegacy(engine.actionEngine(), c);
            engine.applyConfig(c);
            changed.run();
            reload.run();
            JOptionPane.showMessageDialog(Ui.ownerWindow(parent), "Đã gộp " + n + " cấu hình cũ thành luật. Hành vi chặn giữ nguyên.",
                    "Bảo vệ dữ liệu", JOptionPane.INFORMATION_MESSAGE);
        }));
        add.addActionListener(Ui.safe(e -> {
            ActionPolicy p = askRule(parent, engine, focus);
            if (p != null) {
                ledger.upsert(p);
                changed.run();
                reload.run();
            }
        }));
        toggle.addActionListener(Ui.safe(e -> {
            ActionPolicy p = rulesList.getSelectedValue();
            if (p == null) {
                Ui.hint((Component) e.getSource(), "Chọn 1 luật trong danh sách trước");
            } else {
                ledger.setEnabled(p.id(), !p.enabled());
                changed.run();
                reload.run();
            }
        }));
        up.addActionListener(Ui.safe(e -> {
            ActionPolicy p = rulesList.getSelectedValue();
            if (p == null) {
                Ui.hint((Component) e.getSource(), "Chọn 1 luật trong danh sách trước");
            } else {
                ledger.movePriority(p.id(), -1);
                changed.run();
                reload.run();
            }
        }));
        down.addActionListener(Ui.safe(e -> {
            ActionPolicy p = rulesList.getSelectedValue();
            if (p == null) {
                Ui.hint((Component) e.getSource(), "Chọn 1 luật trong danh sách trước");
            } else {
                ledger.movePriority(p.id(), 1);
                changed.run();
                reload.run();
            }
        }));
        del.addActionListener(Ui.safe(e -> {
            ActionPolicy p = rulesList.getSelectedValue();
            if (p == null) {
                Ui.hint((Component) e.getSource(), "Chọn 1 luật trong danh sách trước");
            } else {
                ledger.delete(p.id(), "Xóa trong " + DataProtection.SOURCE);
                changed.run();
                reload.run();
            }
        }));
        undo.addActionListener(Ui.safe(e -> {
            ledger.undoLastDelete();
            changed.run();
            reload.run();
        }));

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(banner, BorderLayout.NORTH);
        top.add(q1, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.add(testSec, BorderLayout.NORTH);
        bottom.add(q2, BorderLayout.CENTER);
        JButton advanced = Ui.secondaryBtn("Nâng cao (Workbench cũ: Kho ID sống, Chuỗi tạo mới, Dò giao thức)...",
                t -> Icons.workbench(t.fg, 13), () -> ActionWorkbenchDialog.show((JComponent) root, engine, focus, null,
                        ActionWorkbenchDialog.TAB_STRATEGY, onChanged));
        bottom.add(Ui.flow(FlowLayout.RIGHT, advanced), BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);
        root.add(rulesSec, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        reload.run();
        return root;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.add(Ui.bold(title), BorderLayout.NORTH);
        return p;
    }

    /** Small form: scope + target + methods + choice -> one rule. */
    static ActionPolicy askRule(Component parent, Engine engine, ApiGroup focus) {
        JComboBox<DataProtection.Scope> scope = new JComboBox<>(DataProtection.Scope.values());
        JComboBox<DataProtection.Choice> choice = new JComboBox<>(DataProtection.Choice.values());
        choice.setSelectedItem(DataProtection.Choice.ASK);
        JComboBox<String> method = new JComboBox<>(new String[] {"POST", "PUT", "PATCH", "DELETE", "GET"});
        List<String> branches = new ArrayList<>(engine.knownPaths());
        java.util.Collections.sort(branches);
        JComboBox<String> branch = new JComboBox<>(branches.toArray(new String[0]));
        branch.setEditable(true);
        JTextField path = new JTextField(focus != null ? focus.path() : "/api/...", 26);
        if (focus != null) {
            scope.setSelectedItem(DataProtection.Scope.API);
            method.setSelectedItem(focus.method());
            branch.setSelectedItem(focus.branch());
        }
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("Áp dụng cho:"));
        p.add(scope);
        p.add(new JLabel("Method (cho 'Một method' / 'Riêng một API'):"));
        p.add(method);
        p.add(new JLabel("Nhánh (cho 'Một nhánh'):"));
        p.add(branch);
        p.add(new JLabel("Path (cho 'Riêng một API', số/ID tự thành {id}):"));
        p.add(path);
        p.add(new JLabel("Thì:"));
        p.add(choice);
        if (JOptionPane.showConfirmDialog(Ui.ownerWindow(parent), p, "Thêm luật bảo vệ dữ liệu", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return null;
        }
        DataProtection.Scope s = (DataProtection.Scope) scope.getSelectedItem();
        String m = String.valueOf(method.getSelectedItem());
        return switch (s) {
            case METHOD -> DataProtection.rule(s, m, List.of(m), (DataProtection.Choice) choice.getSelectedItem(), null);
            case BRANCH -> DataProtection.rule(s, String.valueOf(branch.getSelectedItem()), ActionEngine.ACTION_METHODS,
                    (DataProtection.Choice) choice.getSelectedItem(), null);
            case API -> DataProtection.rule(s, path.getText(), List.of(m), (DataProtection.Choice) choice.getSelectedItem(),
                    focus != null && focus.path().equals(path.getText()) ? focus.gid() : null);
            default -> DataProtection.rule(s, null, ActionEngine.ACTION_METHODS, (DataProtection.Choice) choice.getSelectedItem(),
                    null);
        };
    }
}
