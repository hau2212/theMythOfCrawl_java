package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.collab.ConflictRecord;
import themythofcrawl.collab.GoogleSheetsBridge;
import themythofcrawl.collab.InventoryCatalog;
import themythofcrawl.collab.TeamSyncService;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Floating 4-tab Collaboration &amp; Online Sync Hub (V_5.0_beta):
 * <ol>
 *   <li>Nhóm &amp; Đồng bộ Real-time (Host / Client + Member Identity + Live Presence)</li>
 *   <li>Google Sheets Online (2-way Apps Script WebApp Bridge + 1-click Copy Script)</li>
 *   <li>Quản lý Tag tùy chỉnh (Create / Delete custom tags synced across team &amp; Excel)</li>
 *   <li>Giải quyết Xung đột kiểu Git (Side-by-Side Diff + 4 Resolution Actions)</li>
 * </ol>
 */
final class TeamCollabDialog {

    static final int TAB_TEAM = 0;
    static final int TAB_GSHEETS = 1;
    static final int TAB_TAGS = 2;
    static final int TAB_CONFLICTS = 3;

    private TeamCollabDialog() {
    }

    static void show(Component parent, Engine engine, int initialTab, Runnable onChanged) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dlg = new JDialog(Ui.ownerWindow(owner), "Đồng bộ Nhóm · Google Sheets · Tag & Xung đột (V_5.0_beta)",
                JDialog.ModalityType.MODELESS);
        dlg.setLayout(new BorderLayout());
        dlg.add(buildPanel(dlg, engine, initialTab, onChanged, dlg::dispose), BorderLayout.CENTER);
        Theme.track(dlg);
        dlg.setMinimumSize(new Dimension(820, 560));
        dlg.setSize(new Dimension(940, 640));
        dlg.setLocationRelativeTo(owner);
        Ui.fastScroll(dlg.getContentPane());
        dlg.setVisible(true);
    }

    static JPanel buildPanel(Component parent, Engine engine, int initialTab, Runnable onChanged, Runnable onClose) {
        InventoryCatalog catalog = engine.inventoryCatalog();
        TeamSyncService sync = engine.teamSync();
        GoogleSheetsBridge gsheets = engine.googleSheets();

        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setBorder(BorderFactory.createEmptyBorder(0, 0, Theme.SPACE_2, 0));
        root.add(Ui.dialogHeader(
                "Trung tâm Đồng bộ Nhóm, Google Sheets & Xung đột (V_5.0_beta)",
                "Cộng tác real-time giữa nhiều Extension (Host/Client) · Đồng bộ 2 chiều Google Sheets tô màu · Quản lý Tag & Git-style Conflict."),
                BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBorder(BorderFactory.createEmptyBorder(0, Theme.SPACE_2, 0, Theme.SPACE_2));
        tabs.addTab("1. Nhóm & Real-time", buildTeamTab(parent, engine, catalog, sync, onChanged));
        tabs.addTab("2. Google Sheets Online", buildSheetsTab(parent, engine, gsheets, onChanged));
        tabs.addTab("3. Tag tùy chỉnh", buildTagsTab(parent, engine, catalog, sync, onChanged));
        tabs.addTab("4. Xung đột (" + catalog.conflictCount() + ")", buildConflictsTab(parent, engine, catalog, sync, onChanged));

        if (initialTab >= 0 && initialTab < tabs.getTabCount()) {
            tabs.setSelectedIndex(initialTab);
        }
        root.add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, Theme.SPACE_3, 0, Theme.SPACE_3));
        footer.add(Ui.muted("Mọi thay đổi trạng thái, Tag và kết quả chốt Conflict tự động đồng bộ sang đồng đội & Google Sheets."), BorderLayout.WEST);
        if (onClose != null) {
            footer.add(Ui.secondaryBtn("Đóng", null, onClose), BorderLayout.EAST);
        }
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    // ------------------------------------------------------------------ Tab 1: Team Real-time Hub

    private static JPanel buildTeamTab(Component parent, Engine engine, InventoryCatalog catalog,
                                       TeamSyncService sync, Runnable onChanged) {
        Theme th = Theme.current();
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        p.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        // Top: Member identity + live status pill
        JPanel idCard = new JPanel(new BorderLayout(8, 0));
        idCard.setOpaque(true);
        idCard.setBackground(th.surfaceAlt);
        idCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        JTextField memberField = new JTextField(catalog.memberName(), 12);
        JButton saveMemberBtn = Ui.solidPrimary("Lưu tên tôi", t -> Icons.check(t.accent.onSolid()), null);
        Pill modePill = new Pill(sync.mode().label, t -> sync.mode() == TeamSyncService.Mode.OFFLINE ? t.neutral : t.success);
        JLabel statusLbl = Ui.muted(sync.lastStatus());

        saveMemberBtn.addActionListener(Ui.safe(e -> {
            String nm = memberField.getText().trim();
            if (!nm.isEmpty()) {
                catalog.setMemberName(nm);
                engine.saveCollabPrefs();
                sync.updateLocalPresence(engine.activeBranch().name(), "");
                if (onChanged != null) onChanged.run();
                JOptionPane.showMessageDialog(Ui.ownerWindow(parent), "Đã đặt tên thành viên: " + nm
                        + " (các API bạn tìm sẽ hiện Done (" + nm + ") / Info (" + nm + ")).",
                        "Định danh thành viên", JOptionPane.INFORMATION_MESSAGE);
            }
        }));

        idCard.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Tên viết tắt của bạn (hiện trên Excel):"), memberField, saveMemberBtn), BorderLayout.WEST);
        idCard.add(Ui.flow(FlowLayout.RIGHT, statusLbl, modePill), BorderLayout.EAST);
        p.add(idCard, BorderLayout.NORTH);

        // Middle: 2-column Host vs Join cards
        JPanel cards = new JPanel(new GridLayout(1, 2, 10, 0));
        cards.setOpaque(false);

        // Left: Host Room
        JPanel hostCard = new JPanel(new GridBagLayout());
        hostCard.setOpaque(true);
        hostCard.setBackground(th.surface);
        hostCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        JTextField portField = new JTextField(sync.boundPort() > 0 ? String.valueOf(sync.boundPort()) : "18765", 8);
        JTextField hostPassField = new JTextField(sync.passcode().isEmpty() ? TeamSyncService.randomPasscode() : sync.passcode(), 14);
        JTextField shareUrlField = new JTextField(sync.mode() == TeamSyncService.Mode.HOST ? sync.roomUrl() : "(Chưa mở phòng)", 22);
        shareUrlField.setEditable(false);
        javax.swing.JCheckBox lanBox = new javax.swing.JCheckBox("Cho máy khác trong LAN kết nối trực tiếp (không khuyến nghị)", false);
        lanBox.setOpaque(false);
        lanBox.setToolTipText("Mặc định phòng chỉ nghe 127.0.0.1. Mở cổng cho LAN có thể bị SOC/EDR coi là dịch vụ lạ. "
                + "Cách an toàn hơn: dùng 'Đồng bộ qua thư mục chung' hoặc Google Sheets / Excel nhóm.");
        JButton startHostBtn = Ui.solidPrimary("Mở Phòng nhóm (Host)", t -> Icons.playTest(t.accent.onSolid(), 13), null);
        JButton copyRoomBtn = Ui.secondaryBtn("Copy URL", Icons::copy, () -> Ui.copyToClipboard(shareUrlField.getText()));
        JButton stopBtn = Ui.dangerBtn("Dừng / Ngắt kết nối", t -> Icons.stop(t.danger.text()), null);

        gc.gridy = 0; gc.gridx = 0; gc.gridwidth = 2; hostCard.add(Ui.bold("Cách 1 — Bạn làm Host (Mở phòng cho đồng đội vào)"), gc);
        gc.gridwidth = 1;
        gc.gridy = 1; gc.gridx = 0; hostCard.add(Ui.muted("Cổng (Port):"), gc);
        gc.gridx = 1; gc.weightx = 1; hostCard.add(portField, gc);
        gc.gridy = 2; gc.gridx = 0; gc.weightx = 0; hostCard.add(Ui.muted("Passcode phòng:"), gc);
        gc.gridx = 1; gc.weightx = 1; hostCard.add(hostPassField, gc);
        gc.gridy = 3; gc.gridx = 0; gc.weightx = 0; hostCard.add(Ui.muted("URL chia sẻ:"), gc);
        gc.gridx = 1; gc.weightx = 1; hostCard.add(shareUrlField, gc);
        gc.gridy = 4; gc.gridx = 0; gc.gridwidth = 2;
        hostCard.add(lanBox, gc);
        gc.gridy = 5; gc.gridx = 0; gc.gridwidth = 2;
        hostCard.add(Ui.flow(FlowLayout.LEFT, startHostBtn, copyRoomBtn, stopBtn), gc);

        // Right: Join Room
        JPanel joinCard = new JPanel(new GridBagLayout());
        joinCard.setOpaque(true);
        joinCard.setBackground(th.surface);
        joinCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        GridBagConstraints jc = new GridBagConstraints();
        jc.insets = new Insets(4, 4, 4, 4);
        jc.fill = GridBagConstraints.HORIZONTAL;
        jc.anchor = GridBagConstraints.WEST;

        JTextField joinUrlField = new JTextField(sync.roomUrl().isEmpty() ? "http://192.168.1.10:18765" : sync.roomUrl(), 24);
        JTextField joinPassField = new JTextField(sync.passcode(), 14);
        JButton joinBtn = Ui.solidPrimary("Tham gia Phòng", t -> Icons.arrowRight(t.accent.onSolid()), null);

        jc.gridy = 0; jc.gridx = 0; jc.gridwidth = 2; joinCard.add(Ui.bold("Cách 2 — Tham gia Phòng của đồng đội (Client)"), jc);
        jc.gridwidth = 1;
        jc.gridy = 1; jc.gridx = 0; joinCard.add(Ui.muted("Room URL (LAN / Tunnel):"), jc);
        jc.gridx = 1; jc.weightx = 1; joinCard.add(joinUrlField, jc);
        jc.gridy = 2; jc.gridx = 0; jc.weightx = 0; joinCard.add(Ui.muted("Passcode phòng:"), jc);
        jc.gridx = 1; jc.weightx = 1; joinCard.add(joinPassField, jc);
        jc.gridy = 3; jc.gridx = 0; jc.gridwidth = 2;
        joinCard.add(Ui.muted("Hỗ trợ cả mạng LAN (http://IP:18765) lẫn URL Tunnel (Cloudflare / ngrok)."), jc);
        jc.gridy = 4; jc.gridx = 0; jc.gridwidth = 2;
        joinCard.add(Ui.flow(FlowLayout.LEFT, joinBtn), jc);

        cards.add(hostCard);
        cards.add(joinCard);

        // Bottom: Active Peers Presence table
        DefaultTableModel peerModel = new DefaultTableModel(new String[] {"Thành viên", "Đang làm ở Nhánh", "API đang mở"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable peerTable = new ThemedTable(peerModel);
        Ui.style(peerTable);
        JScrollPane peerScroll = new JScrollPane(peerTable);
        peerScroll.setBorder(BorderFactory.createTitledBorder("Đồng đội đang Online (Live Presence)"));
        peerScroll.setPreferredSize(new Dimension(400, 150));

        Runnable refreshTeam = () -> {
            modePill.setText(sync.mode().label);
            modePill.setToken(sync.mode() == TeamSyncService.Mode.OFFLINE ? t -> t.neutral : t -> t.success);
            statusLbl.setText(sync.lastStatus());
            if (sync.mode() == TeamSyncService.Mode.HOST) {
                shareUrlField.setText(sync.roomUrl());
            }
            peerModel.setRowCount(0);
            for (TeamSyncService.Presence pr : sync.activePeers()) {
                peerModel.addRow(new Object[] {pr.member(), pr.branch(), pr.activeEndpoint()});
            }
            if (onChanged != null) onChanged.run();
        };

        startHostBtn.addActionListener(Ui.safe(e -> {
            try {
                int port = Integer.parseInt(portField.getText().trim());
                if (lanBox.isSelected() && JOptionPane.showConfirmDialog(Ui.ownerWindow(parent),
                        "Mở cổng " + port + " cho toàn mạng LAN?\n\n"
                                + "Một dịch vụ lạ nghe kết nối từ trong Burp + đồng đội hỏi dữ liệu liên tục\n"
                                + "có thể bị SOC / EDR đánh giá là bất thường (giống beaconing).\n"
                                + "Chỉ làm khi đã được bộ phận an ninh cho phép.",
                        "Cảnh báo mở cổng LAN", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                        != JOptionPane.OK_OPTION) {
                    return;
                }
                sync.startHost(port, hostPassField.getText().trim(), lanBox.isSelected());
                refreshTeam.run();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(Ui.ownerWindow(parent), "Không mở được phòng: " + ex.getMessage(),
                        "Lỗi mở phòng", JOptionPane.ERROR_MESSAGE);
            }
        }));
        joinBtn.addActionListener(Ui.safe(e -> {
            try {
                sync.connectClient(joinUrlField.getText().trim(), joinPassField.getText().trim());
                refreshTeam.run();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(Ui.ownerWindow(parent), "Không kết nối được phòng: " + ex.getMessage(),
                        "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
            }
        }));
        stopBtn.addActionListener(Ui.safe(e -> {
            engine.stopTeamSync();
            shareUrlField.setText("(Đã dừng)");
            refreshTeam.run();
        }));

        // V_7.0 Cách 0 (khuyến nghị): shared folder synced by the company's own client — no port, no network from Burp
        JPanel folderCard = new JPanel(new BorderLayout(6, 4));
        folderCard.setOpaque(true);
        folderCard.setBackground(th.success.fill());
        folderCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.success.border()),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        String savedFolder = engine.teamFolder();
        JTextField folderField = new JTextField(savedFolder == null ? "" : savedFolder, 30);
        JButton pickFolder = Ui.secondaryBtn("Chọn thư mục...", t -> Icons.folder(t.fg), () -> {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser(folderField.getText().isBlank() ? null : folderField.getText());
            fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(parent) == javax.swing.JFileChooser.APPROVE_OPTION) {
                folderField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        JButton startFolder = Ui.solidPrimary("Đồng bộ qua thư mục này", t -> Icons.playTest(t.accent.onSolid(), 13), null);
        JButton readNow = Ui.secondaryBtn("Đọc ngay", t -> Icons.refresh(t.fg, 13), null);
        folderCard.add(Ui.bold("Cách 0 (khuyến nghị, an toàn với SOC) — Thư mục chung OneDrive / SharePoint / Google Drive / ổ mạng"),
                BorderLayout.NORTH);
        folderCard.add(Ui.flow(FlowLayout.LEFT, Ui.muted("Thư mục:"), folderField, pickFolder, startFolder, readNow),
                BorderLayout.CENTER);
        folderCard.add(Ui.muted("Burp KHÔNG mở cổng, KHÔNG gọi mạng: mỗi người ghi file riêng jcrawl-team/<tên>.jsonl, "
                + "client đồng bộ của công ty lo phần còn lại. Chỉ chia sẻ metadata (không token / cookie)."),
                BorderLayout.SOUTH);
        startFolder.addActionListener(Ui.safe(e -> {
            try {
                if (folderField.getText().isBlank()) {
                    throw new IllegalArgumentException("Chưa chọn thư mục chung");
                }
                engine.startTeamFolder(java.nio.file.Path.of(folderField.getText().trim()));
                refreshTeam.run();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(Ui.ownerWindow(parent), "Không dùng được thư mục: " + ex.getMessage(),
                        "Lỗi thư mục nhóm", JOptionPane.ERROR_MESSAGE);
            }
        }));
        readNow.addActionListener(Ui.safe(e -> {
            try {
                sync.pollFolderNow();
            } catch (Exception ex) {
                statusLbl.setText("Lỗi đọc: " + ex.getMessage());
            }
            refreshTeam.run();
        }));
        // V_7.0 Cách B: Excel nhóm — offline workbook per member, merged by anyone (no network at all)
        JButton exportWb = Ui.secondaryBtn("Xuất Excel nhóm (6 sheet)...", t -> Icons.excel(t.fg, 13), () -> {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setSelectedFile(new java.io.File("JCrawl_team_" + TeamSyncService.safeMemberFile(engine.inventoryCatalog().memberName())
                    + ".xlsx"));
            if (fc.showSaveDialog(parent) != javax.swing.JFileChooser.APPROVE_OPTION) {
                return;
            }
            try {
                java.nio.file.Files.write(fc.getSelectedFile().toPath(), themythofcrawl.collab.TeamWorkbook.export(engine));
                statusLbl.setText("Đã xuất Excel nhóm: " + fc.getSelectedFile().getName());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(Ui.ownerWindow(parent), "Xuất lỗi: " + ex.getMessage(), "Excel nhóm", JOptionPane.ERROR_MESSAGE);
            }
        });
        JButton mergeWb = Ui.secondaryBtn("Gộp Excel của đồng đội...", t -> Icons.dup(t.fg, 13), () -> {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setMultiSelectionEnabled(true);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Excel nhóm (.xlsx)", "xlsx"));
            if (fc.showOpenDialog(parent) != javax.swing.JFileChooser.APPROVE_OPTION) {
                return;
            }
            try {
                java.util.List<java.nio.file.Path> files = new java.util.ArrayList<>();
                for (java.io.File f : fc.getSelectedFiles()) {
                    files.add(f.toPath());
                }
                var rep = themythofcrawl.collab.TeamWorkbook.merge(engine, files);
                statusLbl.setText(rep.summary());
                refreshTeam.run();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(Ui.ownerWindow(parent), "Gộp lỗi: " + ex.getMessage(), "Excel nhóm", JOptionPane.ERROR_MESSAGE);
            }
        });
        JPanel wbRow = Ui.flow(FlowLayout.LEFT, Ui.bold("Cách B — Excel nhóm (không cần mạng):"), exportWb, mergeWb,
                Ui.muted("Mỗi người xuất 1 file, một người gộp lại: trạng thái, người tìm, tag, ghi chú, xung đột."));
        JPanel folderAndWb = new JPanel(new BorderLayout(0, 4));
        folderAndWb.setOpaque(false);
        folderAndWb.add(folderCard, BorderLayout.NORTH);
        folderAndWb.add(wbRow, BorderLayout.SOUTH);
        JPanel cardsWrap = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        cardsWrap.setOpaque(false);
        cardsWrap.add(folderAndWb, BorderLayout.NORTH);
        cardsWrap.add(cards, BorderLayout.CENTER);

        JPanel center = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        center.setOpaque(false);
        center.add(cardsWrap, BorderLayout.NORTH);
        center.add(peerScroll, BorderLayout.CENTER);
        p.add(center, BorderLayout.CENTER);
        refreshTeam.run();
        return p;
    }

    // ------------------------------------------------------------------ Tab 2: Google Sheets Online

    private static JPanel buildSheetsTab(Component parent, Engine engine, GoogleSheetsBridge gsheets, Runnable onChanged) {
        Theme th = Theme.current();
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        p.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        JPanel cfg = new JPanel(new GridBagLayout());
        cfg.setOpaque(true);
        cfg.setBackground(th.surfaceAlt);
        cfg.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        JTextField urlField = new JTextField(gsheets.webAppUrl(), 42);
        urlField.putClientProperty("JTextField.placeholderText", "https://script.google.com/macros/s/.../exec");
        JCheckBox autoChk = new JCheckBox("Tự động đẩy lên Google Sheets mỗi 3 giây khi có thay đổi", gsheets.isAutoSync());
        autoChk.setOpaque(false);
        JLabel stLbl = Ui.muted(gsheets.lastStatus());

        JButton saveBtn = Ui.solidPrimary("Lưu cấu hình", t -> Icons.save(t.accent.onSolid()), null);
        JButton pushBtn = Ui.tonalBtn("Đẩy lên Sheet ngay (Kèm tô màu)", t -> Icons.upload(t.success.text()), t -> t.success);
        JButton pullBtn = Ui.secondaryBtn("Kéo từ Sheet về", Icons::download, null);
        JButton copyScriptBtn = Ui.secondaryBtn("Copy mã Google Apps Script", Icons::copy, () -> {
            Ui.copyToClipboard(GoogleSheetsBridge.APPS_SCRIPT_TEMPLATE);
            stLbl.setText("Đã copy mã Apps Script! Dán vào Google Sheet -> Tiện ích mở rộng -> Apps Script -> Deploy Web App.");
        });

        saveBtn.addActionListener(Ui.safe(e -> {
            gsheets.configure(urlField.getText().trim(), autoChk.isSelected(), engine);
            engine.saveCollabPrefs();
            stLbl.setText(gsheets.lastStatus());
            if (onChanged != null) onChanged.run();
        }));
        pushBtn.addActionListener(Ui.safe(e -> {
            gsheets.configure(urlField.getText().trim(), autoChk.isSelected(), engine);
            engine.saveCollabPrefs();
            stLbl.setText("Đang đẩy dữ liệu & tô màu lên Google Sheets...");
            Thread t = new Thread(() -> {
                try {
                    int n = gsheets.pushAll(engine);
                    SwingUtilities.invokeLater(() -> {
                        stLbl.setText("Thành công: Đã đẩy " + n + " dòng + tô màu lên Google Sheets.");
                        if (onChanged != null) onChanged.run();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> stLbl.setText("Lỗi đẩy Sheet: " + ex.getMessage()));
                }
            }, "JCrawl-gsheets-push");
            t.setDaemon(true);
            t.start();
        }));
        pullBtn.addActionListener(Ui.safe(e -> {
            gsheets.configure(urlField.getText().trim(), autoChk.isSelected(), engine);
            stLbl.setText("Đang kéo dữ liệu từ Google Sheets...");
            Thread t = new Thread(() -> {
                try {
                    int n = gsheets.pullAll();
                    SwingUtilities.invokeLater(() -> {
                        stLbl.setText("Thành công: Đã kéo " + n + " dòng từ Google Sheets.");
                        if (onChanged != null) onChanged.run();
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> stLbl.setText("Lỗi kéo Sheet: " + ex.getMessage()));
                }
            }, "JCrawl-gsheets-pull");
            t.setDaemon(true);
            t.start();
        }));

        gc.gridy = 0; gc.gridx = 0; cfg.add(Ui.bold("Web App URL:"), gc);
        gc.gridx = 1; gc.weightx = 1; cfg.add(urlField, gc);
        gc.gridy = 1; gc.gridx = 0; gc.gridwidth = 2; cfg.add(autoChk, gc);
        gc.gridy = 2; cfg.add(Ui.flow(FlowLayout.LEFT, saveBtn, pushBtn, pullBtn, copyScriptBtn), gc);
        gc.gridy = 3; cfg.add(stLbl, gc);
        p.add(cfg, BorderLayout.NORTH);

        JTextArea scriptPreview = new JTextArea(GoogleSheetsBridge.APPS_SCRIPT_TEMPLATE);
        scriptPreview.setEditable(false);
        scriptPreview.setFont(th.monoSmallFont);
        scriptPreview.setCaretPosition(0);
        JScrollPane sc = new JScrollPane(scriptPreview);
        sc.setBorder(BorderFactory.createTitledBorder("Mã Google Apps Script mẫu (Cài đặt 1 lần trong 60 giây — tự tô màu Xanh/Vàng/Đỏ/Xám)"));
        p.add(sc, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------ Tab 3: Custom Tags CRUD

    private static JPanel buildTagsTab(Component parent, Engine engine, InventoryCatalog catalog,
                                       TeamSyncService sync, Runnable onChanged) {
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        p.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        DefaultListModel<String> listModel = new DefaultListModel<>();
        Runnable reload = () -> {
            listModel.clear();
            for (String t : catalog.allTags()) {
                listModel.addElement(t);
            }
        };
        reload.run();

        JTextField newTagField = new JTextField(22);
        newTagField.putClientProperty("JTextField.placeholderText", "VD: IDOR-Confirmed, Payment, NeedDevCheck...");
        JButton addBtn = Ui.solidPrimary("+ Tạo Tag mới", t -> Icons.plus(t.accent.onSolid()), () -> {
            String t = newTagField.getText().trim();
            if (!t.isEmpty() && catalog.addCustomTag(t)) {
                engine.saveCollabPrefs();
                sync.broadcastCustomTag(t);
                newTagField.setText("");
                reload.run();
                if (onChanged != null) onChanged.run();
            }
        });
        JList<String> tagList = new JList<>(listModel);
        tagList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton delBtn = Ui.dangerBtn("Xóa Tag chọn", t -> Icons.trash(t.danger.text()), () -> {
            String sel = tagList.getSelectedValue();
            if (sel == null) {
                Ui.hint(tagList, "Chọn 1 tag trong danh sách trước");
            } else if (catalog.removeCustomTag(sel)) {
                engine.saveCollabPrefs();
                reload.run();
                if (onChanged != null) onChanged.run();
            }
        });

        p.add(Ui.flow(FlowLayout.LEFT, Ui.bold("Tên Tag mới:"), newTagField, addBtn, delBtn), BorderLayout.NORTH);
        JScrollPane sc = new JScrollPane(tagList);
        sc.setBorder(BorderFactory.createTitledBorder("Danh sách Tag hiện có (Tự đồng bộ giữa các thành viên & xuất vào cột Tags của Excel)"));
        p.add(sc, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------ Tab 4: Git-Style Conflict Resolver

    private static JPanel buildConflictsTab(Component parent, Engine engine, InventoryCatalog catalog,
                                            TeamSyncService sync, Runnable onChanged) {
        Theme th = Theme.current();
        JPanel p = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        p.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        DefaultTableModel tm = new DefaultTableModel(
                new String[] {"Method", "Host", "Path Template", "Bản của tôi", "Bản đồng đội"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tbl = new ThemedTable(tm);
        Ui.style(tbl);
        tbl.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JTextArea localArea = new JTextArea(6, 30);
        JTextArea remoteArea = new JTextArea(6, 30);
        for (JTextArea a : List.of(localArea, remoteArea)) {
            a.setEditable(false);
            a.setFont(th.monoSmallFont);
            a.setLineWrap(true);
        }
        JLabel suggestLbl = Ui.bold("Chọn 1 dòng xung đột ở trên để so sánh kiểu Git Diff.");

        List<ConflictRecord> displayed = new ArrayList<>();
        Runnable reload = () -> {
            displayed.clear();
            displayed.addAll(catalog.openConflicts());
            tm.setRowCount(0);
            for (ConflictRecord cr : displayed) {
                tm.addRow(new Object[] {
                        cr.method(), cr.host(), cr.pathTemplate(),
                        cr.localSide().summary(), cr.remoteSide().summary()
                });
            }
            if (displayed.isEmpty()) {
                localArea.setText("Không có xung đột nào đang chờ xử lý.");
                remoteArea.setText("Mọi dữ liệu giữa các thành viên đã đồng bộ sạch sẽ.");
                suggestLbl.setText("0 xung đột đang mở.");
            } else {
                tbl.setRowSelectionInterval(0, 0);
            }
        };

        tbl.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int r = tbl.getSelectedRow();
            if (r < 0 || r >= displayed.size()) {
                Ui.hint(tbl, displayed.isEmpty() ? "Không có xung đột nào cần xử lý" : "Chọn 1 xung đột trong bảng trước");
                return;
            }
            ConflictRecord cr = displayed.get(r);
            localArea.setText("Thành viên : " + cr.localSide().member() + "\n"
                    + "Trạng thái : " + cr.localSide().status().shortLabel + " (HTTP " + cr.localSide().httpStatus() + ")\n"
                    + "Nhánh UI   : " + cr.localSide().branch() + "\n"
                    + "Tags       : " + String.join(", ", cr.localSide().tags()) + "\n"
                    + "Ghi chú    : " + cr.localSide().note());
            remoteArea.setText("Thành viên : " + cr.remoteSide().member() + "\n"
                    + "Trạng thái : " + cr.remoteSide().status().shortLabel + " (HTTP " + cr.remoteSide().httpStatus() + ")\n"
                    + "Nhánh UI   : " + cr.remoteSide().branch() + "\n"
                    + "Tags       : " + String.join(", ", cr.remoteSide().tags()) + "\n"
                    + "Ghi chú    : " + cr.remoteSide().note());
            suggestLbl.setText(cr.autoSuggestion());
        });

        java.util.function.Consumer<ConflictRecord.Resolution> resolve = res -> {
            int r = tbl.getSelectedRow();
            if (r < 0 || r >= displayed.size()) return;
            ConflictRecord cr = displayed.get(r);
            InventoryCatalog.EndpointMeta updated = catalog.resolveConflict(cr.endpointKey(), res);
            if (updated != null) {
                sync.broadcastEndpoint(updated);
            }
            reload.run();
            if (onChanged != null) onChanged.run();
        };

        JPanel diffGrid = new JPanel(new GridLayout(1, 2, 8, 0));
        diffGrid.setOpaque(false);
        JScrollPane ls = new JScrollPane(localArea);
        ls.setBorder(BorderFactory.createTitledBorder("Bản của tôi (Local)"));
        JScrollPane rs = new JScrollPane(remoteArea);
        rs.setBorder(BorderFactory.createTitledBorder("Bản của đồng đội (Remote)"));
        diffGrid.add(ls);
        diffGrid.add(rs);

        JPanel actionBar = new JPanel(new BorderLayout(0, 6));
        actionBar.setOpaque(false);
        actionBar.add(suggestLbl, BorderLayout.NORTH);
        actionBar.add(Ui.flow(FlowLayout.RIGHT,
                Ui.secondaryBtn("Giữ bản tôi", null, () -> resolve.accept(ConflictRecord.Resolution.KEEP_LOCAL)),
                Ui.secondaryBtn("Giữ bản đồng đội", null, () -> resolve.accept(ConflictRecord.Resolution.KEEP_REMOTE)),
                Ui.solidPrimary("Gộp cả hai (Done A, B)", t -> Icons.check(t.accent.onSolid()),
                        () -> resolve.accept(ConflictRecord.Resolution.MERGE_BOTH)),
                Ui.secondaryBtn("Giữ cả 2 biến thể", null, () -> resolve.accept(ConflictRecord.Resolution.KEEP_BOTH_VARIANTS))),
                BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setOpaque(false);
        bottom.add(diffGrid, BorderLayout.CENTER);
        bottom.add(actionBar, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(tbl), bottom);
        split.setResizeWeight(0.45);
        p.add(split, BorderLayout.CENTER);
        reload.run();
        return p;
    }
}
