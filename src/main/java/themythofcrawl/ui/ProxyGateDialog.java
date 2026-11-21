package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ProxyGate;
import themythofcrawl.model.ActionPolicy;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.ui.kit.ModernToggleSwitch;
import themythofcrawl.ui.theme.Theme;

/**
 * V_7.1 "Cổng chặn Proxy": replaces the header mode menu. One switch + three plain questions
 * (Chặn request nào? · Method nào? · Làm gì với request bị chặn?), a live diagram of what the
 * browser / Burp / server will see, a one-line summary sentence and a "Thử với 1 URL" box.
 */
final class ProxyGateDialog {
    private ProxyGateDialog() {
    }

    static void show(MainPanel ui) {
        Window owner = SwingUtilities.getWindowAncestor(ui.root());
        JDialog dlg = new JDialog(Ui.ownerWindow(owner), "Cổng chặn Proxy", java.awt.Dialog.ModalityType.MODELESS);
        Gate gate = new Gate(ui.engine, ui.api != null ? ui.api.currentGroup() : null, () -> {
            ui.syncHeader();
            ui.tick(true);
        });
        JScrollPane sp = new JScrollPane(gate.panel);
        sp.setBorder(BorderFactory.createEmptyBorder());
        dlg.setContentPane(sp);
        Ui.fastScroll(sp);
        dlg.setSize(new Dimension(Theme.current().scaled(860), Theme.current().scaled(720)));
        dlg.setLocationRelativeTo(ui.root());
        dlg.setVisible(true);
    }

    static final class TrackWidthPanel extends JPanel implements javax.swing.Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 24; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) { return Math.max(48, r.height - 48); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** The panel itself (also used headless by tests / previews). */
    static final class Gate {
        /** Tracks the viewport width so nothing is cut at the right edge; only vertical scrolling. */
        final JPanel panel = new TrackWidthPanel();
        final ModernToggleSwitch master = new ModernToggleSwitch(false);
        final JLabel status = new JLabel();
        final themythofcrawl.ui.kit.WrappedText sentence = themythofcrawl.ui.kit.WrappedText.body(" ", 560);
        final JLabel problem = new JLabel(" ");
        final ProxyFlowDiagram diagram = new ProxyFlowDiagram();
        final Map<ProxyGate.Scope, JRadioButton> scopeButtons = new LinkedHashMap<>();
        final JComboBox<String> hostBox = new JComboBox<>();
        final JTextField pathField = new JTextField(22);
        final JComboBox<String> branchBox = new JComboBox<>();
        final Map<String, JCheckBox> methodBoxes = new LinkedHashMap<>();
        final Map<ProxyGate.Effect, JRadioButton> effectButtons = new LinkedHashMap<>();
        final JButton apply = Ui.solidPrimary("Áp dụng", t -> Icons.check(t.accent.onSolid(), 12));
        final JLabel dirty = new JLabel(" ");
        final JTextField testUrl = new JTextField(34);
        final JComboBox<String> testMethod = new JComboBox<>(new String[] {"POST", "PUT", "PATCH", "DELETE", "GET"});
        final themythofcrawl.ui.kit.WrappedText testResult = themythofcrawl.ui.kit.WrappedText.body(" ", 560);
        final themythofcrawl.ui.kit.WrappedText others = themythofcrawl.ui.kit.WrappedText.body(" ", 560);
        final JPanel scopeDetail = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        final themythofcrawl.ui.kit.WrappedText effectWhat = themythofcrawl.ui.kit.WrappedText.body(" ", 560);
        final themythofcrawl.ui.kit.WrappedText effectWhen = themythofcrawl.ui.kit.WrappedText.muted(" ", 560);
        final JLabel getWarn = new JLabel(" ");
        JPanel extraPanel;
        private ProxyGate.Scope shownScope;
        private final Engine engine;
        private final Runnable onChanged;
        private boolean loading;

        Gate(Engine engine, ApiGroup focus, Runnable onChanged) {
            this.engine = engine;
            this.onChanged = onChanged;
            Theme th = Theme.current();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(10, 14, 12, 14));

            // ---- master switch + summary
            JPanel head = new JPanel(new BorderLayout(10, 4));
            head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(th.border),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            status.setFont(th.boldFont);
            head.add(Ui.flow(FlowLayout.LEFT, master, status), BorderLayout.WEST);
            head.add(sentence, BorderLayout.SOUTH);
            add(head);
            add(diagram);

            // ---- step 1: which requests
            ButtonGroup sg = new ButtonGroup();
            JPanel scope = new JPanel();
            scope.setLayout(new BoxLayout(scope, BoxLayout.Y_AXIS));
            for (ProxyGate.Scope s : ProxyGate.Scope.values()) {
                JRadioButton rb = new JRadioButton(s.label);
                sg.add(rb);
                scopeButtons.put(s, rb);
                rb.addActionListener(Ui.safe(e -> changed()));
            }
            Set<String> hosts = new TreeSet<>();
            for (ApiGroup g : engine.groups()) {
                hosts.add(g.host());
            }
            for (String h : hosts) {
                hostBox.addItem(h);
            }
            hostBox.setEditable(true);
            hostBox.setPrototypeDisplayValue("api.example-company.com");
            List<String> branches = new ArrayList<>(engine.knownPaths());
            java.util.Collections.sort(branches);
            for (String b : branches) {
                branchBox.addItem(b);
            }
            branchBox.setEditable(true);
            pathField.setToolTipText("Ví dụ: /api/orders (mọi path bắt đầu bằng /api/orders/) hoặc /api/*/delete (dấu * = bất kỳ)");
            // V_7.2: only the selected option's settings are shown, right under the options
            JPanel radios = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
            for (ProxyGate.Scope sc : ProxyGate.Scope.values()) {
                radios.add(scopeButtons.get(sc));
            }
            scope.add(radios);
            scopeDetail.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
            scope.add(scopeDetail);
            hostBox.addActionListener(Ui.safe(e -> changed()));
            branchBox.addActionListener(Ui.safe(e -> changed()));
            pathField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            });
            add(step("Bước 1 — Chặn request nào?", scope));

            // ---- step 2: methods
            JPanel methods = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
            for (String m : List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
                JCheckBox cb = new JCheckBox(m);
                methodBoxes.put(m, cb);
                cb.addActionListener(Ui.safe(e -> changed()));
                methods.add(cb);
            }
            methods.add(Ui.secondaryBtn("Chỉ request thay đổi dữ liệu", null, () -> setMethods(Set.of("POST", "PUT", "PATCH", "DELETE"))));
            methods.add(Ui.secondaryBtn("Tất cả", null, () -> setMethods(new LinkedHashSet<>(methodBoxes.keySet()))));
            JPanel step2 = new JPanel(new BorderLayout());
            step2.add(methods, BorderLayout.CENTER);
            step2.add(getWarn, BorderLayout.SOUTH);
            getWarn.setForeground(th.warning.text());
            add(step("Bước 2 — Method nào?", step2));

            // ---- step 3: what to do
            ButtonGroup eg = new ButtonGroup();
            JPanel effectRadios = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
            for (ProxyGate.Effect ef : ProxyGate.Effect.values()) {
                JRadioButton rb = new JRadioButton(ef.label);
                eg.add(rb);
                effectButtons.put(ef, rb);
                rb.addActionListener(Ui.safe(e -> changed()));
                effectRadios.add(rb);
            }
            JPanel effectCard = new JPanel(new BorderLayout(0, 2));
            effectCard.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(th.border),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)));
            effectCard.add(effectWhat, BorderLayout.CENTER);
            effectCard.add(effectWhen, BorderLayout.SOUTH);
            JPanel effects = new JPanel(new BorderLayout(0, 4));
            effects.add(effectRadios, BorderLayout.NORTH);
            effects.add(effectCard, BorderLayout.CENTER);
            add(step("Bước 3 — Làm gì với request bị chặn?", effects));

            // ---- apply row
            problem.setForeground(th.danger.text());
            dirty.setForeground(th.warning.text());
            JButton offAll = Ui.dangerBtn("Tắt chặn toàn bộ (mọi luật)", t -> Icons.stop(t.danger.text()), () -> {
                engine.toggleQuickActionFromHeader(false);
                load();
                notifyChanged();
            });
            JButton queue = Ui.secondaryBtn("Hàng chờ chặn...", t -> Icons.strategyShield(t.fg, 12),
                    () -> MainPanel.openInterceptQueue(engine));
            JButton more = Ui.secondaryBtn("Luật chi tiết & Gửi lại khi ID bị xóa...", t -> Icons.sliders(t.fg),
                    () -> DataProtectionDialog.show(panel, engine, focus, () -> {
                        load();
                        notifyChanged();
                    }));
            apply.addActionListener(Ui.safe(e -> applyNow()));
            add(Ui.flow(FlowLayout.LEFT, apply, dirty, problem));
            add(Ui.flow(FlowLayout.LEFT, offAll, queue, more));

            // ---- test box
            testUrl.setText(focus != null ? focus.url() : "https://" + (hosts.isEmpty() ? "api.example.com" : hosts.iterator().next())
                    + "/api/orders/15");
            if (focus != null) {
                testMethod.setSelectedItem(focus.method());
            }
            JPanel test = new JPanel(new BorderLayout(0, 4));
            test.add(Ui.flow(FlowLayout.LEFT, testMethod, testUrl,
                    Ui.secondaryBtn("Thử", t -> Icons.playTest(t.fg, 12), this::runTest)), BorderLayout.NORTH);
            test.add(testResult, BorderLayout.CENTER);
            JPanel extra = new JPanel();
            extra.setLayout(new BoxLayout(extra, BoxLayout.Y_AXIS));
            JPanel t1 = step("Thử với 1 request (không gửi gì, theo cấu hình đã ÁP DỤNG)", test);
            JPanel t2 = step("Luật riêng khác đang bật (luật cụ thể thắng cổng chặn chung)", others);
            t1.setAlignmentX(Component.LEFT_ALIGNMENT);
            t2.setAlignmentX(Component.LEFT_ALIGNMENT);
            extra.add(t1);
            extra.add(javax.swing.Box.createVerticalStrut(8));
            extra.add(t2);
            extra.setVisible(false);
            JButton extraToggle = Ui.secondaryBtn("Mở: Thử với 1 request & luật khác", t -> Icons.playTest(t.fg, 12));
            extraToggle.addActionListener(Ui.safe(e -> {
                extra.setVisible(!extra.isVisible());
                extraToggle.setText(extra.isVisible() ? "Thu lại phần Thử & luật khác" : "Mở: Thử với 1 request & luật khác");
                panel.revalidate();
                panel.repaint();
            }));
            add(Ui.flow(FlowLayout.LEFT, extraToggle));
            add(extra);
            this.extraPanel = extra;

            master.onChange(on -> {
                if (!loading) {
                    changed();
                    applyNow();
                }
            });
            for (Component c : panel.getComponents()) {
                if (c instanceof javax.swing.JComponent jc) {
                    jc.setAlignmentX(Component.LEFT_ALIGNMENT);
                }
            }
            load();
        }

        private void add(Component c) {
            panel.add(c);
            panel.add(javax.swing.Box.createVerticalStrut(8));
        }

        private static JPanel step(String title, Component body) {
            JPanel p = new JPanel(new BorderLayout(0, 4));
            p.add(Ui.bold(title), BorderLayout.NORTH);
            p.add(body, BorderLayout.CENTER);
            return p;
        }

        private void setMethods(Set<String> ms) {
            for (Map.Entry<String, JCheckBox> e : methodBoxes.entrySet()) {
                e.getValue().setSelected(ms.contains(e.getKey()));
            }
            changed();
        }

        /** Settings currently shown in the form (not necessarily applied yet). */
        ProxyGate.Settings formSettings() {
            ProxyGate.Scope scope = ProxyGate.Scope.ALL_HOSTS;
            for (Map.Entry<ProxyGate.Scope, JRadioButton> e : scopeButtons.entrySet()) {
                if (e.getValue().isSelected()) {
                    scope = e.getKey();
                }
            }
            ProxyGate.Effect eff = ProxyGate.Effect.ASK;
            for (Map.Entry<ProxyGate.Effect, JRadioButton> e : effectButtons.entrySet()) {
                if (e.getValue().isSelected()) {
                    eff = e.getKey();
                }
            }
            Set<String> ms = new LinkedHashSet<>();
            for (Map.Entry<String, JCheckBox> e : methodBoxes.entrySet()) {
                if (e.getValue().isSelected()) {
                    ms.add(e.getKey());
                }
            }
            Object host = hostBox.getEditor().getItem();
            Object branch = branchBox.getEditor().getItem();
            return new ProxyGate.Settings(master.isOn(), scope, host == null ? "" : host.toString(), pathField.getText(),
                    branch == null ? "" : branch.toString(), ms, eff);
        }

        void load() {
            loading = true;
            try {
                ProxyGate.Settings s = engine.proxyGate();
                master.setOn(s.on(), false);
                scopeButtons.get(s.scope()).setSelected(true);
                if (!s.host().isEmpty()) {
                    hostBox.setSelectedItem(s.host());
                }
                pathField.setText(s.path());
                if (!s.branch().isEmpty()) {
                    branchBox.setSelectedItem(s.branch());
                } else {
                    branchBox.setSelectedItem(engine.activeBranch().name());
                }
                for (Map.Entry<String, JCheckBox> e : methodBoxes.entrySet()) {
                    e.getValue().setSelected(s.methods().contains(e.getKey()));
                }
                effectButtons.get(s.effect()).setSelected(true);
            } finally {
                loading = false;
            }
            refreshView(false);
            runTest();
        }

        private void changed() {
            if (!loading) {
                refreshView(true);
            }
        }

        private void refreshView(boolean pending) {
            ProxyGate.Settings s = formSettings();
            Theme th = Theme.current();
            status.setText(s.on() ? "Cổng chặn: ĐANG BẬT" : "Cổng chặn: TẮT");
            status.setForeground(s.on() ? th.warning.text() : th.fg);
            sentence.setText(s.sentence());
            diagram.show(s.on(), s.effect());
            String p = s.on() ? s.problem() : null;
            problem.setText(p == null ? " " : p);
            apply.setEnabled(p == null);
            dirty.setText(pending ? "Chưa áp dụng" : " ");
            if (s.scope() != shownScope) {
                shownScope = s.scope();
                scopeDetail.removeAll();
                switch (s.scope()) {
                    case HOST -> {
                        scopeDetail.add(Ui.muted("Host:"));
                        scopeDetail.add(hostBox);
                        scopeDetail.add(Ui.muted("(cũng khớp subdomain, vd api.<host>)"));
                    }
                    case HOST_PATH -> {
                        scopeDetail.add(Ui.muted("Host:"));
                        scopeDetail.add(hostBox);
                        scopeDetail.add(Ui.muted("Path:"));
                        scopeDetail.add(pathField);
                        scopeDetail.add(Ui.muted("/api/orders = mọi path bên dưới · * = bất kỳ"));
                    }
                    case BRANCH -> {
                        scopeDetail.add(Ui.muted("Nhánh:"));
                        scopeDetail.add(branchBox);
                        scopeDetail.add(Ui.muted("(kèm nhánh con · theo Đích đang crawl)"));
                    }
                    default -> scopeDetail.add(Ui.muted("Áp dụng cho request tới bất kỳ host nào."));
                }
                scopeDetail.revalidate();
                scopeDetail.repaint();
            }
            effectWhat.setText(s.effect().what);
            effectWhen.setText("Dùng khi: " + s.effect().when);
            getWarn.setText(s.methods().contains("GET")
                    ? "Đang chặn cả GET: trình duyệt sẽ đứng ở mọi lần tải trang / đọc dữ liệu khớp phạm vi."
                    : "Không chặn GET (tải trang / đọc dữ liệu) — nên giữ như vậy.");
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (ActionPolicy pol : engine.actionEngine().policyLedger().all()) {
                if (pol.enabled() && !ProxyGate.ID.equals(pol.id()) && pol.stage().appliesToProxy()) {
                    if (n < 4) {
                        sb.append(n == 0 ? "" : "   |   ").append(themythofcrawl.core.DataProtection.sentence(pol));
                    }
                    n++;
                }
            }
            others.setText(n == 0 ? "(không có)" : sb + (n > 4 ? "   ... +" + (n - 4) + " luật" : ""));
        }

        void applyNow() {
            ProxyGate.Settings s = formSettings();
            if (s.on() && s.problem() != null) {
                refreshView(true);
                return;
            }
            engine.setProxyGate(s);
            refreshView(false);
            runTest();
            notifyChanged();
        }

        void runTest() {
            String raw = testUrl.getText().trim();
            String host = "";
            String path = raw;
            try {
                URI u = URI.create(raw.contains("://") ? raw : "https://" + raw);
                host = u.getHost() == null ? "" : u.getHost();
                path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                        + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            } catch (IllegalArgumentException ignored) {
                // keep raw as path
            }
            testResult.setText(engine.explainProtection(String.valueOf(testMethod.getSelectedItem()), host, path, null));
        }

        private void notifyChanged() {
            if (onChanged != null) {
                onChanged.run();
            }
        }
    }
}
