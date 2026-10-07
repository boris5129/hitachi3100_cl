package com.hitachi3100.ui;

import com.hitachi3100.model.PatientRecord;
import com.hitachi3100.model.TestResult;
import com.hitachi3100.service.HistoryService;
import com.hitachi3100.ui.components.PatientReportDialog;
import com.hitachi3100.ui.theme.UIStyle;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * [탭 4] 환자 검사 이력 (History) 패널
 * - [검사일시 | Position | 환자 ID | 검사항목 | 상태 | 항목별 검사 결과]
 * - 환자 선택 시 그 환자에 맞는 검사항목 상세 확인
 * - results.csv 실시간 동기화 및 재시작 시 자동 복원
 * - 환자 ID 또는 Position 기반 실시간 검색/필터링 기능
 */
public class Tab4HistoryPanel extends JPanel {

    private final HistoryService historyService;

    // 검색 및 필터 컴포넌트
    private JTextField searchField;
    private JComboBox<String> statusFilterCombo;

    // 상단 메인 이력 테이블
    private DefaultTableModel historyTableModel;
    private JTable historyTable;
    private List<PatientRecord> currentDisplayedList = new ArrayList<>();

    // 하단 선택 환자 상세 패널
    private JLabel detailHeaderLabel;
    private DefaultTableModel detailTableModel;
    private JTable detailTable;

    public Tab4HistoryPanel(HistoryService historyService) {
        this.historyService = historyService;

        setLayout(new BorderLayout(10, 10));
        setBackground(UIStyle.COLOR_BG_APP);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        initUI();
        initListeners();
        refreshHistory();
    }

    private void initUI() {
        // 상단: 검색/필터 툴바
        add(createFilterToolBar(), BorderLayout.NORTH);

        // 중앙: 상하 분할 (상단: 환자 이력 목록 vs 하단: 선택 환자 세부 검사항목 및 수치 카드)
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, createMainHistoryPanel(), createDetailPanel());
        splitPane.setDividerLocation(350);
        splitPane.setResizeWeight(0.6);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);
    }

    private JPanel createFilterToolBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 10));
        bar.setBackground(Color.WHITE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIStyle.COLOR_BORDER, 1),
                new EmptyBorder(8, 14, 8, 14)
        ));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);

        left.add(new JLabel("🔍 실시간 검색 (환자 ID / Position):"));
        searchField = new JTextField(15);
        searchField.setFont(UIStyle.FONT_BOLD);
        left.add(searchField);

        left.add(new JLabel("상태 필터:"));
        statusFilterCombo = new JComboBox<>(new String[]{"전체", "정상", "이상치"});
        statusFilterCombo.setFont(UIStyle.FONT_REGULAR);
        statusFilterCombo.addActionListener(e -> refreshHistory());
        left.add(statusFilterCombo);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        JButton printBtn = UIStyle.createPrimaryButton("🖨️ 선택 환자 리포트 출력 / 인쇄");
        printBtn.addActionListener(e -> onPrintSelectedReport());
        right.add(printBtn);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JPanel createMainHistoryPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBackground(Color.WHITE);
        panel.setBorder(UIStyle.createCardBorder("전체 검사 이력 목록 (results.csv 연동)"));

        String[] cols = {"검사일시", "Position", "환자 ID", "검사항목", "상태", "항목별 검사 결과"};
        historyTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        historyTable = new JTable(historyTableModel);
        historyTable.setRowHeight(24);
        historyTable.setFont(UIStyle.FONT_REGULAR);
        historyTable.getTableHeader().setFont(UIStyle.FONT_BOLD);
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        historyTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        historyTable.getColumnModel().getColumn(1).setPreferredWidth(65);
        historyTable.getColumnModel().getColumn(2).setPreferredWidth(110);
        historyTable.getColumnModel().getColumn(3).setPreferredWidth(180);
        historyTable.getColumnModel().getColumn(4).setPreferredWidth(70);
        historyTable.getColumnModel().getColumn(5).setPreferredWidth(350);

        // 상태 열 렌더러
        historyTable.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object val, boolean isSel, boolean hasFoc, int row, int col) {
                Component c = super.getTableCellRendererComponent(tbl, val, isSel, hasFoc, row, col);
                setHorizontalAlignment(SwingConstants.CENTER);
                String st = val != null ? val.toString() : "";
                if ("이상치".equals(st)) {
                    c.setForeground(UIStyle.COLOR_HIGH_TEXT);
                    setFont(UIStyle.FONT_BOLD);
                } else {
                    c.setForeground(UIStyle.COLOR_NORMAL_TEXT);
                    setFont(UIStyle.FONT_BOLD);
                }
                return c;
            }
        });

        historyTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onPatientSelected();
            }
        });

        JScrollPane scrollPane = new JScrollPane(historyTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(UIStyle.COLOR_BORDER));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createDetailPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBackground(Color.WHITE);
        panel.setBorder(UIStyle.createCardBorder("선택 환자 세부 검사 항목 및 수치 결과"));

        detailHeaderLabel = new JLabel("환자를 선택하시면 해당 검체의 모든 세부 검사 항목과 판정 결과가 표시됩니다.");
        detailHeaderLabel.setFont(UIStyle.FONT_BOLD);
        detailHeaderLabel.setForeground(new Color(30, 41, 59));
        detailHeaderLabel.setBorder(new EmptyBorder(4, 8, 4, 8));
        panel.add(detailHeaderLabel, BorderLayout.NORTH);

        String[] cols = {"검사항목", "영문 명칭", "측정 수치", "단위", "판정(Flag)", "참고 범위", "알람 코드"};
        detailTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        detailTable = new JTable(detailTableModel);
        detailTable.setRowHeight(24);
        detailTable.setFont(UIStyle.FONT_REGULAR);
        detailTable.getTableHeader().setFont(UIStyle.FONT_BOLD);

        // High/Low 색상 렌더러
        detailTable.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object val, boolean isSel, boolean hasFoc, int row, int col) {
                Component c = super.getTableCellRendererComponent(tbl, val, isSel, hasFoc, row, col);
                setHorizontalAlignment(SwingConstants.CENTER);
                String fl = val != null ? val.toString() : "";
                if ("H".equalsIgnoreCase(fl)) {
                    c.setForeground(UIStyle.COLOR_HIGH_TEXT);
                    setFont(UIStyle.FONT_BOLD);
                } else if ("L".equalsIgnoreCase(fl)) {
                    c.setForeground(UIStyle.COLOR_LOW_TEXT);
                    setFont(UIStyle.FONT_BOLD);
                } else {
                    c.setForeground(UIStyle.COLOR_NORMAL_TEXT);
                    setFont(UIStyle.FONT_REGULAR);
                }
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(detailTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(UIStyle.COLOR_BORDER));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private void initListeners() {
        historyService.addChangeListener(this::refreshHistory);

        // 실시간 검색어 변경 감지
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshHistory();
            }
            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshHistory();
            }
            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshHistory();
            }
        });
    }

    private void refreshHistory() {
        SwingUtilities.invokeLater(() -> {
            String query = searchField.getText();
            String statusFilter = (String) statusFilterCombo.getSelectedItem();

            currentDisplayedList = historyService.filter(query, statusFilter);
            historyTableModel.setRowCount(0);

            for (PatientRecord r : currentDisplayedList) {
                historyTableModel.addRow(new Object[]{
                        r.getFormattedDateTime(),
                        r.getPosition(),
                        r.getPatientId(),
                        r.getTestItemsSummary(),
                        r.getStatus(),
                        r.getResultsSummary()
                });
            }

            if (historyTable.getRowCount() > 0 && historyTable.getSelectedRow() == -1) {
                historyTable.setRowSelectionInterval(0, 0);
            } else if (historyTable.getRowCount() == 0) {
                detailTableModel.setRowCount(0);
                detailHeaderLabel.setText("조건에 일치하는 검사 이력이 없습니다.");
            }
        });
    }

    private void onPatientSelected() {
        int row = historyTable.getSelectedRow();
        if (row < 0 || row >= currentDisplayedList.size()) return;

        PatientRecord record = currentDisplayedList.get(row);
        detailHeaderLabel.setText("<html>검체 ID: <b>" + record.getPatientId().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</b> (Disk Pos: " + record.getPosition() +
                ") | 검사일시: " + record.getFormattedDateTime() + " | 판정: <b>" + record.getStatus() + "</b></html>");

        detailTableModel.setRowCount(0);
        for (TestResult tr : record.getResults()) {
            detailTableModel.addRow(new Object[]{
                    tr.getItem().getCode(),
                    tr.getItem().getFullName(),
                    tr.getFormattedValue(),
                    tr.getUnit(),
                    tr.getFlag(),
                    tr.getRefRange(),
                    tr.getAlarmCode()
            });
        }
    }

    private void onPrintSelectedReport() {
        int row = historyTable.getSelectedRow();
        if (row < 0 || row >= currentDisplayedList.size()) {
            JOptionPane.showMessageDialog(this, "리포트를 출력할 환자를 먼저 테이블에서 선택해 주세요.", "선택 안내", JOptionPane.WARNING_MESSAGE);
            return;
        }

        PatientRecord record = currentDisplayedList.get(row);
        PatientReportDialog dialog = new PatientReportDialog(SwingUtilities.getWindowAncestor(this), record);
        dialog.setVisible(true);
    }
}
