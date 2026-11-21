package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.core.Text;
import themythofcrawl.excel.PathMatch;
import themythofcrawl.excel.PlaceholderCatalog;
import themythofcrawl.excel.PlaceholderCatalog.MatchKind;
import themythofcrawl.excel.PlaceholderCatalog.PlaceholderDef;
import themythofcrawl.excel.TargetEntry;
import themythofcrawl.ui.kit.Card;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Interactive Excel {@code {placeholder}} manager, clickable path-segment template builder,
 * and Live Match Verifier. Allows the user to explicitly declare custom {@code {...}} tokens
 * (e.g. {@code /api/a/{session}/c}) and verify in real time that random segments match accurately.
 */
public final class ExcelPlaceholderDialog {

    private ExcelPlaceholderDialog() {
    }

    public static void show(Component parent, Engine engine, TargetEntry focusEntry, Runnable onChanged) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dlg = new JDialog(Ui.ownerWindow(owner), "Quản lý {Placeholder} & Xác minh khớp Excel",
                JDialog.ModalityType.APPLICATION_MODAL);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dlg.setContentPane(buildPanel(dlg, engine, focusEntry, onChanged, dlg::dispose));
        dlg.setMinimumSize(new Dimension(920, 620));
        dlg.setSize(1080, 700);
        dlg.setLocationRelativeTo(parent);
        Ui.fastScroll(dlg.getContentPane());
        dlg.setVisible(true);
    }

    /**
     * Reusable panel builder so both the modal dialog and headless UI tests can construct and
     * inspect the complete Placeholder Manager &amp; Live Match Verifier.
     */
    public static JPanel buildPanel(Component dialogParent, Engine engine, TargetEntry focusEntry,
                                    Runnable onChanged, Runnable onClose) {
        Theme t = Theme.current();
        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(Theme.current().bg);
            }
        };
        root.setOpaque(true);
        root.setBackground(t.bg);
        root.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        // ------------------------------------------------------------------ Top banner
        JPanel banner = new JPanel(new BorderLayout(Theme.SPACE_2, 2));
        banner.setOpaque(false);
        JLabel title = Ui.bold("Quản lý {Placeholder} & Xác minh khớp đường dẫn Excel");
        title.setIcon(Icons.themed(th -> Icons.codeBraces(th.accent.text(), 16)));
        title.setFont(t.headingFont);
        JLabel subtitle = Ui.muted(focusEntry != null
                ? "Đang chỉnh dòng Excel #" + focusEntry.sttText() + " (" + focusEntry.methodLabel()
                        + ") — Bấm vào bất kỳ đoạn đường dẫn nào để gắn thành {placeholder} và kiểm tra khớp ngay."
                : "Định nghĩa các thẻ {session}, {id}, {token} hoặc Regex tùy chỉnh rồi bấm từng đoạn đường dẫn để kiểm tra khớp trực tiếp.");
        banner.add(title, BorderLayout.NORTH);
        banner.add(subtitle, BorderLayout.CENTER);
        root.add(banner, BorderLayout.NORTH);

        // ------------------------------------------------------------------ Shared state & models
        String initialTemplate = focusEntry != null && !focusEntry.path().isBlank()
                ? focusEntry.path()
                : "/api/a/{session}/c";
        JTextField templateField = new JTextField(initialTemplate);
        templateField.setFont(t.monoFont);
        templateField.setPreferredSize(new Dimension(420, 28));

        JTextField sampleField = new JTextField(synthesizeSamplePath(initialTemplate, engine.placeholders()));
        sampleField.setFont(t.monoFont);
        sampleField.setPreferredSize(new Dimension(340, 28));

        JCheckBox prefixChk = new JCheckBox("Cho phép tiền tố (prefix)", engine.config().targetAllowPrefix);
        prefixChk.setOpaque(false);
        JCheckBox idsAsParamsChk = new JCheckBox("Tự nhận diện số/UUID trong mẫu", engine.config().targetIdsAsParams);
        idsAsParamsChk.setOpaque(false);

        Pill verdictPill = new Pill("CHƯA KIỂM TRA", th -> th.neutral);
        verdictPill.setSolidWhenActive(false);
        verdictPill.setActive(true);
        JLabel verdictNote = Ui.muted("Nhập mẫu và đường dẫn thực tế để xác minh.");

        CaptureModel captureModel = new CaptureModel();
        ThemedTable captureTable = new ThemedTable(captureModel);
        Ui.style(captureTable);
        captureTable.setRowHeight(26);
        captureTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        captureTable.getColumnModel().getColumn(0).setPreferredWidth(160);
        captureTable.getColumnModel().getColumn(1).setPreferredWidth(240);

        JPanel segmentsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        segmentsBar.setOpaque(false);

        CatalogModel catalogModel = new CatalogModel();
        catalogModel.reload(engine.placeholders());

        // Forward declarations via Runnable refs
        Runnable[] rebuildSegmentsRef = new Runnable[1];
        Runnable[] runVerifyRef = new Runnable[1];

        runVerifyRef[0] = () -> {
            String tpl = templateField.getText().trim();
            String smp = sampleField.getText().trim();
            if (tpl.isEmpty() || smp.isEmpty()) {
                verdictPill.setText("THIẾU DỮ LIỆU");
                verdictPill.setToken(th -> th.neutral);
                verdictNote.setText("Cần có cả đường dẫn mẫu và đường dẫn thực tế để kiểm tra.");
                captureModel.set(Map.of());
                return;
            }
            PathMatch.MatchVerification res = PathMatch.verifyMatch(
                    tpl, smp, prefixChk.isSelected(), idsAsParamsChk.isSelected(), engine.placeholders());
            if (res.matched()) {
                verdictPill.setText(res.exact() ? "KHỚP CHÍNH XÁC" : "KHỚP TIỀN TỐ");
                verdictPill.setToken(th -> res.exact() ? th.success : th.info);
                int nCap = res.capturedValues().size();
                verdictNote.setText(nCap > 0
                        ? "Đường dẫn thực tế khớp mẫu — đã trích xuất " + nCap + " giá trị động bên dưới."
                        : "Đường dẫn thực tế khớp mẫu cố định (không chứa placeholder).");
                captureModel.set(res.capturedValues());
            } else {
                verdictPill.setText("KHÔNG KHỚP");
                verdictPill.setToken(th -> th.danger);
                verdictNote.setText("Không khớp với mẫu hiện tại. Hãy kiểm tra lại số đoạn '/' hoặc kiểu Regex của {placeholder}.");
                captureModel.set(Map.of());
            }
        };

        rebuildSegmentsRef[0] = () -> {
            segmentsBar.removeAll();
            Theme cur = Theme.current();
            String raw = templateField.getText().trim();
            List<String> segs = PathMatch.segments(raw);
            if (segs.isEmpty()) {
                segmentsBar.add(Ui.muted("Nhập đường dẫn bắt đầu bằng '/' (VD: /api/a/9f8e7d6c/c) để tách đoạn."));
            } else {
                for (int i = 0; i < segs.size(); i++) {
                    int segIndex = i;
                    String segText = segs.get(i);
                    JLabel slash = Ui.muted("/");
                    slash.setFont(cur.monoBoldFont);
                    segmentsBar.add(slash);

                    boolean isPlaceholder = PathMatch.hasPlaceholder(segText) || segText.startsWith(":");
                    JButton segBtn = new JButton(segText) {
                        @Override
                        public void updateUI() {
                            super.updateUI();
                            Theme th = Theme.current();
                            Theme.Token tok = isPlaceholder ? th.accent : th.neutral;
                            setFont(isPlaceholder ? th.monoBoldFont : th.monoFont);
                            setForeground(isPlaceholder ? tok.text() : th.fg);
                            setBackground(isPlaceholder ? tok.fill() : th.surface);
                            setBorder(BorderFactory.createCompoundBorder(
                                    BorderFactory.createLineBorder(isPlaceholder ? tok.border() : th.border, 1),
                                    BorderFactory.createEmptyBorder(3, 8, 3, 8)));
                        }
                    };
                    segBtn.setOpaque(true);
                    segBtn.setContentAreaFilled(true);
                    segBtn.setFocusPainted(false);
                    segBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    segBtn.setToolTipText(isPlaceholder
                            ? "Đoạn động " + segText + " — bấm để đổi sang placeholder khác hoặc giữ chữ cố định"
                            : "Đoạn cố định \"" + segText + "\" — bấm để chuyển thành {placeholder}");
                    segBtn.addActionListener(Ui.safe(e -> showSegmentPopup(
                            segBtn, dialogParent, engine, templateField, segs, segIndex, catalogModel,
                            () -> {
                                rebuildSegmentsRef[0].run();
                                runVerifyRef[0].run();
                            })));
                    segmentsBar.add(segBtn);
                }
            }
            segmentsBar.revalidate();
            segmentsBar.repaint();
        };

        // ------------------------------------------------------------------ Section A: Builder Card
        Card builderCard = new Card();
        builderCard.setLayout(new BorderLayout(0, Theme.SPACE_1));
        builderCard.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        JPanel builderTop = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        builderTop.setOpaque(false);
        JLabel tplLbl = Ui.bold("Đường dẫn mẫu Excel:");
        tplLbl.setIcon(Icons.themed(th -> Icons.link(th.info.text(), 14)));

        JButton[] insertBtnRef = new JButton[1];
        JButton insertBtn = Ui.dropdown("Chèn {...}", th -> Icons.withChevron(Icons.plus(th.accent.text()), th.fg),
                "Chèn nhanh thẻ {placeholder} vào vị trí con trỏ", () -> {
                    JPopupMenu pm = new JPopupMenu();
                    for (PlaceholderDef def : engine.placeholders().all()) {
                        pm.add(Ui.item(def.token() + "  —  " + def.kind().label,
                                th -> Icons.codeBraces(th.accent.text(), 13), () -> {
                                    insertTokenAtCaret(templateField, def.token());
                                }));
                    }
                    pm.show(insertBtnRef[0], 0, insertBtnRef[0].getHeight());
                });
        insertBtnRef[0] = insertBtn;

        JButton genSampleBtn = Ui.button("Tạo mẫu thử", th -> Icons.playTest(th.info.text(), 13),
                "Tự điền URL thực tế giả lập từ mẫu hiện tại để kiểm tra khớp", () -> {
                    sampleField.setText(synthesizeSamplePath(templateField.getText(), engine.placeholders()));
                    runVerifyRef[0].run();
                });

        JPanel builderActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        builderActions.setOpaque(false);
        builderActions.add(insertBtn);
        builderActions.add(genSampleBtn);
        if (focusEntry != null) {
            JButton applyRowBtn = Ui.accent("Lưu dòng #" + focusEntry.sttText(),
                    th -> Icons.save(th.accent.onSolid()), th -> th.accent,
                    "Cập nhật đường dẫn mẫu mới vào dòng Excel đang chọn và đối chiếu lại ngay", null, () -> {
                        String np = templateField.getText().trim();
                        if (np.isEmpty()) {
                            JOptionPane.showMessageDialog(Ui.ownerWindow(dialogParent), "Đường dẫn mẫu không được để trống.",
                                    Text.EXT_NAME, JOptionPane.WARNING_MESSAGE);
                            return;
                        }
                        if (engine.updateTargetRowPath(focusEntry.rowNo(), np)) {
                            if (onChanged != null) {
                                onChanged.run();
                            }
                            JOptionPane.showMessageDialog(Ui.ownerWindow(dialogParent),
                                    "Đã cập nhật dòng Excel #" + focusEntry.sttText() + " thành:\n" + np
                                            + "\nĐã tự động đối chiếu lại toàn bộ API đã bắt.",
                                    Text.EXT_NAME, JOptionPane.INFORMATION_MESSAGE);
                        }
                    });
            builderActions.add(applyRowBtn);
        }

        builderTop.add(tplLbl, BorderLayout.WEST);
        builderTop.add(templateField, BorderLayout.CENTER);
        builderTop.add(builderActions, BorderLayout.EAST);

        JPanel segWrap = new JPanel(new BorderLayout(Theme.SPACE_1, 0));
        segWrap.setOpaque(false);
        JLabel segHint = Ui.muted("Bấm đoạn để gắn {...}:");
        segWrap.add(segHint, BorderLayout.WEST);
        JScrollPane segScroll = new JScrollPane(segmentsBar,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        segScroll.setBorder(BorderFactory.createEmptyBorder());
        segScroll.setOpaque(false);
        segScroll.getViewport().setOpaque(false);
        segScroll.setPreferredSize(new Dimension(200, 38));
        segWrap.add(segScroll, BorderLayout.CENTER);

        builderCard.add(builderTop, BorderLayout.NORTH);
        builderCard.add(segWrap, BorderLayout.CENTER);

        // ------------------------------------------------------------------ Section B: Catalog CRUD (Left)
        Card catalogCard = new Card();
        catalogCard.setLayout(new BorderLayout(0, Theme.SPACE_2));
        catalogCard.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        JLabel catTitle = Ui.bold("Danh mục {Placeholder} đã khai báo");
        catTitle.setIcon(Icons.themed(th -> Icons.tag(th.accent.text(), 14)));
        catalogCard.add(catTitle, BorderLayout.NORTH);

        ThemedTable catalogTable = new ThemedTable(catalogModel);
        Ui.style(catalogTable);
        catalogTable.setRowHeight(28);
        catalogTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        catalogTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] catWidths = {104, 170, 175, 80, 220};
        for (int c = 0; c < catWidths.length; c++) {
            catalogTable.getColumnModel().getColumn(c).setPreferredWidth(catWidths[c]);
        }

        JTextField nameField = new JTextField();
        nameField.setFont(t.monoFont);
        nameField.setPreferredSize(new Dimension(120, 26));
        nameField.setToolTipText("Tên placeholder (VD: session, tenantId, orderCode)");

        JComboBox<MatchKind> kindBox = new JComboBox<>(MatchKind.values());
        kindBox.setPreferredSize(new Dimension(210, 26));
        kindBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof MatchKind k) {
                    setText(k.label);
                }
                return this;
            }
        });

        JTextField regexField = new JTextField();
        regexField.setFont(t.monoFont);
        regexField.setPreferredSize(new Dimension(190, 26));

        JTextField descField = new JTextField();
        descField.setPreferredSize(new Dimension(210, 26));

        Runnable syncRegexField = () -> {
            MatchKind k = (MatchKind) kindBox.getSelectedItem();
            boolean custom = k == MatchKind.CUSTOM_REGEX;
            regexField.setEditable(custom);
            if (!custom && k != null) {
                regexField.setText(k.defaultRegex);
            }
        };
        kindBox.addActionListener(Ui.safe(e -> syncRegexField.run()));
        syncRegexField.run();

        JButton saveDefBtn = Ui.accent("Lưu Placeholder", th -> Icons.save(th.accent.onSolid()), th -> th.accent, () -> {
            String clean = PlaceholderCatalog.sanitizeName(nameField.getText());
            if (clean.isEmpty()) {
                JOptionPane.showMessageDialog(Ui.ownerWindow(dialogParent),
                        "Vui lòng nhập tên hợp lệ (chỉ gồm chữ cái, chữ số, '_' hoặc '-').",
                        Text.EXT_NAME, JOptionPane.WARNING_MESSAGE);
                return;
            }
            MatchKind k = (MatchKind) kindBox.getSelectedItem();
            String rx = regexField.getText().trim();
            if (k == MatchKind.CUSTOM_REGEX) {
                if (rx.isEmpty()) {
                    JOptionPane.showMessageDialog(Ui.ownerWindow(dialogParent), "Vui lòng nhập biểu thức Regex cho kiểu tùy chỉnh.",
                            Text.EXT_NAME, JOptionPane.WARNING_MESSAGE);
                    return;
                }
                try {
                    java.util.regex.Pattern.compile(rx);
                } catch (RuntimeException ex) {
                    JOptionPane.showMessageDialog(Ui.ownerWindow(dialogParent), "Regex không hợp lệ: " + ex.getMessage(),
                            Text.EXT_NAME, JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
            engine.placeholders().upsert(clean, k, k == MatchKind.CUSTOM_REGEX ? rx : "", descField.getText().trim());
            engine.savePlaceholders();
            catalogModel.reload(engine.placeholders());
            rebuildSegmentsRef[0].run();
            runVerifyRef[0].run();
            if (onChanged != null) {
                onChanged.run();
            }
        });

        JButton newDefBtn = Ui.button("Mới", th -> Icons.plus(th.success.text()), () -> {
            catalogTable.clearSelection();
            nameField.setText("");
            kindBox.setSelectedItem(MatchKind.ANY_SEGMENT);
            descField.setText("");
            syncRegexField.run();
            nameField.requestFocusInWindow();
        });

        JButton delDefBtn = Ui.button("Xóa", th -> Icons.trash(th.danger.text()), () -> {
            int row = catalogTable.getSelectedRow();
            if (row < 0) {
                return;
            }
            PlaceholderDef d = catalogModel.at(row);
            if (d == null || d.builtIn()) {
                JOptionPane.showMessageDialog(Ui.ownerWindow(dialogParent),
                        "Placeholder mặc định không thể xóa (nhưng bạn có thể sửa Kiểu khớp / Mô tả).",
                        Text.EXT_NAME, JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (engine.placeholders().remove(d.name())) {
                engine.savePlaceholders();
                catalogModel.reload(engine.placeholders());
                rebuildSegmentsRef[0].run();
                runVerifyRef[0].run();
                if (onChanged != null) {
                    onChanged.run();
                }
            }
        });
        delDefBtn.setEnabled(false);

        catalogTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            int row = catalogTable.getSelectedRow();
            PlaceholderDef d = catalogModel.at(row);
            if (d != null) {
                nameField.setText(d.name());
                kindBox.setSelectedItem(d.kind());
                if (d.kind() == MatchKind.CUSTOM_REGEX) {
                    regexField.setEditable(true);
                    regexField.setText(d.customRegex());
                } else {
                    syncRegexField.run();
                }
                descField.setText(d.description());
                delDefBtn.setEnabled(!d.builtIn());
            } else {
                delDefBtn.setEnabled(false);
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;

        g.gridx = 0; g.gridy = 0; g.weightx = 0; form.add(Ui.muted("Tên {...}:"), g);
        g.gridx = 1; g.weightx = 0.4; form.add(nameField, g);
        g.gridx = 2; g.weightx = 0; form.add(Ui.muted("Kiểu khớp:"), g);
        g.gridx = 3; g.weightx = 0.6; form.add(kindBox, g);

        g.gridx = 0; g.gridy = 1; g.weightx = 0; form.add(Ui.muted("Regex:"), g);
        g.gridx = 1; g.weightx = 0.4; form.add(regexField, g);
        g.gridx = 2; g.weightx = 0; form.add(Ui.muted("Mô tả:"), g);
        g.gridx = 3; g.weightx = 0.6; form.add(descField, g);

        JPanel formBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        formBtns.setOpaque(false);
        formBtns.add(newDefBtn);
        formBtns.add(delDefBtn);
        formBtns.add(saveDefBtn);
        g.gridx = 0; g.gridy = 2; g.gridwidth = 4; g.weightx = 1.0;
        form.add(formBtns, g);

        catalogCard.add(MainPanel.scroll(catalogTable), BorderLayout.CENTER);
        catalogCard.add(form, BorderLayout.SOUTH);

        // ------------------------------------------------------------------ Section C: Live Match Verifier (Right)
        Card verifierCard = new Card();
        verifierCard.setLayout(new BorderLayout(0, Theme.SPACE_2));
        verifierCard.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        JLabel verTitle = Ui.bold("Kiểm thử khớp trực tiếp (Live Match Verifier)");
        verTitle.setIcon(Icons.themed(th -> Icons.check(th.success.text(), 14)));

        JPanel verTop = new JPanel();
        verTop.setOpaque(false);
        verTop.setLayout(new BoxLayout(verTop, BoxLayout.Y_AXIS));

        JPanel verHeader = new JPanel(new BorderLayout());
        verHeader.setOpaque(false);
        verHeader.add(verTitle, BorderLayout.WEST);
        verHeader.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel sampleRow = new JPanel(new BorderLayout(Theme.SPACE_1, 0));
        sampleRow.setOpaque(false);
        sampleRow.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, 0, Theme.SPACE_1, 0));
        sampleRow.add(Ui.muted("URL / Path thực tế:"), BorderLayout.WEST);
        sampleRow.add(sampleField, BorderLayout.CENTER);
        sampleRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel optsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        optsRow.setOpaque(false);
        optsRow.add(prefixChk);
        optsRow.add(idsAsParamsChk);
        optsRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel verdictRow = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
        verdictRow.setOpaque(false);
        verdictRow.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_1, 0, Theme.SPACE_1, 0));
        verdictRow.add(verdictPill, BorderLayout.WEST);
        verdictRow.add(verdictNote, BorderLayout.CENTER);
        verdictRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        verTop.add(verHeader);
        verTop.add(sampleRow);
        verTop.add(optsRow);
        verTop.add(verdictRow);

        verifierCard.add(verTop, BorderLayout.NORTH);
        verifierCard.add(MainPanel.scroll(captureTable), BorderLayout.CENTER);

        // ------------------------------------------------------------------ Center layout
        JSplitPane centerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, catalogCard, verifierCard);
        centerSplit.setResizeWeight(0.56);
        centerSplit.setOneTouchExpandable(true);
        centerSplit.setBorder(BorderFactory.createEmptyBorder());

        JPanel body = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        body.setOpaque(false);
        body.add(builderCard, BorderLayout.NORTH);
        body.add(centerSplit, BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);

        // ------------------------------------------------------------------ Footer
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        JLabel tip = Ui.muted("Mọi placeholder {name} mới khai báo sẽ tự động áp dụng cho toàn bộ danh sách Excel hiện tại.");
        JButton closeBtn = Ui.button("Đóng", th -> Icons.check(th.fg), () -> {
            if (onClose != null) {
                onClose.run();
            }
        });
        footer.add(tip, BorderLayout.WEST);
        footer.add(closeBtn, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        // ------------------------------------------------------------------ Live document listeners
        DocumentListener tplListener = new DocumentListener() {
            private void sync() {
                rebuildSegmentsRef[0].run();
                runVerifyRef[0].run();
            }
            @Override public void insertUpdate(DocumentEvent e) { sync(); }
            @Override public void removeUpdate(DocumentEvent e) { sync(); }
            @Override public void changedUpdate(DocumentEvent e) { sync(); }
        };
        templateField.getDocument().addDocumentListener(tplListener);

        DocumentListener smpListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { runVerifyRef[0].run(); }
            @Override public void removeUpdate(DocumentEvent e) { runVerifyRef[0].run(); }
            @Override public void changedUpdate(DocumentEvent e) { runVerifyRef[0].run(); }
        };
        sampleField.getDocument().addDocumentListener(smpListener);
        prefixChk.addActionListener(Ui.safe(e -> runVerifyRef[0].run()));
        idsAsParamsChk.addActionListener(Ui.safe(e -> runVerifyRef[0].run()));

        // Initial render
        rebuildSegmentsRef[0].run();
        runVerifyRef[0].run();
        return root;
    }

    private static void showSegmentPopup(JButton anchor, Component dialogParent, Engine engine,
                                         JTextField templateField, List<String> segs, int segIndex,
                                         CatalogModel catalogModel, Runnable afterChange) {
        JPopupMenu pm = new JPopupMenu();
        String current = segs.get(segIndex);
        for (PlaceholderDef def : engine.placeholders().all()) {
            String label = "Gắn thành " + def.token() + "  (" + def.kind().label + ")";
            pm.add(Ui.item(label, th -> Icons.codeBraces(th.accent.text(), 13), () -> {
                replaceSegment(templateField, segs, segIndex, def.token());
                afterChange.run();
            }));
        }
        pm.addSeparator();
        pm.add(Ui.item("Tạo tên {placeholder} mới cho đoạn này...", th -> Icons.plus(th.success.text(), 13), () -> {
            String rawName = JOptionPane.showInputDialog(Ui.ownerWindow(dialogParent),
                    "Nhập tên placeholder cho đoạn \"" + current + "\" (VD: session, tenantId):",
                    Text.EXT_NAME, JOptionPane.QUESTION_MESSAGE);
            String clean = PlaceholderCatalog.sanitizeName(rawName);
            if (!clean.isEmpty()) {
                if (engine.placeholders().byName(clean) == null) {
                    engine.placeholders().upsert(clean, MatchKind.ANY_SEGMENT, "",
                            "Tạo nhanh từ đường dẫn Excel");
                    engine.savePlaceholders();
                    catalogModel.reload(engine.placeholders());
                }
                replaceSegment(templateField, segs, segIndex, "{" + clean + "}");
                afterChange.run();
            }
        }));
        pm.add(Ui.item("Sửa đoạn này thành chữ cố định...", th -> Icons.edit(th.fg, 13), () -> {
            String literal = JOptionPane.showInputDialog(Ui.ownerWindow(dialogParent),
                    "Nhập giá trị cố định cho đoạn #" + (segIndex + 1) + ":",
                    current);
            if (literal != null && !literal.trim().isEmpty()) {
                replaceSegment(templateField, segs, segIndex, literal.trim().replace("/", ""));
                afterChange.run();
            }
        }));
        pm.show(anchor, 0, anchor.getHeight());
    }

    private static void replaceSegment(JTextField templateField, List<String> segs, int idx, String replacement) {
        String raw = templateField.getText().trim();
        String suffix = "";
        int qi = raw.indexOf('?');
        if (qi >= 0) {
            suffix = raw.substring(qi);
        }
        List<String> updated = new ArrayList<>(segs);
        if (idx >= 0 && idx < updated.size()) {
            updated.set(idx, replacement);
        }
        templateField.setText("/" + String.join("/", updated) + suffix);
    }

    private static void insertTokenAtCaret(JTextField field, String token) {
        int start = field.getSelectionStart();
        int end = field.getSelectionEnd();
        String cur = field.getText();
        String next = cur.substring(0, start) + token + cur.substring(end);
        field.setText(next);
        field.setCaretPosition(start + token.length());
        field.requestFocusInWindow();
    }

    static String synthesizeSamplePath(String templatePath, PlaceholderCatalog catalog) {
        List<String> segs = PathMatch.segments(templatePath);
        if (segs.isEmpty()) {
            return "/api/a/9f8e7d6c/c";
        }
        List<String> out = new ArrayList<>();
        for (String seg : segs) {
            if (PathMatch.hasPlaceholder(seg) || (seg.startsWith(":") && seg.length() > 1)) {
                String clean = PlaceholderCatalog.sanitizeName(seg);
                PlaceholderDef def = catalog == null ? null : catalog.byName(clean);
                MatchKind k = def == null ? MatchKind.ANY_SEGMENT : def.kind();
                out.add(switch (k) {
                    case NUMERIC -> "48291";
                    case UUID -> "550e8400-e29b-41d4-a716-446655440000";
                    case RANDOM_TOKEN -> "sess_9f8e7d6c5b4a";
                    case ALPHANUMERIC -> "demo-item-01";
                    case CUSTOM_REGEX, ANY_SEGMENT -> "x9k2m8p1";
                });
            } else {
                out.add(seg);
            }
        }
        return "/" + String.join("/", out);
    }

    // ------------------------------------------------------------------ Table Models
    private static final class CatalogModel extends AbstractTableModel {
        private static final String[] COLS = {"Token", "Kiểu khớp", "Regex áp dụng", "Loại", "Mô tả"};
        private List<PlaceholderDef> rows = List.of();

        void reload(PlaceholderCatalog catalog) {
            rows = catalog == null ? List.of() : catalog.all();
            fireTableDataChanged();
        }

        PlaceholderDef at(int r) {
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            PlaceholderDef d = rows.get(r);
            return switch (c) {
                case 0 -> d.token();
                case 1 -> d.kind().label;
                case 2 -> d.kind() == MatchKind.CUSTOM_REGEX && !d.customRegex().isBlank()
                        ? d.customRegex() : d.kind().defaultRegex;
                case 3 -> d.builtIn() ? "Mặc định" : "Tùy chỉnh";
                default -> d.description();
            };
        }
    }

    private static final class CaptureModel extends AbstractTableModel {
        private static final String[] COLS = {"Placeholder trong mẫu", "Giá trị thực tế bắt được"};
        private List<Map.Entry<String, String>> rows = List.of();

        void set(Map<String, String> map) {
            rows = map == null ? List.of() : new ArrayList<>(map.entrySet());
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            Map.Entry<String, String> e = rows.get(r);
            return c == 0 ? e.getKey() : e.getValue();
        }
    }
}
