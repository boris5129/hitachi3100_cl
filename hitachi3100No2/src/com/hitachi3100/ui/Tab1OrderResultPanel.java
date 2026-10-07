package com.hitachi3100.ui;

import com.hitachi3100.model.*;
import com.hitachi3100.protocol.Hitachi3100Constants;
import com.hitachi3100.protocol.IHitachiChannel;
import com.hitachi3100.service.*;
import com.hitachi3100.ui.components.PatientReportDialog;
import com.hitachi3100.ui.theme.UIStyle;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * [탭 1] 검사 오더 & 결과 수신 (Disk 1~35)
 */
public class Tab1OrderResultPanel extends JPanel {

    private final OrderService orderService;
    private final PresetService presetService;
    private final QCService qcService;
    private final HistoryService historyService;

    // 입력 컴포넌트
    private JSpinner positionSpinner;
    private JTextField sampleIdField;
    private final Map<TestItem, JCheckBox> itemCheckBoxMap = new EnumMap<>(TestItem.class);
    private JComboBox<PresetPanel> presetComboBox;
    private JCheckBox statCheckBox;
    private JCheckBox idModeCheckBox;
    private boolean refreshingPresets = false;   // 콤보박스 갱신 중에는 프리셋을 자동 적용하지 않는다

    // 오더 대기 테이블
    private DefaultTableModel orderTableModel;
    private JTable orderTable;

    // 결과 수신 테이블 및 요약 뷰
    private JLabel summaryTextLabel;
    private DefaultTableModel resultTableModel;
    private JTable resultTable;
    private List<TestResult> lastResults = new ArrayList<>();
    private Order lastCompletedOrder = null;

    // 통신 상태 라벨
    private JLabel comStatusLabel;

    public Tab1OrderResultPanel(OrderService orderService, PresetService presetService, QCService qcService, HistoryService historyService) {
        this.orderService = orderService;
        this.presetService = presetService;
        this.qcService = qcService;
        this.historyService = historyService;

        setLayout(new BorderLayout(10, 10));
        setBackground(UIStyle.COLOR_BG_APP);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        initUI();
        initListeners();
    }

    private void initUI() {
        // 상단 통신 상태 및 빠른 액션 바
        add(createTopToolBar(), BorderLayout.NORTH);

        // 중앙 분할: 좌측(오더 입력 패널) vs 우측(오더 큐 + 실시간 결과 수신 패널)
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createOrderEntryPanel(), createRightDisplayPanel());
        splitPane.setDividerLocation(420);
        splitPane.setResizeWeight(0.35);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);
    }

    private JPanel createTopToolBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Color.WHITE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIStyle.COLOR_BORDER, 1),
                new EmptyBorder(8, 12, 8, 12)
        ));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        JLabel title = new JLabel("Hitachi 3100 검사 오더 및 실시간 결과 수신");
        title.setFont(UIStyle.FONT_HEADER);
        title.setForeground(new Color(30, 41, 59));
        left.add(title);

        comStatusLabel = new JLabel("● 통신 연결 확인 중...");
        comStatusLabel.setFont(UIStyle.FONT_BOLD);
        comStatusLabel.setForeground(Color.GRAY);
        left.add(comStatusLabel);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        JButton testCommBtn = UIStyle.createSecondaryButton("🔄 통신 재연결 테스트");
        testCommBtn.addActionListener(e -> testCommunication());
        right.add(testCommBtn);

        JButton printReportBtn = UIStyle.createPrimaryButton("🖨️ 선택 환자 리포트 출력");
        printReportBtn.addActionListener(e -> printCurrentPatientReport());
        right.add(printReportBtn);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JPanel createOrderEntryPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(Color.WHITE);
        panel.setBorder(UIStyle.createCardBorder("검사 오더 입력 (Disk 1~35)"));

        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));
        formPanel.setOpaque(false);

        // 1. Position & Sample ID
        JPanel posIdRow = new JPanel(new GridLayout(2, 2, 8, 6));
        posIdRow.setOpaque(false);
        posIdRow.setBorder(new EmptyBorder(4, 4, 8, 4));

        posIdRow.add(new JLabel("Disk Position (1~" + Hitachi3100Constants.MAX_POSITION + "):"));
        posIdRow.add(new JLabel("환자 / 검체 ID:"));

        positionSpinner = new JSpinner(new SpinnerNumberModel(orderService.getCurrentDiskPosition(), 1, Hitachi3100Constants.MAX_POSITION, 1));
        positionSpinner.setFont(UIStyle.FONT_BOLD);
        posIdRow.add(positionSpinner);

        sampleIdField = new JTextField(orderService.getNextPatientId());
        sampleIdField.setFont(UIStyle.FONT_BOLD);
        posIdRow.add(sampleIdField);

        formPanel.add(posIdRow);

        // 검체 종류 / 통신 모드 옵션
        // (Control / Calibration 검체는 Host 가 TS 를 지시할 수 없고 장비에서 직접 측정하므로 여기서 오더하지 않는다)
        JPanel quickIdBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        quickIdBar.setOpaque(false);

        statCheckBox = new JCheckBox("STAT (긴급)");
        statCheckBox.setOpaque(false);
        statCheckBox.setToolTipText("Stat 검체는 Batch 전송이 불가하며 장비의 실시간 TS 문의 시 전달됩니다");
        quickIdBar.add(statCheckBox);

        idModeCheckBox = new JCheckBox("ID 모드 (바코드 리더 장착)", orderService.isIdMode());
        idModeCheckBox.setOpaque(false);
        idModeCheckBox.setToolTipText("해제하면 Sample No. 모드(바코드 리더 없음)로 TS 를 전송합니다. 장비 설정과 일치해야 합니다.");
        idModeCheckBox.addActionListener(e -> orderService.setIdMode(idModeCheckBox.isSelected()));
        quickIdBar.add(idModeCheckBox);

        formPanel.add(quickIdBar);
        formPanel.add(Box.createVerticalStrut(8));

        // 2. 프리셋 선택 및 사용자 세트 저장
        JPanel presetRow = new JPanel(new BorderLayout(6, 4));
        presetRow.setOpaque(false);
        presetRow.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(UIStyle.COLOR_BORDER), "검사 패널 / 세트 (Preset)", 0, 0, UIStyle.FONT_BOLD));

        JPanel presetTop = new JPanel(new GridLayout(1, 3, 4, 4));
        presetTop.setOpaque(false);

        JButton liverBtn = new JButton("간기능 패널");
        liverBtn.addActionListener(e -> applyPreset(PresetPanel.liverPanel()));
        presetTop.add(liverBtn);

        JButton kidneyBtn = new JButton("신장/지질 패널");
        kidneyBtn.addActionListener(e -> applyPreset(PresetPanel.kidneyLipidPanel()));
        presetTop.add(kidneyBtn);

        JButton diabetesBtn = new JButton("당뇨/특수 패널");
        diabetesBtn.addActionListener(e -> applyPreset(PresetPanel.diabetesSpecialPanel()));
        presetTop.add(diabetesBtn);

        JPanel presetBottom = new JPanel(new BorderLayout(6, 4));
        presetBottom.setOpaque(false);
        presetBottom.setBorder(new EmptyBorder(4, 0, 4, 0));

        presetComboBox = new JComboBox<>();
        refreshPresetComboBox();
        presetComboBox.addActionListener(e -> {
            if (refreshingPresets) return;
            PresetPanel p = (PresetPanel) presetComboBox.getSelectedItem();
            if (p != null) {
                applyPreset(p);
            }
        });
        presetBottom.add(presetComboBox, BorderLayout.CENTER);

        JButton saveCustomSetBtn = new JButton("내 세트 저장");
        saveCustomSetBtn.addActionListener(e -> saveCustomPreset());
        presetBottom.add(saveCustomSetBtn, BorderLayout.EAST);

        presetRow.add(presetTop, BorderLayout.NORTH);
        presetRow.add(presetBottom, BorderLayout.SOUTH);
        formPanel.add(presetRow);
        formPanel.add(Box.createVerticalStrut(8));

        // 3. 17개 화학 검사 항목 체크박스 영역
        JPanel itemsBox = new JPanel(new BorderLayout());
        itemsBox.setOpaque(false);
        itemsBox.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(UIStyle.COLOR_BORDER), "검사 항목 선택 (17개 항목)", 0, 0, UIStyle.FONT_BOLD));

        JPanel checkAllBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        checkAllBar.setOpaque(false);
        JButton selectAllBtn = new JButton("전체 선택");
        selectAllBtn.setFont(UIStyle.FONT_SMALL);
        selectAllBtn.addActionListener(e -> itemCheckBoxMap.values().forEach(cb -> cb.setSelected(true)));
        JButton deselectAllBtn = new JButton("전체 해제");
        deselectAllBtn.setFont(UIStyle.FONT_SMALL);
        deselectAllBtn.addActionListener(e -> itemCheckBoxMap.values().forEach(cb -> cb.setSelected(false)));
        checkAllBar.add(selectAllBtn);
        checkAllBar.add(deselectAllBtn);
        itemsBox.add(checkAllBar, BorderLayout.NORTH);

        JPanel gridItems = new JPanel(new GridLayout(6, 3, 4, 4));
        gridItems.setOpaque(false);
        gridItems.setBorder(new EmptyBorder(4, 8, 8, 8));

        for (TestItem item : TestItem.values()) {
            JCheckBox cb = new JCheckBox(item.getCode() + " (" + item.getUnit() + ")");
            cb.setFont(UIStyle.FONT_REGULAR);
            cb.setToolTipText(item.getFullName() + " [Ch " + item.getChannel() + "]");
            itemCheckBoxMap.put(item, cb);
            gridItems.add(cb);
        }
        itemsBox.add(gridItems, BorderLayout.CENTER);
        formPanel.add(itemsBox);

        panel.add(formPanel, BorderLayout.CENTER);

        // 하단 오더 추가 버튼
        JButton addOrderBtn = UIStyle.createPrimaryButton("➕ 오더 추가 (Add Order)");
        addOrderBtn.setFont(UIStyle.FONT_HEADER);
        addOrderBtn.setPreferredSize(new Dimension(0, 42));
        addOrderBtn.addActionListener(e -> onAddOrder());
        panel.add(addOrderBtn, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createRightDisplayPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 8, 8));
        panel.setOpaque(false);

        // 상단: 오더 대기 목록 및 전송 버튼
        JPanel queuePanel = new JPanel(new BorderLayout(6, 6));
        queuePanel.setBackground(Color.WHITE);
        queuePanel.setBorder(UIStyle.createCardBorder("검사 대기 큐 (Order Queue)"));

        String[] orderCols = {"Pos", "검체 ID", "종류", "검사항목", "상태"};
        orderTableModel = new DefaultTableModel(orderCols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        orderTable = new JTable(orderTableModel);
        orderTable.setRowHeight(24);
        orderTable.setFont(UIStyle.FONT_REGULAR);
        orderTable.getTableHeader().setFont(UIStyle.FONT_BOLD);
        orderTable.getColumnModel().getColumn(0).setPreferredWidth(45);
        orderTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        orderTable.getColumnModel().getColumn(2).setPreferredWidth(65);
        orderTable.getColumnModel().getColumn(3).setPreferredWidth(200);
        orderTable.getColumnModel().getColumn(4).setPreferredWidth(70);

        JScrollPane orderScroll = new JScrollPane(orderTable);
        orderScroll.setBorder(BorderFactory.createLineBorder(UIStyle.COLOR_BORDER));
        queuePanel.add(orderScroll, BorderLayout.CENTER);

        JPanel queueButtonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        queueButtonBar.setOpaque(false);

        JButton clearQueueBtn = UIStyle.createSecondaryButton("큐 비우기");
        clearQueueBtn.addActionListener(e -> orderService.clearPendingOrders());
        queueButtonBar.add(clearQueueBtn);

        JButton startAnalysisBtn = UIStyle.createPrimaryButton("▶ 오더 전송 (Send TS to AU)");
        startAnalysisBtn.setFont(UIStyle.FONT_BOLD);
        startAnalysisBtn.addActionListener(e -> onStartAnalysis());
        queueButtonBar.add(startAnalysisBtn);

        queuePanel.add(queueButtonBar, BorderLayout.SOUTH);
        panel.add(queuePanel);

        // 하단: 실시간 결과 수신 뷰
        JPanel resultPanel = new JPanel(new BorderLayout(6, 6));
        resultPanel.setBackground(Color.WHITE);
        resultPanel.setBorder(UIStyle.createCardBorder("실시간 검사 결과 수신 (Real-time Results)"));

        // 최신 결과 한 줄 요약 배너 (High/Low 강조)
        summaryTextLabel = new JLabel("수신 대기 중...");
        summaryTextLabel.setFont(UIStyle.FONT_BOLD);
        summaryTextLabel.setOpaque(true);
        summaryTextLabel.setBackground(new Color(241, 245, 249));
        summaryTextLabel.setForeground(new Color(30, 41, 59));
        summaryTextLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIStyle.COLOR_BORDER),
                new EmptyBorder(8, 12, 8, 12)
        ));
        resultPanel.add(summaryTextLabel, BorderLayout.NORTH);

        String[] resCols = {"검사항목", "측정 수치", "단위", "판정(Flag)", "알람", "참고 범위"};
        resultTableModel = new DefaultTableModel(resCols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        resultTable = new JTable(resultTableModel);
        resultTable.setRowHeight(24);
        resultTable.setFont(UIStyle.FONT_REGULAR);
        resultTable.getTableHeader().setFont(UIStyle.FONT_BOLD);

        // High/Low 색상 렌더러
        resultTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object val, boolean isSel, boolean hasFoc, int row, int col) {
                Component c = super.getTableCellRendererComponent(table, val, isSel, hasFoc, row, col);
                setHorizontalAlignment(SwingConstants.CENTER);
                String flag = val != null ? val.toString() : "";
                if ("H".equalsIgnoreCase(flag)) {
                    c.setForeground(UIStyle.COLOR_HIGH_TEXT);
                    setFont(UIStyle.FONT_BOLD);
                } else if ("L".equalsIgnoreCase(flag)) {
                    c.setForeground(UIStyle.COLOR_LOW_TEXT);
                    setFont(UIStyle.FONT_BOLD);
                } else {
                    c.setForeground(UIStyle.COLOR_NORMAL_TEXT);
                    setFont(UIStyle.FONT_REGULAR);
                }
                return c;
            }
        });

        JScrollPane resScroll = new JScrollPane(resultTable);
        resScroll.setBorder(BorderFactory.createLineBorder(UIStyle.COLOR_BORDER));
        resultPanel.add(resScroll, BorderLayout.CENTER);

        panel.add(resultPanel);
        return panel;
    }

    /** Swing HTML 라벨에 넣을 문자열 이스케이프 */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void initListeners() {
        // 오더 변경 시 큐 갱신
        orderService.addOrderChangeListener(this::refreshOrderQueueTable);

        // 결과 수신 시 테이블 및 요약 갱신
        orderService.addResultListener((order, results, summary) -> SwingUtilities.invokeLater(() -> {
            this.lastCompletedOrder = order;
            this.lastResults = new ArrayList<>(results);

            // 요약 라벨 업데이트 (HTML로 H/L 색상 강조)
            StringBuilder html = new StringBuilder("<html>[Pos " + order.getPosition() + "] <b>" + esc(order.getSampleId()) + "</b>: ");
            for (int i = 0; i < results.size(); i++) {
                if (i > 0) html.append(" | ");
                TestResult tr = results.get(i);
                if ("H".equalsIgnoreCase(tr.getFlag())) {
                    html.append(tr.getItem().getCode()).append(": ").append(tr.getFormattedValue()).append(" ").append(tr.getUnit())
                            .append(" <font color='red'><b>(H)</b></font>");
                } else if ("L".equalsIgnoreCase(tr.getFlag())) {
                    html.append(tr.getItem().getCode()).append(": ").append(tr.getFormattedValue()).append(" ").append(tr.getUnit())
                            .append(" <font color='blue'><b>(L)</b></font>");
                } else {
                    html.append(tr.getItem().getCode()).append(": ").append(tr.getFormattedValue()).append(" ").append(tr.getUnit())
                            .append(" <font color='green'>(Normal)</font>");
                }
                if (tr.hasAlarm()) {
                    html.append(" <font color='#b45309'>[알람 ").append(esc(tr.getAlarmCode())).append("]</font>");
                }
            }
            html.append("</html>");
            summaryTextLabel.setText(html.toString());

            // 테이블 갱신
            resultTableModel.setRowCount(0);
            for (TestResult tr : results) {
                resultTableModel.addRow(new Object[]{
                        tr.getItem().getCode(),
                        tr.getFormattedValue(),
                        tr.getUnit(),
                        tr.getFlag(),
                        tr.hasAlarm() ? tr.getAlarmCode() + " " + tr.getAlarmDescription() : "",
                        tr.getRefRange()
                });
            }
        }));
    }

    private void onAddOrder() {
        int pos = (Integer) positionSpinner.getValue();
        String id = sampleIdField.getText().trim();
        if (id.isEmpty()) {
            JOptionPane.showMessageDialog(this, "환자 / 검체 ID를 입력해 주세요.", "입력 확인", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<TestItem> selectedItems = new ArrayList<>();
        for (Map.Entry<TestItem, JCheckBox> entry : itemCheckBoxMap.entrySet()) {
            if (entry.getValue().isSelected()) {
                selectedItems.add(entry.getKey());
            }
        }

        if (selectedItems.isEmpty()) {
            JOptionPane.showMessageDialog(this, "검사할 항목을 최소 1개 이상 선택해 주세요.", "항목 선택 확인", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 오더 추가 (OrderService에서 Disk Pos 및 환자 ID가 +1 자동 증가됨)
        try {
            orderService.addOrder(pos, id, selectedItems, statCheckBox.isSelected());
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "오더 입력 확인", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // UI 입력 필드에 자동 증가된 값 반영
        positionSpinner.setValue(orderService.getCurrentDiskPosition());
        sampleIdField.setText(orderService.getNextPatientId());
    }

    private void onStartAnalysis() {
        List<Order> pending = orderService.getPendingOrders();
        long waitingRoutine = pending.stream().filter(o -> o.getStatus() == Order.OrderStatus.WAITING && !o.isStat()).count();
        long waitingStat = pending.stream().filter(o -> o.getStatus() == Order.OrderStatus.WAITING && o.isStat()).count();
        if (waitingRoutine == 0 && waitingStat == 0) {
            JOptionPane.showMessageDialog(this, "전송할 대기 오더가 없습니다.", "오더 없음", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        try {
            int queued = orderService.transmitPendingOrders();
            StringBuilder msg = new StringBuilder();
            if (queued > 0) {
                msg.append("Routine ").append(queued).append("건을 전송 대기열에 올렸습니다.\n")
                   .append("장비의 다음 통신 사이클(ANY)에 TS(SPE)로 전달되며, 전달되면 상태가 '전송됨'으로 바뀝니다.\n");
            }
            if (waitingStat > 0) {
                msg.append("Stat ").append(waitingStat).append("건은 Batch 전송이 불가하여 장비가 TS 를 문의할 때 전달됩니다.\n");
            }
            msg.append("\n측정 시작은 장비(Start Conditions)에서 합니다.");
            JOptionPane.showMessageDialog(this, msg.toString(), "오더 전송 요청", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "전송 실패: " + e.getMessage(), "통신 오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applyPreset(PresetPanel preset) {
        if (preset == null) return;
        Set<TestItem> set = new HashSet<>(preset.getItems());
        for (Map.Entry<TestItem, JCheckBox> entry : itemCheckBoxMap.entrySet()) {
            entry.getValue().setSelected(set.contains(entry.getKey()));
        }
    }

    private void saveCustomPreset() {
        List<TestItem> selected = new ArrayList<>();
        for (Map.Entry<TestItem, JCheckBox> entry : itemCheckBoxMap.entrySet()) {
            if (entry.getValue().isSelected()) {
                selected.add(entry.getKey());
            }
        }
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, "저장할 검사 항목을 선택해 주세요.", "알림", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String name = JOptionPane.showInputDialog(this, "저장할 세트(패널) 이름을 입력하세요:", "내 세트 저장", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.trim().isEmpty()) {
            presetService.saveUserPreset(name.trim(), selected);
            refreshPresetComboBox();
            JOptionPane.showMessageDialog(this, "'" + name.trim() + "' 세트가 성공적으로 저장되었습니다.", "저장 완료", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void refreshPresetComboBox() {
        PresetPanel previous = (PresetPanel) presetComboBox.getSelectedItem();
        refreshingPresets = true;
        try {
            presetComboBox.removeAllItems();
            for (PresetPanel p : presetService.getAllPresets()) {
                presetComboBox.addItem(p);
            }
            if (previous != null) {
                for (int i = 0; i < presetComboBox.getItemCount(); i++) {
                    if (presetComboBox.getItemAt(i).getName().equals(previous.getName())) {
                        presetComboBox.setSelectedIndex(i);
                        break;
                    }
                }
            }
        } finally {
            refreshingPresets = false;
        }
    }

    private void refreshOrderQueueTable() {
        SwingUtilities.invokeLater(() -> {
            orderTableModel.setRowCount(0);
            for (Order o : orderService.getPendingOrders()) {
                String type = o.isStat() ? "STAT" : "Routine";
                orderTableModel.addRow(new Object[]{
                        o.getPosition(),
                        o.getSampleId(),
                        type,
                        o.getItemsSummary(),
                        o.getStatus().getLabel()
                });
            }
        });
    }

    private void testCommunication() {
        IHitachiChannel ch = orderService.getCommunicationChannel();
        if (ch != null && ch.isConnected()) {
            comStatusLabel.setText("● 통신 정상 (" + ch.getChannelName() + ")");
            comStatusLabel.setForeground(UIStyle.COLOR_NORMAL_TEXT);
            JOptionPane.showMessageDialog(this, "Hitachi 3100 통신 상태: 정상 연결됨\n채널: " + ch.getChannelName(), "통신 테스트 성공", JOptionPane.INFORMATION_MESSAGE);
        } else {
            comStatusLabel.setText("● 통신 끊김");
            comStatusLabel.setForeground(UIStyle.COLOR_HIGH_TEXT);
            JOptionPane.showMessageDialog(this, "Hitachi 3100 통신이 연결되어 있지 않습니다.\n상단 메뉴에서 통신 모드를 확인하세요.", "통신 확인", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void printCurrentPatientReport() {
        if (lastCompletedOrder == null || lastResults.isEmpty()) {
            JOptionPane.showMessageDialog(this, "출력할 수신 완료 검사 결과가 없습니다.", "출력 불가", JOptionPane.WARNING_MESSAGE);
            return;
        }

        PatientRecord dummy = new PatientRecord(
                lastCompletedOrder.getOrderTime(),
                lastCompletedOrder.getPosition(),
                lastCompletedOrder.getSampleId(),
                lastCompletedOrder.getItemsSummary(),
                "완료",
                lastResults
        );

        PatientReportDialog dialog = new PatientReportDialog(SwingUtilities.getWindowAncestor(this), dummy);
        dialog.setVisible(true);
    }

    public void updateConnectionStatus(boolean connected, String message) {
        SwingUtilities.invokeLater(() -> {
            if (connected) {
                comStatusLabel.setText("● " + message);
                comStatusLabel.setForeground(UIStyle.COLOR_NORMAL_TEXT);
            } else {
                comStatusLabel.setText("● " + message);
                comStatusLabel.setForeground(UIStyle.COLOR_HIGH_TEXT);
            }
        });
    }
}
