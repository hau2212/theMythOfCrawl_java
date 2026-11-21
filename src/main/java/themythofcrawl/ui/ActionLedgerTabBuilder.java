package themythofcrawl.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

import themythofcrawl.app.Engine;
import themythofcrawl.core.ActionEngine;
import themythofcrawl.core.ActionEngine.ActionMode;
import themythofcrawl.core.ActionPolicyLedger;
import themythofcrawl.core.ActionPolicyLedger.DeletedEntry;
import themythofcrawl.core.ActionPolicyLedger.EvaluationResult;
import themythofcrawl.core.ActionPolicyLedger.Preset;
import themythofcrawl.core.Hierarchy;
import themythofcrawl.core.Text;
import themythofcrawl.model.ActionPolicy;
import themythofcrawl.model.ActionPolicy.PathMatchType;
import themythofcrawl.model.ActionPolicy.ProxyEffect;
import themythofcrawl.model.ActionPolicy.ScopeType;
import themythofcrawl.model.ActionPolicy.Stage;
import themythofcrawl.model.ApiGroup;
import themythofcrawl.model.Branch;
import themythofcrawl.ui.kit.MethodChipBar;
import themythofcrawl.ui.kit.ModernToggleSwitch;
import themythofcrawl.ui.kit.Pill;
import themythofcrawl.ui.kit.ThemedTable;
import themythofcrawl.ui.theme.Theme;

/**
 * Builds the Unified Action Policy Ledger tab (V_3.5_beta Progressive CRUD Form):
 * <ul>
 *   <li>Context-aware form that only shows Branch / Path / Proxy / Replay fields relevant to the chosen Scope &amp; Stage.</li>
 *   <li>Unmistakable solid primary Save buttons ({@code Lưu chính sách mới} / {@code Lưu thay đổi} + {@code Ctrl+S}),
 *       auto-naming when the Name field is left empty, and instant green confirmation feedback.</li>
 *   <li>Burp-LAF-proof {@link MethodChipBar} and {@link ModernToggleSwitch}.</li>
 *   <li>Full CRUD + Priority Reorder + Presets + JSON Import/Export + Undo Trash + Emergency Reset All.</li>
 * </ul>
 */
final class ActionLedgerTabBuilder {

    private ActionLedgerTabBuilder() {
    }

    static JPanel build(
            Component dialogParent,
            Engine engine,
            ApiGroup focusGroup,
            String prefillBranch,
            Runnable refreshBanner,
            Runnable onAfterAction) {
        Theme th = Theme.current();
        ActionPolicyLedger ledger = engine.actionPolicyLedger();

        JPanel root = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));

        // ---- Top Filter & Global Control Strip
        JPanel topStrip = new JPanel(new BorderLayout(8, 4));
        topStrip.setOpaque(true);
        topStrip.setBackground(th.surfaceAlt);
        topStrip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        filterRow.setOpaque(false);

        JComboBox<String> scopeFilterCombo = new JComboBox<>(new String[] {
                "Mọi phạm vi",
                ScopeType.GLOBAL.label,
                ScopeType.METHOD_MATRIX.label,
                ScopeType.BRANCH.label,
                ScopeType.PATH_PATTERN.label,
                ScopeType.API_EXACT.label
        });
        JComboBox<String> stateFilterCombo = new JComboBox<>(new String[] {
                "Mọi trạng thái", "Đang BẬT", "Đang TẮT"
        });
        JTextField searchField = new JTextField(15);
        searchField.setToolTipText("Lọc nhanh theo tên chính sách, path, nhánh, method, nguồn tạo...");
        Pill summaryPill = new Pill(ledger.activeSummaryText(), t -> t.accent, t -> Icons.strategyShield(t.accent.text(), 12));

        filterRow.add(Ui.bold("Lọc:"));
        filterRow.add(scopeFilterCombo);
        filterRow.add(stateFilterCombo);
        filterRow.add(searchField);
        filterRow.add(summaryPill);

        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        topRight.setOpaque(false);
        JButton trashBtn = Ui.secondaryBtn("Thùng rác / Hoàn tác (0)", t -> Icons.undo(t.fg));
        JButton resetAllBtn = Ui.dangerBtn("Nhả toàn bộ -> Nguyên bản", t -> Icons.trash(t.danger.text()), null);
        resetAllBtn.setToolTipText("Đưa toàn bộ chính sách vào Thùng rác Undo, tắt mọi chặn Proxy và chuyển về chế độ Nguyên bản");
        topRight.add(trashBtn);
        topRight.add(resetAllBtn);

        topStrip.add(filterRow, BorderLayout.CENTER);
        topStrip.add(topRight, BorderLayout.EAST);
        root.add(topStrip, BorderLayout.NORTH);

        // ---- Center Top: Ledger Table + Bulk Toolbar
        String[] cols = {
                "#", "Bật/Tắt", "Phạm vi", "Đối tượng áp dụng", "Method",
                "Giai đoạn & Hành vi", "Tạo lúc", "Sửa lúc", "Khớp cuối", "Hits", "Nguồn"
        };
        DefaultTableModel tableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        JTable ledgerTable = new ThemedTable(tableModel);
        Ui.style(ledgerTable);
        ledgerTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        ledgerTable.setRowHeight(26);
        int[] widths = {34, 62, 108, 190, 92, 180, 82, 82, 82, 46, 92};
        for (int i = 0; i < widths.length; i++) {
            ledgerTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        JScrollPane tableScroll = new JScrollPane(ledgerTable);
        tableScroll.setColumnHeaderView(ledgerTable.getTableHeader());
        tableScroll.setBorder(BorderFactory.createLineBorder(th.border));
        tableScroll.setMinimumSize(new Dimension(400, 150));
        tableScroll.setPreferredSize(new Dimension(780, 210));

        final List<ActionPolicy> displayedRows = new ArrayList<>();
        final String[] editingPolicyId = {null};

        // Bulk & Table Toolbar
        JPanel tableBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        tableBtns.setOpaque(false);
        JButton toggleSelBtn = Ui.secondaryBtn("Bật / Tắt", t -> Icons.sliders(t.fg));
        JButton dupBtn = Ui.secondaryBtn("Nhân bản", t -> Icons.dup(t.fg));
        JButton upBtn = Ui.secondaryBtn("Lên", null);
        JButton downBtn = Ui.secondaryBtn("Xuống", null);
        JButton topBtn = Ui.secondaryBtn("Lên đầu", null);
        JButton deleteBtn = Ui.dangerBtn("Xóa chọn", t -> Icons.trash(t.danger.text()), null);
        JButton presetBtn = Ui.secondaryBtn("Bộ mẫu nhanh", t -> Icons.plus(t.accent.text()));
        JButton importBtn = Ui.secondaryBtn("Import JSON", null);
        JButton exportBtn = Ui.secondaryBtn("Export JSON", null);
        JButton clearHitsBtn = Ui.secondaryBtn("Reset Hits", null);

        JButton newRuleBtn = Ui.solidPrimary("Tạo luật mới", t -> Icons.plus(t.accent.onSolid()));
        newRuleBtn.setToolTipText("Mở form bên dưới để tạo 1 luật mới (form chỉ hiện khi cần)");
        tableBtns.add(newRuleBtn);
        tableBtns.add(Box.createHorizontalStrut(6));
        tableBtns.add(toggleSelBtn);
        tableBtns.add(dupBtn);
        tableBtns.add(upBtn);
        tableBtns.add(downBtn);
        tableBtns.add(topBtn);
        tableBtns.add(deleteBtn);
        tableBtns.add(Box.createHorizontalStrut(6));
        tableBtns.add(presetBtn);
        tableBtns.add(importBtn);
        tableBtns.add(exportBtn);
        tableBtns.add(clearHitsBtn);

        JPanel tableWrap = new JPanel(new BorderLayout(0, 2));
        tableWrap.setOpaque(false);
        tableWrap.add(tableScroll, BorderLayout.CENTER);
        tableWrap.add(tableBtns, BorderLayout.SOUTH);

        // ---- Center Bottom: Progressive Context-Aware CRUD Form
        JPanel formCard = new JPanel(new BorderLayout(0, 6));
        formCard.setOpaque(true);
        formCard.setBackground(th.surface);
        formCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        JLabel formHeader = Ui.bold("Tạo chính sách Action mới");
        Pill editModePill = new Pill("Chế độ: Tạo mới", t -> t.accent, t -> Icons.plus(t.accent.text(), 11));
        JPanel formTopHead = new JPanel(new BorderLayout(8, 0));
        formTopHead.setOpaque(false);
        formTopHead.add(formHeader, BorderLayout.WEST);
        formTopHead.add(editModePill, BorderLayout.EAST);
        formCard.add(formTopHead, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        JTextField nameField = new JTextField(22);
        nameField.putClientProperty("JTextField.placeholderText", "Để trống = hệ thống tự đặt tên gọn theo luật");
        ModernToggleSwitch enabledSwitch = new ModernToggleSwitch(true, false).labels("Bật", "Tắt");

        JComboBox<ScopeType> scopeCombo = new JComboBox<>(ScopeType.values());
        scopeCombo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val != null ? val.label : "");
            l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return l;
        });

        JComboBox<Stage> stageCombo = new JComboBox<>(Stage.values());
        stageCombo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val != null ? val.label : "");
            l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return l;
        });

        JComboBox<ProxyEffect> proxyEffectCombo = new JComboBox<>(ProxyEffect.values());
        proxyEffectCombo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val != null ? val.label : "");
            l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return l;
        });
        proxyEffectCombo.setSelectedItem(ProxyEffect.INTERCEPT_WAIT);

        JComboBox<ActionMode> replayModeCombo = new JComboBox<>(ActionMode.values());
        replayModeCombo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val != null ? val.shortTitle() : "");
            l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return l;
        });
        replayModeCombo.setSelectedItem(ActionMode.NONE);

        JTextField branchesField = new JTextField(22);
        branchesField.setToolTipText("Nhiều nhánh cách nhau bằng dấu chấm phẩy ';'");
        JComboBox<String> branchPicker = new JComboBox<>();
        branchPicker.addItem("(Chọn nhánh để thêm...)");
        for (Branch b : engine.branches()) {
            branchPicker.addItem(b.name());
        }
        JCheckBox subBranchChk = new JCheckBox("Gồm nhánh con", true);
        subBranchChk.setOpaque(false);

        branchPicker.addActionListener(Ui.safe(e -> {
            if (branchPicker.getSelectedIndex() <= 0) return;
            String picked = String.valueOf(branchPicker.getSelectedItem());
            String cur = branchesField.getText().trim();
            if (cur.isEmpty()) {
                branchesField.setText(picked);
            } else if (!cur.toLowerCase().contains(picked.toLowerCase())) {
                branchesField.setText(cur + "; " + picked);
            }
            branchPicker.setSelectedIndex(0);
        }));

        JComboBox<PathMatchType> pathTypeCombo = new JComboBox<>(PathMatchType.values());
        pathTypeCombo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val != null ? val.label : "");
            l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return l;
        });
        JTextField pathField = new JTextField(20);
        pathField.putClientProperty("JTextField.placeholderText", "VD: /api/v1/orders/{id}");
        JButton insertIdBtn = Ui.secondaryBtn("+ {id}", null);
        insertIdBtn.setToolTipText("Chèn placeholder {id} vào mẫu đường dẫn");
        insertIdBtn.addActionListener(Ui.safe(e -> {
            String t = pathField.getText();
            pathField.setText(t.endsWith("/") ? t + "{id}" : t + "/{id}");
            pathTypeCombo.setSelectedItem(PathMatchType.TEMPLATE);
        }));
        JTextField hostField = new JTextField(12);
        hostField.putClientProperty("JTextField.placeholderText", "Mọi Host");
        hostField.setToolTipText("Để trống = mọi Host (hỗ trợ wildcard *)");

        // Burp-LAF-proof MethodChipBar (replaces raw JToggleButtons!)
        MethodChipBar methodBar = new MethodChipBar(ActionEngine.ACTION_METHODS, true);

        // Row 0: Name + Scope + Enabled Switch
        gc.gridy = 0;
        gc.gridx = 0; gc.weightx = 0; grid.add(Ui.muted("Tên chính sách:"), gc);
        gc.gridx = 1; gc.weightx = 0.45; grid.add(nameField, gc);
        gc.gridx = 2; gc.weightx = 0; grid.add(Ui.muted("Phạm vi:"), gc);
        gc.gridx = 3; gc.weightx = 0.35; grid.add(scopeCombo, gc);
        gc.gridx = 4; gc.weightx = 0; grid.add(enabledSwitch, gc);

        // Row 1 (Progressive: Branch Scope only)
        JLabel branchLbl = Ui.muted("Nhánh cây:");
        JLabel branchPickLbl = Ui.muted("Chọn nhanh:");
        gc.gridy = 1;
        gc.gridx = 0; gc.weightx = 0; grid.add(branchLbl, gc);
        gc.gridx = 1; gc.weightx = 0.45; grid.add(branchesField, gc);
        gc.gridx = 2; gc.weightx = 0; grid.add(branchPickLbl, gc);
        gc.gridx = 3; gc.weightx = 0.35; grid.add(branchPicker, gc);
        gc.gridx = 4; gc.weightx = 0; grid.add(subBranchChk, gc);

        // Row 2 (Progressive: Path/API Scope only)
        JLabel pathLbl = Ui.muted("Đường dẫn:");
        JPanel pathWrap = new JPanel(new BorderLayout(4, 0));
        pathWrap.setOpaque(false);
        pathWrap.add(pathTypeCombo, BorderLayout.WEST);
        pathWrap.add(pathField, BorderLayout.CENTER);
        pathWrap.add(insertIdBtn, BorderLayout.EAST);

        JPanel hostWrap = new JPanel(new BorderLayout(4, 0));
        hostWrap.setOpaque(false);
        hostWrap.add(Ui.muted("Host:"), BorderLayout.WEST);
        hostWrap.add(hostField, BorderLayout.CENTER);

        gc.gridy = 2;
        gc.gridx = 0; gc.weightx = 0; grid.add(pathLbl, gc);
        gc.gridx = 1; gc.gridwidth = 2; gc.weightx = 0.6; grid.add(pathWrap, gc);
        gc.gridwidth = 1;
        gc.gridx = 3; gc.gridwidth = 2; gc.weightx = 0.4; grid.add(hostWrap, gc);
        gc.gridwidth = 1;

        // Row 3: Stage + Explicit Proxy Effect (Chặng 1) + Explicit Replay Mode (Chặng 2)
        JLabel proxyEffectLbl = Ui.muted("Hành vi Proxy (Chặng 1):");
        JLabel replayModeLbl = Ui.muted("Hành vi Replay (Chặng 2):");
        JPanel behaviorWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        behaviorWrap.setOpaque(false);
        behaviorWrap.add(proxyEffectLbl);
        behaviorWrap.add(proxyEffectCombo);
        behaviorWrap.add(replayModeLbl);
        behaviorWrap.add(replayModeCombo);

        gc.gridy = 3;
        gc.gridx = 0; gc.weightx = 0; grid.add(Ui.muted("Giai đoạn:"), gc);
        gc.gridx = 1; gc.weightx = 0.28; grid.add(stageCombo, gc);
        gc.gridx = 2; gc.gridwidth = 3; gc.weightx = 0.72; grid.add(behaviorWrap, gc);
        gc.gridwidth = 1;

        // Row 4: HTTP Method Chip Bar
        gc.gridy = 4;
        gc.gridx = 0; gc.weightx = 0; grid.add(Ui.muted("HTTP Method:"), gc);
        gc.gridx = 1; gc.gridwidth = 4; gc.weightx = 1.0; grid.add(methodBar, gc);
        gc.gridwidth = 1;

        formCard.add(grid, BorderLayout.CENTER);

        // Progressive visibility updater
        Runnable syncProgressiveFields = () -> {
            ScopeType sc = scopeCombo.getSelectedItem() instanceof ScopeType s ? s : ScopeType.PATH_PATTERN;
            boolean showBranch = (sc == ScopeType.BRANCH);
            boolean showPath = (sc == ScopeType.PATH_PATTERN || sc == ScopeType.API_EXACT);
            branchLbl.setVisible(showBranch);
            branchesField.setVisible(showBranch);
            branchPickLbl.setVisible(showBranch);
            branchPicker.setVisible(showBranch);
            subBranchChk.setVisible(showBranch);

            pathLbl.setVisible(showPath);
            pathWrap.setVisible(showPath);
            hostWrap.setVisible(showPath || sc == ScopeType.METHOD_MATRIX);

            Stage st = stageCombo.getSelectedItem() instanceof Stage sg ? sg : Stage.PROXY_GATE;
            boolean showProxy = (st == Stage.PROXY_GATE || st == Stage.BOTH);
            boolean showReplay = (st == Stage.REPLAY_EXEC || st == Stage.BOTH);
            proxyEffectLbl.setVisible(showProxy);
            proxyEffectCombo.setVisible(showProxy);
            replayModeLbl.setVisible(showReplay);
            replayModeCombo.setVisible(showReplay);

            grid.revalidate();
            grid.repaint();
            formCard.revalidate();
        };
        scopeCombo.addActionListener(Ui.safe(e -> syncProgressiveFields.run()));
        stageCombo.addActionListener(Ui.safe(e -> syncProgressiveFields.run()));

        // ---- Unmistakable Save / Update Footer + Green Feedback Label
        JLabel saveFeedbackLbl = new JLabel(" ");
        saveFeedbackLbl.setFont(th.smallBoldFont);
        saveFeedbackLbl.setForeground(th.success.text());

        JButton clearFormBtn = Ui.secondaryBtn("Tạo mới (Xóa form)", Icons::undo, null);
        JButton updateBtn = Ui.solidPrimary("Lưu thay đổi", t -> Icons.check(t.accent.onSolid()), null);
        updateBtn.setVisible(false);
        JButton createBtn = Ui.solidPrimary("Lưu chính sách mới (Ctrl+S)", t -> Icons.save(t.accent.onSolid()), null);
        createBtn.setToolTipText("Tạo và lưu ngay 1 bản ghi chính sách mới vào Sổ cái (Phím tắt: Ctrl+S)");

        JPanel formBtnsRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        formBtnsRight.setOpaque(false);
        JButton hideFormBtn = Ui.secondaryBtn("Ẩn form", null);
        hideFormBtn.setToolTipText("Thu form lại để bảng luật chiếm hết chỗ");
        formBtnsRight.add(hideFormBtn);
        formBtnsRight.add(clearFormBtn);
        formBtnsRight.add(updateBtn);
        formBtnsRight.add(createBtn);

        JPanel formFooter = new JPanel(new BorderLayout(8, 0));
        formFooter.setOpaque(false);
        formFooter.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, th.border));
        formFooter.add(saveFeedbackLbl, BorderLayout.CENTER);
        formFooter.add(formBtnsRight, BorderLayout.EAST);
        formCard.add(formFooter, BorderLayout.SOUTH);

        // Prefill context if opened for a specific branch or API
        if (prefillBranch != null && !prefillBranch.isBlank()) {
            scopeCombo.setSelectedItem(ScopeType.BRANCH);
            branchesField.setText(Hierarchy.normalize(prefillBranch));
            nameField.setText("Chặn nhánh " + Hierarchy.leaf(prefillBranch));
        } else if (focusGroup != null) {
            scopeCombo.setSelectedItem(ScopeType.API_EXACT);
            pathTypeCombo.setSelectedItem(PathMatchType.TEMPLATE);
            pathField.setText(ActionEngine.normalizePathTemplate(focusGroup.path()));
            hostField.setText(focusGroup.host());
            methodBar.setSingleMethod(focusGroup.method());
        } else {
            scopeCombo.setSelectedItem(ScopeType.PATH_PATTERN);
        }
        syncProgressiveFields.run();

        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableWrap, formCard);
        centerSplit.setResizeWeight(0.50);
        centerSplit.setDividerSize(6);
        centerSplit.setBorder(null);
        root.add(centerSplit, BorderLayout.CENTER);
        // V_7.2: progressive disclosure — the form only appears when creating or editing a rule
        Runnable showForm = () -> {
            if (!formCard.isVisible()) {
                formCard.setVisible(true);
                centerSplit.setDividerLocation(0.42);
                root.revalidate();
            }
        };
        formCard.setVisible(focusGroup != null || (prefillBranch != null && !prefillBranch.isBlank()));
        newRuleBtn.addActionListener(Ui.safe(e -> {
            clearFormBtn.doClick();
            showForm.run();
        }));
        hideFormBtn.addActionListener(Ui.safe(e -> {
            formCard.setVisible(false);
            root.revalidate();
        }));
        ledgerTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && ledgerTable.getSelectedRow() >= 0) {
                showForm.run();
            }
        });

        // ---- Bottom Per-API Effective Policy Explainer (when focusGroup != null)
        JPanel explainerPanel = new JPanel(new BorderLayout(8, 4));
        explainerPanel.setOpaque(true);
        explainerPanel.setBackground(th.surfaceAlt);
        explainerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.border),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        JLabel explainerText = Ui.muted("");
        JPanel explainerBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        explainerBtns.setOpaque(false);

        if (focusGroup != null) {
            JButton releaseApiBtn = Ui.secondaryBtn("Nhả API này về Nguyên bản", t -> Icons.undo(t.fg));
            JButton allowApiBtn = Ui.secondaryBtn("+ Cho qua luôn (ALLOW)", t -> Icons.check(t.success.text()));
            JButton composerBtn = Ui.secondaryBtn("Sửa & Gửi lại Request", t -> Icons.edit(t.accent.text()));
            explainerBtns.add(releaseApiBtn);
            explainerBtns.add(allowApiBtn);
            explainerBtns.add(composerBtn);
            explainerPanel.add(explainerText, BorderLayout.CENTER);
            explainerPanel.add(explainerBtns, BorderLayout.EAST);
            root.add(explainerPanel, BorderLayout.SOUTH);

            releaseApiBtn.addActionListener(Ui.safe(e -> {
                engine.releaseApiToNone(focusGroup, false);
                if (refreshBanner != null) refreshBanner.run();
                if (onAfterAction != null) onAfterAction.run();
            }));
            allowApiBtn.addActionListener(Ui.safe(e -> {
                engine.releaseApiToNone(focusGroup, true);
                if (refreshBanner != null) refreshBanner.run();
                if (onAfterAction != null) onAfterAction.run();
            }));
            composerBtn.addActionListener(Ui.safe(e -> RequestComposerDialog.open(dialogParent, engine, focusGroup, null, null)));
        }

        // ---- Refresh Logic
        final String[] pendingSelectId = {null};
        Runnable refreshAll = () -> {
            ScopeType sf = switch (scopeFilterCombo.getSelectedIndex()) {
                case 1 -> ScopeType.GLOBAL;
                case 2 -> ScopeType.METHOD_MATRIX;
                case 3 -> ScopeType.BRANCH;
                case 4 -> ScopeType.PATH_PATTERN;
                case 5 -> ScopeType.API_EXACT;
                default -> null;
            };
            Boolean ef = switch (stateFilterCombo.getSelectedIndex()) {
                case 1 -> Boolean.TRUE;
                case 2 -> Boolean.FALSE;
                default -> null;
            };
            List<ActionPolicy> allPolicies = ledger.all();
            java.util.Map<String, Integer> rankById = new java.util.HashMap<>(allPolicies.size() * 2);
            for (int i = 0; i < allPolicies.size(); i++) {
                rankById.put(allPolicies.get(i).id(), i + 1);
            }
            List<ActionPolicy> list = ledger.filter(sf, ef, null, null, searchField.getText());
            displayedRows.clear();
            displayedRows.addAll(list);
            tableModel.setRowCount(0);
            int rowToSelect = -1;
            for (int i = 0; i < list.size(); i++) {
                ActionPolicy p = list.get(i);
                int rank = rankById.getOrDefault(p.id(), i + 1);
                tableModel.addRow(new Object[] {
                        "#" + rank,
                        p.enabled() ? "BẬT" : "TẮT",
                        p.scopeType().label,
                        p.targetSummary(),
                        p.methodsLabel(),
                        p.behaviorSummary(),
                        ActionPolicy.formatTimestamp(p.createdAt()),
                        ActionPolicy.formatTimestamp(p.updatedAt()),
                        ActionPolicy.formatTimestamp(p.lastHitAt()),
                        p.hitCount(),
                        p.createdFrom()
                });
                if (pendingSelectId[0] != null && pendingSelectId[0].equals(p.id())) {
                    rowToSelect = i;
                }
            }
            pendingSelectId[0] = null;
            if (rowToSelect >= 0) {
                ledgerTable.setRowSelectionInterval(rowToSelect, rowToSelect);
                ledgerTable.scrollRectToVisible(ledgerTable.getCellRect(rowToSelect, 0, true));
            }

            summaryPill.setText(ledger.activeSummaryText());
            summaryPill.setToken(ledger.activeCount() > 0 ? t -> t.warning : t -> t.success);
            trashBtn.setText("Thùng rác / Hoàn tác (" + ledger.trashSize() + ")");
            trashBtn.setEnabled(ledger.trashSize() > 0);

            if (focusGroup != null) {
                EvaluationResult pEv = ledger.evaluateProxy(
                        focusGroup.branch(), focusGroup.host(), focusGroup.method(), focusGroup.path(), focusGroup.gid());
                ActionMode effReplay = engine.effectiveActionMode(focusGroup);
                String chain = pEv.allMatched().isEmpty()
                        ? "Không có luật Proxy nào chạm vào " + Text.apiLabel(focusGroup.gid()) + " -> Đi thẳng Nguyên bản."
                        : "Hiệu lực cho " + Text.apiLabel(focusGroup.gid()) + ": THẮNG = [" + pEv.winningPolicy().name()
                                + " (" + pEv.effectiveProxyEffect().label + ")]"
                                + (pEv.allMatched().size() > 1
                                        ? " · Ghi đè " + (pEv.allMatched().size() - 1) + " luật ưu tiên thấp hơn." : ".");
                explainerText.setText(chain + "  |  Replay hiện tại: " + effReplay.shortTitle());
            }
            if (refreshBanner != null) refreshBanner.run();
        };

        // Wire filters
        scopeFilterCombo.addActionListener(Ui.safe(e -> refreshAll.run()));
        stateFilterCombo.addActionListener(Ui.safe(e -> refreshAll.run()));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshAll.run(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshAll.run(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshAll.run(); }
        });

        // Populate form on row selection
        ledgerTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = ledgerTable.getSelectedRow();
            if (row < 0 || row >= displayedRows.size()) {
                editingPolicyId[0] = null;
                updateBtn.setVisible(false);
                formHeader.setText("Tạo chính sách Action mới");
                editModePill.setText("Chế độ: Tạo mới");
                editModePill.setToken(t -> t.accent);
                return;
            }
            ActionPolicy p = displayedRows.get(row);
            editingPolicyId[0] = p.id();
            updateBtn.setVisible(true);
            formHeader.setText("Đang chỉnh sửa chính sách: " + p.name());
            editModePill.setText("Đang sửa #" + (row + 1));
            editModePill.setToken(t -> t.warning);

            nameField.setText(p.rawName());
            enabledSwitch.setOn(p.enabled(), false);
            scopeCombo.setSelectedItem(p.scopeType());
            stageCombo.setSelectedItem(p.stage());
            proxyEffectCombo.setSelectedItem(p.proxyEffect());
            replayModeCombo.setSelectedItem(p.replayMode());
            branchesField.setText(String.join("; ", p.targetBranches()));
            subBranchChk.setSelected(p.includeSubBranches());
            pathTypeCombo.setSelectedItem(p.pathMatchType());
            pathField.setText(p.pathPattern());
            hostField.setText(p.hostPattern());
            methodBar.setSelectedMethods(p.methods());
            syncProgressiveFields.run();
        });

        // Build policy from form helper (with automatic clean naming when left blank!)
        java.util.function.Function<String, ActionPolicy> buildFromForm = existingId -> {
            ScopeType sc = (ScopeType) scopeCombo.getSelectedItem();
            Stage st = (Stage) stageCombo.getSelectedItem();
            ProxyEffect pe = (ProxyEffect) proxyEffectCombo.getSelectedItem();
            ActionMode rm = (ActionMode) replayModeCombo.getSelectedItem();
            PathMatchType pt = (PathMatchType) pathTypeCombo.getSelectedItem();
            List<String> branches = new ArrayList<>();
            for (String seg : branchesField.getText().split("[;,]")) {
                if (!seg.trim().isEmpty()) {
                    branches.add(Hierarchy.normalize(seg.trim()));
                }
            }
            Set<String> selectedMethods = methodBar.selectedMethods();
            if (selectedMethods.size() == ActionPolicy.ALL_METHODS.size()) {
                selectedMethods.clear(); // empty = all methods
            }
            Integer apiGid = (sc == ScopeType.API_EXACT && focusGroup != null) ? focusGroup.gid() : null;
            int prio = existingId != null && ledger.findById(existingId) != null
                    ? ledger.findById(existingId).priority() : -1;

            String finalName = nameField.getText().trim();
            if (finalName.isEmpty()) {
                String actionTag = (st == Stage.REPLAY_EXEC) ? rm.shortTitle() : pe.label;
                String targetTag = switch (sc) {
                    case GLOBAL -> "Toàn cục";
                    case METHOD_MATRIX -> selectedMethods.isEmpty() ? "Mọi Method" : String.join("/", selectedMethods);
                    case BRANCH -> branches.isEmpty() ? "Nhánh" : Hierarchy.leaf(branches.get(0));
                    case PATH_PATTERN, API_EXACT -> pathField.getText().trim().isEmpty()
                            ? "Đường dẫn" : Text.shortPath(pathField.getText().trim(), 30);
                };
                finalName = actionTag + " · " + targetTag;
            }

            long now = System.currentTimeMillis();
            return new ActionPolicy(
                    existingId, finalName, "", enabledSwitch.isOn(), prio,
                    sc, branches, subBranchChk.isSelected(), hostField.getText().trim(),
                    pt, pathField.getText().trim(), apiGid, selectedMethods,
                    st, pe, rm, 200, null, null, "",
                    now, now, existingId == null ? "Sổ cái CRUD" : "Chỉnh sửa CRUD", 0L, "", 0);
        };

        Runnable doCreate = () -> {
            ActionPolicy created = buildFromForm.apply(null);
            ActionPolicy saved = (created.proxyEffect() == ProxyEffect.ALLOW_EXPLICIT)
                    ? ledger.createAtTop(created)
                    : ledger.upsert(created);
            engine.saveActionPolicies();
            pendingSelectId[0] = saved.id();
            saveFeedbackLbl.setIcon(Icons.check(Theme.current().success.text(), 13));
            saveFeedbackLbl.setText("Đã lưu chính sách mới: \"" + saved.name() + "\" — áp dụng tức thì!");
            refreshAll.run();
            if (onAfterAction != null) onAfterAction.run();
        };

        Runnable doUpdate = () -> {
            if (editingPolicyId[0] == null) {
                doCreate.run();
                return;
            }
            ActionPolicy updated = buildFromForm.apply(editingPolicyId[0]);
            ActionPolicy saved = ledger.upsert(updated);
            engine.saveActionPolicies();
            pendingSelectId[0] = saved.id();
            saveFeedbackLbl.setIcon(Icons.check(Theme.current().success.text(), 13));
            saveFeedbackLbl.setText("Đã cập nhật chính sách: \"" + saved.name() + "\"!");
            refreshAll.run();
            if (onAfterAction != null) onAfterAction.run();
        };

        createBtn.addActionListener(Ui.safe(e -> doCreate.run()));
        updateBtn.addActionListener(Ui.safe(e -> doUpdate.run()));

        // Ctrl+S shortcut on formCard saves immediately
        formCard.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "saveActionPolicy");
        formCard.getActionMap().put("saveActionPolicy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (editingPolicyId[0] != null) {
                    doUpdate.run();
                } else {
                    doCreate.run();
                }
            }
        });

        clearFormBtn.addActionListener(Ui.safe(e -> {
            editingPolicyId[0] = null;
            ledgerTable.clearSelection();
            updateBtn.setVisible(false);
            formHeader.setText("Tạo chính sách Action mới");
            editModePill.setText("Chế độ: Tạo mới");
            editModePill.setToken(t -> t.accent);
            saveFeedbackLbl.setIcon(null);
            saveFeedbackLbl.setText(" ");
            nameField.setText("");
            enabledSwitch.setOn(true, false);
            scopeCombo.setSelectedItem(ScopeType.PATH_PATTERN);
            stageCombo.setSelectedItem(Stage.PROXY_GATE);
            proxyEffectCombo.setSelectedItem(ProxyEffect.INTERCEPT_WAIT);
            replayModeCombo.setSelectedItem(ActionMode.NONE);
            branchesField.setText("");
            pathField.setText("");
            hostField.setText("");
            methodBar.setSelectedMethods(ActionEngine.ACTION_METHODS);
            syncProgressiveFields.run();
        }));

        // Bulk & Table button actions
        java.util.function.Supplier<List<String>> selectedIds = () -> {
            List<String> ids = new ArrayList<>();
            for (int r : ledgerTable.getSelectedRows()) {
                if (r >= 0 && r < displayedRows.size()) {
                    ids.add(displayedRows.get(r).id());
                }
            }
            return ids;
        };

        toggleSelBtn.addActionListener(Ui.safe(e -> {
            List<String> ids = selectedIds.get();
            if (ids.isEmpty()) {
                Ui.hint((java.awt.Component) e.getSource(), "Chọn ít nhất 1 luật trong bảng trước");
                return;
            }
            boolean anyDisabled = false;
            for (String id : ids) {
                ActionPolicy p = ledger.findById(id);
                if (p != null && !p.enabled()) {
                    anyDisabled = true;
                    break;
                }
            }
            ledger.bulkSetEnabled(ids, anyDisabled);
            engine.saveActionPolicies();
            refreshAll.run();
            if (onAfterAction != null) onAfterAction.run();
        }));

        dupBtn.addActionListener(Ui.safe(e -> {
            List<String> ids = selectedIds.get();
            if (ids.isEmpty()) {
                Ui.hint((java.awt.Component) e.getSource(), "Chọn ít nhất 1 luật trong bảng trước");
                return;
            }
            for (String id : ids) {
                ledger.duplicate(id);
            }
            engine.saveActionPolicies();
            refreshAll.run();
            if (onAfterAction != null) onAfterAction.run();
        }));

        upBtn.addActionListener(Ui.safe(e -> {
            List<String> ids = selectedIds.get();
            if (ids.size() != 1) {
                Ui.hint((java.awt.Component) e.getSource(), "Chọn đúng 1 luật trong bảng để di chuyển");
                return;
            }
            if (ledger.movePriority(ids.get(0), -1)) {
                engine.saveActionPolicies();
                refreshAll.run();
            }
        }));

        downBtn.addActionListener(Ui.safe(e -> {
            List<String> ids = selectedIds.get();
            if (ids.size() != 1) {
                Ui.hint((java.awt.Component) e.getSource(), "Chọn đúng 1 luật trong bảng để di chuyển");
                return;
            }
            if (ledger.movePriority(ids.get(0), 1)) {
                engine.saveActionPolicies();
                refreshAll.run();
            }
        }));

        topBtn.addActionListener(Ui.safe(e -> {
            List<String> ids = selectedIds.get();
            if (ids.size() != 1) {
                Ui.hint((java.awt.Component) e.getSource(), "Chọn đúng 1 luật trong bảng để di chuyển");
                return;
            }
            if (ledger.moveToTop(ids.get(0))) {
                engine.saveActionPolicies();
                refreshAll.run();
            }
        }));

        deleteBtn.addActionListener(Ui.safe(e -> {
            List<String> ids = selectedIds.get();
            if (ids.isEmpty()) {
                Ui.hint((java.awt.Component) e.getSource(), "Chọn ít nhất 1 luật trong bảng trước");
                return;
            }
            ledger.bulkDelete(ids, "Xóa từ Sổ cái");
            engine.saveActionPolicies();
            refreshAll.run();
            if (onAfterAction != null) onAfterAction.run();
        }));

        presetBtn.addActionListener(Ui.safe(e -> {
            JPopupMenu pm = new JPopupMenu();
            for (Preset pr : Preset.values()) {
                pm.add(Ui.item(pr.label, null, () -> {
                    ledger.applyPreset(pr);
                    engine.saveActionPolicies();
                    saveFeedbackLbl.setIcon(Icons.check(Theme.current().success.text(), 13));
                    saveFeedbackLbl.setText("Đã áp dụng bộ mẫu: " + pr.label);
                    refreshAll.run();
                    if (onAfterAction != null) onAfterAction.run();
                }));
            }
            pm.show(presetBtn, 0, presetBtn.getHeight());
        }));

        importBtn.addActionListener(Ui.safe(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new FileNameExtensionFilter("JSON Policy (*.json)", "json"));
            if (fc.showOpenDialog(dialogParent) == JFileChooser.APPROVE_OPTION) {
                try {
                    String json = Files.readString(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8);
                    int choice = JOptionPane.showOptionDialog(Ui.ownerWindow(dialogParent),
                            "Chọn cách nhập bộ Chính sách Action từ file JSON:",
                            "Import JSON Chính sách",
                            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                            new String[] {"Gộp thêm vào Sổ cái", "Thay thế toàn bộ (cũ vào Thùng rác)", "Hủy"},
                            "Gộp thêm vào Sổ cái");
                    if (choice == 0 || choice == 1) {
                        int n = ledger.importJson(json, choice == 1);
                        engine.saveActionPolicies();
                        refreshAll.run();
                        if (onAfterAction != null) onAfterAction.run();
                        JOptionPane.showMessageDialog(Ui.ownerWindow(dialogParent), "Đã nhập " + n + " chính sách thành công.");
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(Ui.ownerWindow(dialogParent), "Lỗi đọc file JSON: " + ex.getMessage());
                }
            }
        }));

        exportBtn.addActionListener(Ui.safe(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("jcrawl_action_policies.json"));
            fc.setFileFilter(new FileNameExtensionFilter("JSON Policy (*.json)", "json"));
            if (fc.showSaveDialog(dialogParent) == JFileChooser.APPROVE_OPTION) {
                try {
                    List<String> ids = selectedIds.get();
                    String json = ledger.exportJson(ids.isEmpty() ? null : ids);
                    Files.writeString(fc.getSelectedFile().toPath(), json, StandardCharsets.UTF_8);
                    JOptionPane.showMessageDialog(Ui.ownerWindow(dialogParent),
                            "Đã xuất " + (ids.isEmpty() ? ledger.size() : ids.size()) + " chính sách ra file JSON.");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(Ui.ownerWindow(dialogParent), "Lỗi ghi file JSON: " + ex.getMessage());
                }
            }
        }));

        clearHitsBtn.addActionListener(Ui.safe(e -> {
            ledger.resetAllHitCounts();
            engine.saveActionPolicies();
            refreshAll.run();
        }));

        resetAllBtn.addActionListener(Ui.safe(e -> {
            int ok = JOptionPane.showConfirmDialog(Ui.ownerWindow(dialogParent),
                    "Nhả toàn bộ cấu hình Action về Nguyên bản (NONE)?\n"
                            + "Mọi chính sách hiện có sẽ được chuyển an toàn vào Thùng rác Hoàn tác để khôi phục khi cần.",
                    "Xác nhận Nhả toàn bộ (Reset All)", JOptionPane.YES_NO_OPTION);
            if (ok == JOptionPane.YES_OPTION) {
                engine.resetAllActionControls();
                refreshAll.run();
                if (onAfterAction != null) onAfterAction.run();
            }
        }));

        trashBtn.addActionListener(Ui.safe(e -> showTrashDialog(dialogParent, engine, refreshAll, onAfterAction)));

        // Live sync when ledger changes from other tabs (e.g. Gate Auditor, Guide Playbooks, or Quick Strategy)
        ledger.addListener(() -> SwingUtilities.invokeLater(refreshAll));
        refreshAll.run();
        return root;
    }

    private static void showTrashDialog(Component parent, Engine engine, Runnable refreshAll, Runnable onAfterAction) {
        ActionPolicyLedger ledger = engine.actionPolicyLedger();
        List<DeletedEntry> items = ledger.trash();
        if (items.isEmpty()) {
            JOptionPane.showMessageDialog(Ui.ownerWindow(parent), "Thùng rác Hoàn tác đang trống.");
            return;
        }
        JDialog dlg = new JDialog(Ui.ownerWindow(SwingUtilities.getWindowAncestor(parent)),
                "Thùng rác Hoàn tác Chính sách (" + items.size() + " mục)", JDialog.ModalityType.APPLICATION_MODAL);
        Theme th = Theme.current();
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBackground(th.surface);
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        DefaultTableModel tm = new DefaultTableModel(
                new String[] {"Xóa lúc", "Tên chính sách", "Phạm vi", "Hành vi", "Lý do xóa"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        for (DeletedEntry d : items) {
            tm.addRow(new Object[] {
                    ActionPolicy.formatTimestamp(d.deletedAt()),
                    d.policy().name(),
                    d.policy().scopeType().label,
                    d.policy().behaviorSummary(),
                    d.reason()
            });
        }
        JTable tbl = new ThemedTable(tm);
        tbl.setRowHeight(25);
        tbl.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (tm.getRowCount() > 0) tbl.setRowSelectionInterval(0, 0);
        JScrollPane sc = new JScrollPane(tbl);
        sc.setColumnHeaderView(tbl.getTableHeader());
        p.add(sc, BorderLayout.CENTER);

        JPanel foot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        foot.setOpaque(false);
        JButton restoreBtn = Ui.solidPrimary("Khôi phục mục đang chọn", t -> Icons.undo(t.accent.onSolid()), null);
        JButton restoreAllBtn = Ui.secondaryBtn("Khôi phục tất cả", null);
        JButton closeBtn = Ui.secondaryBtn("Đóng", null);

        restoreBtn.addActionListener(Ui.safe(e -> {
            int row = tbl.getSelectedRow();
            if (row >= 0 && row < items.size()) {
                ledger.restoreFromTrash(items.get(row).policy().id());
                engine.saveActionPolicies();
                refreshAll.run();
                if (onAfterAction != null) onAfterAction.run();
                dlg.dispose();
            }
        }));
        restoreAllBtn.addActionListener(Ui.safe(e -> {
            for (DeletedEntry d : items) {
                ledger.restoreFromTrash(d.policy().id());
            }
            engine.saveActionPolicies();
            refreshAll.run();
            if (onAfterAction != null) onAfterAction.run();
            dlg.dispose();
        }));
        closeBtn.addActionListener(Ui.safe(e -> dlg.dispose()));

        foot.add(restoreAllBtn);
        foot.add(restoreBtn);
        foot.add(closeBtn);
        p.add(foot, BorderLayout.SOUTH);

        dlg.setContentPane(p);
        dlg.setSize(760, 380);
        dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
    }
}
