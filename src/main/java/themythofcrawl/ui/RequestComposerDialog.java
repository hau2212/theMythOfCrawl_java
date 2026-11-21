package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.app.Prefs;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.HttpBytes;
import themythofcrawl.core.Json;
import themythofcrawl.core.Text;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Blob;
import themythofcrawl.model.Role;
import themythofcrawl.model.RoleResult;
import themythofcrawl.model.Service;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Floating, draggable, resizable Quick Request Composer &amp; Live Replay window (V_3.4_beta).
 * <p>
 * Lets users pick ANY captured API (or Role row), freely edit Raw HTTP request + Target Host/Port/HTTPS,
 * format JSON, auto-recalculate {@code Content-Length}, choose Role &amp; per-send Action mode,
 * replay immediately with live side-by-side Response + Session History, and perform full CRUD saves:
 * Ad-hoc Send, Overwrite Original API (Update), Clone as New API in Branch (Create), Push to Repeater, or Reset.
 */
public final class RequestComposerDialog {

    private static final DateTimeFormatter HMS_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public record SendHistoryEntry(
            int index,
            long sentAtMs,
            String roleLabel,
            Service service,
            byte[] requestSent,
            byte[] response,
            Integer status,
            int bodyLength,
            long elapsedMs,
            String error) {
    }

    /** Headless-testable view bundle for RequestComposerDialog. */
    public record View(
            JPanel panel,
            JTextArea requestArea,
            JTextArea responseArea,
            JTable historyTable,
            JButton sendButton,
            JButton saveOverwriteButton,
            JButton cloneApiButton,
            Pill statusPill,
            JComboBox<String> roleCombo,
            JComboBox<String> actionModeCombo,
            Runnable refreshHistory) {
    }

    private RequestComposerDialog() {
    }

    public static void showFloating(Component parent, Engine engine, ApiGroup api, RoleResult initialRole, Runnable onAfter) {
        open(parent, engine, api, initialRole, null);
        if (onAfter != null) {
            onAfter.run();
        }
    }

    public static void open(Component parent, Engine engine, ApiGroup api, RoleResult initialRole, MainPanel ownerUi) {
        if (engine == null || api == null) {
            return;
        }
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        if (owner == null && ownerUi != null) {
            owner = SwingUtilities.getWindowAncestor(ownerUi.root());
        }
        Prefs prefs = UiPrefs.resolve(null, engine);
        String roleSuffix = (initialRole != null && !initialRole.original())
                ? " [" + initialRole.roleName() + "]" : "";
        String title = "Sửa & Gửi lại Request — " + Text.apiLabel(api.gid()) + " "
                + api.method() + " " + Text.shortPath(api.path(), 44) + roleSuffix;
        JDialog dlg = new JDialog(Ui.ownerWindow(owner), title, JDialog.ModalityType.MODELESS);
        dlg.setResizable(true);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        View view = buildView(engine, api, initialRole, ownerUi, dlg::dispose, pinned -> {
            try {
                dlg.setAlwaysOnTop(pinned);
            } catch (SecurityException ignored) {
                // Restricted environments may disallow alwaysOnTop
            }
        });
        dlg.setContentPane(view.panel());

        // ESC closes, Ctrl+Enter sends immediately
        dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeComposer");
        dlg.getRootPane().getActionMap().put("closeComposer", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dlg.dispose();
            }
        });
        dlg.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "sendComposer");
        dlg.getRootPane().getActionMap().put("sendComposer", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (view.sendButton().isEnabled()) {
                    view.sendButton().doClick();
                }
            }
        });

        dlg.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (dlg.isVisible()) {
                    UiPrefs.setComposerSize(prefs, dlg.getWidth(), dlg.getHeight());
                }
            }
        });
        dlg.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                UiPrefs.setComposerSize(prefs, dlg.getWidth(), dlg.getHeight());
            }
        });

        Theme.track(dlg);
        dlg.setMinimumSize(new Dimension(920, 580));
        dlg.setSize(UiPrefs.composerSize(prefs));
        dlg.setLocationRelativeTo(owner);
        Ui.fastScroll(dlg.getContentPane());
        dlg.setVisible(true);
    }

    /** Builds the headless-safe Composer view panel. */
    public static View buildView(
            Engine engine,
            ApiGroup api,
            RoleResult initialRole,
            MainPanel ownerUi,
            Runnable onClose,
            Consumer<Boolean> onPinToggle) {
        Theme th = Theme.current();
        byte[] initialBytes = (initialRole != null)
                ? engine.requestOf(api, initialRole)
                : Blob.bytesOf(api.origRequest());
        if (initialBytes == null) {
            initialBytes = new byte[0];
        }
        final String originalReqText = new String(initialBytes, StandardCharsets.ISO_8859_1);
        byte[] initialRespBytes = (initialRole != null)
                ? engine.responseOf(api, initialRole)
                : Blob.bytesOf(api.origResponse());

        final List<SendHistoryEntry> history = new ArrayList<>();
        final byte[][] lastResponseHolder = {initialRespBytes};
        final boolean[] prettyRespMode = {false};
        final boolean[] sendingNow = {false};

        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setBackground(th.surface);
        root.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.SPACE_2, 0));

        // ---- Top Header + Pin Toggle
        JPanel northWrap = new JPanel();
        northWrap.setLayout(new BoxLayout(northWrap, BoxLayout.Y_AXIS));
        northWrap.setOpaque(false);

        JPanel headerBar = new JPanel(new BorderLayout(8, 0));
        headerBar.setOpaque(true);
        headerBar.setBackground(th.surfaceAlt);
        headerBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, th.border),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        JPanel titleCol = new JPanel();
        titleCol.setLayout(new BoxLayout(titleCol, BoxLayout.Y_AXIS));
        titleCol.setOpaque(false);
        JLabel titleLbl = Ui.h2("Trình Sửa & Gửi lại Request — " + Text.apiLabel(api.gid())
                + " [" + api.method() + " " + Text.shortPath(api.path(), 56) + "]");
        JLabel subLbl = Ui.muted("Nhánh: " + api.branch()
                + "  |  Chỉnh sửa tự do, chọn Role & chế độ Action cho riêng lần gửi này, xem Response tức thì hoặc Lưu đè / Tạo API mới.");
        titleCol.add(titleLbl);
        titleCol.add(Box.createVerticalStrut(2));
        titleCol.add(subLbl);
        headerBar.add(titleCol, BorderLayout.CENTER);

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        headerRight.setOpaque(false);
        Pill statusPill = new Pill(
                api.origStatus() != null ? "Gốc: HTTP " + api.origStatus() : "Chưa có Response",
                api.origStatus() != null && api.origStatus() >= 200 && api.origStatus() < 300
                        ? t -> t.success : t -> t.neutral,
                t -> Icons.api(t.fg, 12));
        JToggleButton pinBtn = new JToggleButton("Ghim nổi");
        pinBtn.setIcon(Icons.pin(th.muted, 13));
        pinBtn.setFocusable(false);
        pinBtn.setMargin(new Insets(3, 8, 3, 8));
        pinBtn.addActionListener(Ui.safe(e -> {
            if (onPinToggle != null) {
                onPinToggle.accept(pinBtn.isSelected());
            }
        }));
        headerRight.add(statusPill);
        headerRight.add(pinBtn);
        headerBar.add(headerRight, BorderLayout.EAST);
        northWrap.add(headerBar);

        // ---- Target & Execution Bar (Row 1: Target Host/Port/Protocol + Utilities)
        JPanel targetBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        targetBar.setOpaque(true);
        targetBar.setBackground(th.surface);
        targetBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, th.border));

        JComboBox<String> protoCombo = new JComboBox<>(new String[] {"https", "http"});
        protoCombo.setSelectedItem(api.service().secure() ? "https" : "http");
        JTextField hostField = new JTextField(api.service().host(), 20);
        JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(api.service().port(), 1, 65535, 1));
        portSpinner.setPreferredSize(new Dimension(76, 26));
        JCheckBox autoLenChk = new JCheckBox("Tự tính Content-Length", true);
        autoLenChk.setOpaque(false);
        JButton formatJsonBtn = Ui.secondaryBtn("Format JSON", t -> Icons.sliders(t.fg));
        JButton diffBtn = Ui.secondaryBtn("So với Gốc", t -> Icons.dup(t.fg));
        JButton resetOrigBtn = Ui.secondaryBtn("Khôi phục gốc", t -> Icons.undo(t.fg));

        targetBar.add(Ui.muted("Đích:"));
        targetBar.add(protoCombo);
        targetBar.add(hostField);
        targetBar.add(Ui.muted(":"));
        targetBar.add(portSpinner);
        targetBar.add(Box.createHorizontalStrut(6));
        targetBar.add(autoLenChk);
        targetBar.add(formatJsonBtn);
        targetBar.add(diffBtn);
        targetBar.add(resetOrigBtn);
        northWrap.add(targetBar);

        // ---- Row 2: Role Selector + Per-Send Action Mode
        JPanel execBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        execBar.setOpaque(true);
        execBar.setBackground(th.surfaceAlt);
        execBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, th.border));

        List<Role> allRoles = engine.roles().all();
        List<String> roleChoices = new ArrayList<>();
        roleChoices.add("Raw (Vừa sửa — không áp luật Role)");
        roleChoices.add("Profile API gốc (" + (engine.originalProfile().enabled() ? "Đang bật" : "Đang tắt") + ")");
        roleChoices.add("Tất cả Role đang bật (" + engine.roles().enabled().size() + " roles)");
        for (Role r : allRoles) {
            roleChoices.add("Role: " + r.name() + (r.enabled() ? "" : " [Đang tắt]"));
        }
        JComboBox<String> roleCombo = new JComboBox<>(roleChoices.toArray(new String[0]));
        roleCombo.setPreferredSize(new Dimension(265, 26));

        JComboBox<String> actionModeCombo = new JComboBox<>(new String[] {
                "Nguyên bản (Không can thiệp — gửi đúng từng byte)",
                "Tự đổi ID sống từ Kho (nếu ID đã tiêu thụ)",
                "Chạy Producer tạo ID mới trước khi gửi"
        });
        actionModeCombo.setPreferredSize(new Dimension(320, 26));

        JLabel byteCounter = Ui.muted(initialBytes.length + " bytes");

        execBar.add(Ui.bold("Gửi với:"));
        execBar.add(roleCombo);
        execBar.add(Box.createHorizontalStrut(6));
        execBar.add(Ui.bold("Chế độ Action lần này:"));
        execBar.add(actionModeCombo);
        execBar.add(Box.createHorizontalStrut(6));
        execBar.add(byteCounter);
        northWrap.add(execBar);

        root.add(northWrap, BorderLayout.NORTH);

        // ---- Center SplitPane: Left = Raw Request Editor, Right = Live Response + Session History
        JTextArea requestArea = new JTextArea(originalReqText);
        requestArea.setFont(th.monoFont);
        requestArea.setLineWrap(false);
        requestArea.setBackground(th.surface);
        requestArea.setForeground(th.fg);
        requestArea.setCaretColor(th.fg);
        requestArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        requestArea.getDocument().addDocumentListener(new DocumentListener() {
            private void updateCount() {
                byte[] b = requestArea.getText().getBytes(StandardCharsets.ISO_8859_1);
                boolean modified = !requestArea.getText().equals(originalReqText);
                byteCounter.setText(b.length + " bytes" + (modified ? " · Đã chỉnh sửa" : " · Nguyên bản"));
            }
            @Override public void insertUpdate(DocumentEvent e) { updateCount(); }
            @Override public void removeUpdate(DocumentEvent e) { updateCount(); }
            @Override public void changedUpdate(DocumentEvent e) { updateCount(); }
        });

        JPanel leftPane = new JPanel(new BorderLayout(0, 4));
        leftPane.setOpaque(false);
        JPanel reqHead = new JPanel(new BorderLayout());
        reqHead.setOpaque(true);
        reqHead.setBackground(th.surfaceAlt);
        reqHead.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, th.border),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        reqHead.add(Ui.bold("Raw HTTP Request (Chỉnh sửa trực tiếp)"), BorderLayout.WEST);
        reqHead.add(Ui.muted("Ctrl+Enter để Gửi ngay"), BorderLayout.EAST);
        leftPane.add(reqHead, BorderLayout.NORTH);
        JScrollPane reqScroll = new JScrollPane(requestArea);
        reqScroll.setBorder(BorderFactory.createLineBorder(th.border));
        leftPane.add(reqScroll, BorderLayout.CENTER);

        // Right Pane: Response viewer on top + Session History table at bottom
        JTextArea responseArea = new JTextArea();
        responseArea.setFont(th.monoFont);
        responseArea.setEditable(false);
        responseArea.setLineWrap(false);
        responseArea.setBackground(th.surface);
        responseArea.setForeground(th.fg);
        responseArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        if (initialRespBytes != null && initialRespBytes.length > 0) {
            responseArea.setText(new String(initialRespBytes, StandardCharsets.UTF_8));
            responseArea.setCaretPosition(0);
        } else {
            responseArea.setText("(Chưa có Response — bấm 'Gửi ngay' hoặc Ctrl+Enter để xem kết quả)");
        }

        JPanel respHead = new JPanel(new BorderLayout());
        respHead.setOpaque(true);
        respHead.setBackground(th.surfaceAlt);
        respHead.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, th.border),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        JLabel respMetaLbl = Ui.bold("Response trực tiếp");
        JToggleButton prettyRespBtn = new JToggleButton("Pretty JSON");
        prettyRespBtn.setFocusable(false);
        prettyRespBtn.setMargin(new Insets(2, 8, 2, 8));
        respHead.add(respMetaLbl, BorderLayout.WEST);
        respHead.add(prettyRespBtn, BorderLayout.EAST);

        Runnable renderResponseText = () -> {
            byte[] rb = lastResponseHolder[0];
            if (rb == null || rb.length == 0) {
                return;
            }
            if (prettyRespMode[0]) {
                responseArea.setText(formatHttpWithPrettyJson(rb));
            } else {
                responseArea.setText(new String(rb, StandardCharsets.UTF_8));
            }
            responseArea.setCaretPosition(0);
        };
        prettyRespBtn.addActionListener(Ui.safe(e -> {
            prettyRespMode[0] = prettyRespBtn.isSelected();
            renderResponseText.run();
        }));

        JPanel respTop = new JPanel(new BorderLayout(0, 4));
        respTop.setOpaque(false);
        respTop.add(respHead, BorderLayout.NORTH);
        JScrollPane respScroll = new JScrollPane(responseArea);
        respScroll.setBorder(BorderFactory.createLineBorder(th.border));
        respTop.add(respScroll, BorderLayout.CENTER);

        DefaultTableModel histModel = new DefaultTableModel(
                new String[] {"#", "Lúc", "Role / Chế độ", "HTTP", "Thời gian", "Body"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable historyTable = new ThemedTable(histModel);
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyTable.setRowHeight(24);
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(36);
        historyTable.getColumnModel().getColumn(1).setPreferredWidth(68);
        historyTable.getColumnModel().getColumn(2).setPreferredWidth(150);
        historyTable.getColumnModel().getColumn(3).setPreferredWidth(62);
        historyTable.getColumnModel().getColumn(4).setPreferredWidth(68);
        historyTable.getColumnModel().getColumn(5).setPreferredWidth(68);

        JScrollPane histScroll = new JScrollPane(historyTable);
        histScroll.setColumnHeaderView(historyTable.getTableHeader());
        histScroll.setBorder(BorderFactory.createLineBorder(th.border));
        histScroll.setPreferredSize(new Dimension(420, 120));

        JPanel histPanel = new JPanel(new BorderLayout(0, 3));
        histPanel.setOpaque(false);
        JLabel histTitle = Ui.muted("Lịch sử gửi trong phiên (bấm 1 dòng để xem lại Request & Response lần đó)");
        histTitle.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        histPanel.add(histTitle, BorderLayout.NORTH);
        histPanel.add(histScroll, BorderLayout.CENTER);

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, respTop, histPanel);
        rightSplit.setResizeWeight(0.74);
        rightSplit.setDividerSize(5);
        rightSplit.setBorder(null);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, rightSplit);
        mainSplit.setResizeWeight(0.5);
        mainSplit.setDividerSize(6);
        mainSplit.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        root.add(mainSplit, BorderLayout.CENTER);

        Runnable refreshHistory = () -> {
            histModel.setRowCount(0);
            for (int i = history.size() - 1; i >= 0; i--) {
                SendHistoryEntry h = history.get(i);
                histModel.addRow(new Object[] {
                        "#" + h.index(),
                        HMS_FMT.format(Instant.ofEpochMilli(h.sentAtMs())),
                        h.roleLabel(),
                        h.status() != null ? String.valueOf(h.status()) : "LỖI",
                        h.elapsedMs() + " ms",
                        h.bodyLength() + " B"
                });
            }
        };

        historyTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = historyTable.getSelectedRow();
            if (row < 0 || row >= history.size()) return;
            SendHistoryEntry entry = history.get(history.size() - 1 - row);
            if (entry.requestSent() != null) {
                requestArea.setText(new String(entry.requestSent(), StandardCharsets.ISO_8859_1));
                requestArea.setCaretPosition(0);
            }
            lastResponseHolder[0] = entry.response();
            if (entry.response() != null && entry.response().length > 0) {
                renderResponseText.run();
            } else {
                responseArea.setText("Lỗi gửi: " + entry.error());
            }
            updateStatusPill(statusPill, respMetaLbl, entry.status(), entry.elapsedMs(), entry.bodyLength(), entry.roleLabel(), entry.error());
        });

        // ---- Utility button handlers
        formatJsonBtn.addActionListener(Ui.safe(e -> {
            String formatted = formatHttpWithPrettyJson(requestArea.getText().getBytes(StandardCharsets.UTF_8));
            if (formatted != null && !formatted.isEmpty()) {
                requestArea.setText(formatted);
                requestArea.setCaretPosition(0);
            }
        }));

        diffBtn.addActionListener(Ui.safe(e -> {
            String cur = requestArea.getText();
            if (cur.equals(originalReqText)) {
                JOptionPane.showMessageDialog(Ui.ownerWindow(root),
                        "Request hiện tại giống hệt bản gốc ban đầu (chưa có thay đổi).",
                        "So sánh với Bản gốc", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String[] origLines = originalReqText.split("\r?\n", -1);
            String[] curLines = cur.split("\r?\n", -1);
            StringBuilder sb = new StringBuilder();
            sb.append("Thay đổi so với bản gốc:\n\n");
            int max = Math.max(origLines.length, curLines.length);
            int diffCount = 0;
            for (int i = 0; i < max && diffCount < 25; i++) {
                String a = i < origLines.length ? origLines[i] : "";
                String b = i < curLines.length ? curLines[i] : "";
                if (!a.equals(b)) {
                    sb.append("Dòng ").append(i + 1).append(":\n");
                    sb.append("  - Gốc: ").append(a.length() > 120 ? a.substring(0, 120) + "..." : a).append("\n");
                    sb.append("  + Mới: ").append(b.length() > 120 ? b.substring(0, 120) + "..." : b).append("\n");
                    diffCount++;
                }
            }
            JOptionPane.showMessageDialog(Ui.ownerWindow(root), sb.toString(), "So sánh với Bản gốc (" + diffCount + " dòng khác)",
                    JOptionPane.INFORMATION_MESSAGE);
        }));

        resetOrigBtn.addActionListener(Ui.safe(e -> {
            requestArea.setText(originalReqText);
            requestArea.setCaretPosition(0);
            protoCombo.setSelectedItem(api.service().secure() ? "https" : "http");
            hostField.setText(api.service().host());
            portSpinner.setValue(api.service().port());
        }));

        // Helper to resolve current target Service & normalized request bytes
        java.util.function.Supplier<Service> currentService = () -> {
            String host = hostField.getText().trim();
            if (host.isEmpty()) host = api.service().host();
            int port = ((Number) portSpinner.getValue()).intValue();
            boolean secure = "https".equalsIgnoreCase(String.valueOf(protoCombo.getSelectedItem()));
            return new Service(host, port, secure);
        };

        java.util.function.Supplier<byte[]> currentRequestBytes = () -> {
            byte[] raw = requestArea.getText().getBytes(StandardCharsets.ISO_8859_1);
            return autoLenChk.isSelected() ? recalculateContentLength(raw) : raw;
        };

        // ---- Bottom CRUD Action Bar
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(4, 10, 0, 10));

        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leftActions.setOpaque(false);
        JButton sendBtn = Ui.solidPrimary("Gửi ngay (Ctrl+Enter)", t -> Icons.send(t.accent.onSolid()), null);
        JButton saveOverwriteBtn = Ui.secondaryBtn("Lưu đè vào API này", t -> Icons.check(t.success.text()));
        JButton cloneApiBtn = Ui.secondaryBtn("Lưu thành API mới trong nhánh", t -> Icons.plus(t.accent.text()));
        JButton repeaterBtn = Ui.secondaryBtn("Đẩy sang Repeater", t -> Icons.repeater(t.fg));
        leftActions.add(sendBtn);
        leftActions.add(saveOverwriteBtn);
        leftActions.add(cloneApiBtn);
        leftActions.add(repeaterBtn);

        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightActions.setOpaque(false);
        JButton closeBtn = Ui.secondaryBtn("Đóng", null);
        closeBtn.addActionListener(Ui.safe(e -> {
            if (onClose != null) onClose.run();
        }));
        rightActions.add(closeBtn);

        footer.add(leftActions, BorderLayout.WEST);
        footer.add(rightActions, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        // ---- Send Handler (runs on background thread, never blocks EDT)
        sendBtn.addActionListener(Ui.safe(e -> {
            if (sendingNow[0]) return;
            Service svc = currentService.get();
            byte[] reqBytes = currentRequestBytes.get();
            if (autoLenChk.isSelected()) {
                requestArea.setText(new String(reqBytes, StandardCharsets.ISO_8859_1));
            }
            int roleIdx = roleCombo.getSelectedIndex();
            ActionEngine.ActionMode sendMode = switch (actionModeCombo.getSelectedIndex()) {
                case 1 -> ActionEngine.ActionMode.LIVE_ID_POOL;
                case 2 -> ActionEngine.ActionMode.PRODUCER_RECREATE;
                default -> ActionEngine.ActionMode.NONE;
            };
            List<Role> targetRoles = new ArrayList<>();
            if (roleIdx == 0) {
                targetRoles.add(null); // Raw
            } else if (roleIdx == 1) {
                targetRoles.add(engine.originalProfile());
            } else if (roleIdx == 2) {
                List<Role> enabled = engine.roles().enabled();
                if (enabled.isEmpty()) {
                    targetRoles.add(null);
                } else {
                    targetRoles.addAll(enabled);
                }
            } else {
                int listPos = roleIdx - 3;
                if (listPos >= 0 && listPos < allRoles.size()) {
                    targetRoles.add(allRoles.get(listPos));
                } else {
                    targetRoles.add(null);
                }
            }

            sendingNow[0] = true;
            sendBtn.setEnabled(false);
            statusPill.setText("Đang gửi...");
            statusPill.setToken(t -> t.info);

            Thread worker = new Thread(() -> {
                List<Engine.AdHocSendResult> results = new ArrayList<>();
                for (Role r : targetRoles) {
                    results.add(engine.sendAdHocRequest(svc, reqBytes, r, api, sendMode));
                }
                SwingUtilities.invokeLater(() -> {
                    sendingNow[0] = false;
                    sendBtn.setEnabled(true);
                    Engine.AdHocSendResult last = null;
                    for (Engine.AdHocSendResult res : results) {
                        last = res;
                        history.add(new SendHistoryEntry(
                                history.size() + 1,
                                System.currentTimeMillis(),
                                res.roleLabel(),
                                res.service(),
                                res.requestSent(),
                                res.response(),
                                res.status(),
                                res.bodyLength(),
                                res.elapsedMs(),
                                res.error()));
                    }
                    refreshHistory.run();
                    if (last != null) {
                        lastResponseHolder[0] = last.response();
                        if (last.response() != null && last.response().length > 0) {
                            renderResponseText.run();
                        } else {
                            responseArea.setText("Lỗi gửi: " + last.error());
                        }
                        updateStatusPill(statusPill, respMetaLbl, last.status(), last.elapsedMs(),
                                last.bodyLength(), last.roleLabel(), last.error());
                    }
                    if (ownerUi != null) {
                        ownerUi.tick(true);
                    }
                });
            }, "JCrawl-composer-send");
            worker.setDaemon(true);
            worker.start();
        }));

        // ---- CRUD Save Overwrite (Update)
        saveOverwriteBtn.addActionListener(Ui.safe(e -> {
            Service svc = currentService.get();
            byte[] reqBytes = currentRequestBytes.get();
            boolean ok = engine.updateApiRequestFromComposer(api, initialRole, svc, reqBytes, lastResponseHolder[0]);
            if (ok) {
                if (ownerUi != null) {
                    ownerUi.tick(true);
                    ownerUi.flash("Đã lưu đè Request vào " + Text.apiLabel(api.gid()));
                }
                statusPill.setText("Đã lưu đè vào " + Text.apiLabel(api.gid()));
                statusPill.setToken(t -> t.success);
            }
        }));

        // ---- CRUD Clone as New API (Create)
        cloneApiBtn.addActionListener(Ui.safe(e -> {
            Service svc = currentService.get();
            byte[] reqBytes = currentRequestBytes.get();
            ApiGroup created = engine.cloneApiFromComposer(svc, reqBytes, lastResponseHolder[0], api.branchId(), "Nhân bản từ #" + api.gid());
            if (created != null) {
                if (ownerUi != null) {
                    ownerUi.tick(true);
                    ownerUi.flash("Đã tạo API mới " + Text.apiLabel(created.gid()) + " trong nhánh " + created.branch());
                }
                statusPill.setText("Đã tạo API mới " + Text.apiLabel(created.gid()));
                statusPill.setToken(t -> t.success);
            }
        }));

        // ---- Push to Burp Repeater
        repeaterBtn.addActionListener(Ui.safe(e -> {
            Service svc = currentService.get();
            byte[] reqBytes = currentRequestBytes.get();
            String tabTitle = Text.groupId(api.gid()) + " Composer " + Text.shortPath(api.path(), 20);
            engine.pushRawToRepeater(svc, reqBytes, tabTitle);
            if (ownerUi != null) {
                ownerUi.flash("Đã đẩy Request vừa sửa sang Burp Repeater");
            }
        }));

        return new View(root, requestArea, responseArea, historyTable, sendBtn,
                saveOverwriteBtn, cloneApiBtn, statusPill, roleCombo, actionModeCombo, refreshHistory);
    }

    private static void updateStatusPill(
            Pill statusPill, JLabel respMetaLbl, Integer st, long ms, int bodyLen, String roleLabel, String err) {
        if (st == null) {
            statusPill.setText("Lỗi gửi");
            statusPill.setToken(t -> t.danger);
            respMetaLbl.setText("Response [" + roleLabel + "] — " + (err != null ? err : "Không phản hồi"));
            return;
        }
        statusPill.setText("HTTP " + st + " · " + ms + " ms · " + bodyLen + " B");
        if (st >= 200 && st < 300) {
            statusPill.setToken(t -> t.success);
        } else if (st >= 400 && st < 500) {
            statusPill.setToken(t -> t.warning);
        } else if (st >= 500) {
            statusPill.setToken(t -> t.danger);
        } else {
            statusPill.setToken(t -> t.info);
        }
        respMetaLbl.setText("Response [" + roleLabel + "] — HTTP " + st + " (" + ms + " ms, " + bodyLen + " bytes)");
    }

    /** Formats the JSON body portion of a raw HTTP message while leaving headers untouched. */
    public static String formatHttpWithPrettyJson(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        int sep = HttpBytes.bodyOffset(raw);
        if (sep < 0 || sep >= raw.length) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        String head = new String(raw, 0, sep, StandardCharsets.ISO_8859_1);
        String body = new String(raw, sep, raw.length - sep, StandardCharsets.UTF_8).trim();
        if (body.isEmpty() || !(body.startsWith("{") || body.startsWith("["))) {
            return new String(raw, StandardCharsets.UTF_8);
        }
        try {
            Object parsed = Json.parse(body);
            String pretty = Json.pretty(parsed);
            return head + pretty;
        } catch (RuntimeException ignored) {
            return new String(raw, StandardCharsets.UTF_8);
        }
    }

    /** Formats a raw HTTP string's JSON body; returns null if there is no valid JSON body. */
    public static String formatJsonBodyInRawHttp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        int sep = HttpBytes.bodyOffset(bytes);
        if (sep < 0 || sep >= bytes.length) {
            return null;
        }
        String head = new String(bytes, 0, sep, StandardCharsets.ISO_8859_1);
        String body = new String(bytes, sep, bytes.length - sep, StandardCharsets.UTF_8).trim();
        if (body.isEmpty() || !(body.startsWith("{") || body.startsWith("["))) {
            return null;
        }
        try {
            Object parsed = Json.parse(body);
            return head + Json.pretty(parsed);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Recalculates Content-Length header to match exact byte length of the HTTP body. */
    public static byte[] recalculateContentLength(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return new byte[0];
        }
        int bodyOff = HttpBytes.bodyOffset(raw);
        if (bodyOff <= 0 || bodyOff > raw.length) {
            return raw;
        }
        int bodyLen = raw.length - bodyOff;
        String head = new String(raw, 0, bodyOff, StandardCharsets.ISO_8859_1);
        java.util.regex.Pattern clPat = java.util.regex.Pattern.compile("(?im)^Content-Length\\s*:\\s*\\d+\\r?$");
        java.util.regex.Matcher m = clPat.matcher(head);
        String newHead;
        if (m.find()) {
            boolean crlf = head.contains("\r\n");
            newHead = m.replaceFirst("Content-Length: " + bodyLen + (crlf ? "\r" : ""));
        } else if (bodyLen > 0) {
            boolean crlf = head.contains("\r\n");
            String sep = crlf ? "\r\n\r\n" : "\n\n";
            int sepIdx = head.lastIndexOf(sep);
            if (sepIdx >= 0) {
                newHead = head.substring(0, sepIdx) + (crlf ? "\r\n" : "\n")
                        + "Content-Length: " + bodyLen + sep;
            } else {
                newHead = head;
            }
        } else {
            newHead = head;
        }
        byte[] headBytes = newHead.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[headBytes.length + bodyLen];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        if (bodyLen > 0) {
            System.arraycopy(raw, bodyOff, out, headBytes.length, bodyLen);
        }
        return out;
    }
}
