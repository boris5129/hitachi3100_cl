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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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

    // 과거 기록(기간) 검색
    private static final int MAX_DISPLAY_ROWS = 1000;      // 표에 한 번에 보여줄 최대 행 수 (화면 지연 방지)
    private static final int MAX_ARCHIVE_ROWS = 2000;
    private JTextField fromDateField;
    private JTextField toDateField;
    private JButton archiveSearchBtn;
    private JLabel infoLabel;
    private boolean archiveMode = false;
    private int searchSeq = 0;
    private javax.swing.Timer debounceTimer;

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
        statusFilterCombo.addActionListener(e -> onFilterChanged());
        left.add(statusFilterCombo);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        JButton printBtn = UIStyle.createPrimaryButton("🖨️ 선택 환자 리포트 출력 / 인쇄");
        printBtn.addActionListener(e -> onPrintSelectedReport());
        right.add(printBtn);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);

        // 두 번째 줄: 과거 기록 기간 검색 (메모리에는 최근 기록만 올라와 있음)
        JPanel archiveRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        archiveRow.setOpaque(false);
        archiveRow.add(new JLabel("📅 과거 기록 기간 검색:"));
        LocalDate defaultTo = historyService.getMemoryFrom() != null ? historyService.getMemoryFrom().toLocalDate() : LocalDate.now();
        fromDateField = new JTextField(defaultTo.minusYears(1).toString(), 8);
        toDateField = new JTextField(defaultTo.toString(), 8);
        fromDateField.setToolTipText("시작일 (yyyy-MM-dd)");
        toDateField.setToolTipText("종료일 (yyyy-MM-dd)");
        archiveRow.add(fromDateField);
        archiveRow.add(new JLabel("~"));
        archiveRow.add(toDateField);
        archiveSearchBtn = UIStyle.createPrimaryButton("과거 기록 검색");
        archiveSearchBtn.addActionListener(e -> runArchiveSearch());
        archiveRow.add(archiveSearchBtn);
        JButton backBtn = UIStyle.createSecondaryButton("최근 기록으로 돌아가기");
        backBtn.addActionListener(e -> {
            archiveMode = false;
            searchSeq++;
            refreshHistory();
        });
        archiveRow.add(backBtn);
        infoLabel = new JLabel(" ");
        infoLabel.setFont(UIStyle.FONT_REGULAR);
        archiveRow.add(infoLabel);
        bar.add(archiveRow, BorderLayout.SOUTH);
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
        // 새 결과가 들어와도 과거 기록 검색 결과 화면은 건드리지 않는다
        historyService.addChangeListener(() -> {
            if (!archiveMode) refreshHistory();
        });

        // 실시간 검색: 입력이 250ms 멈춘 뒤에 한 번만 갱신 (한 글자마다 전체 필터링하지 않음)
        debounceTimer = new javax.swing.Timer(250, e -> onFilterChanged());
        debounceTimer.setRepeats(false);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                debounceTimer.restart();
            }
            @Override
            public void removeUpdate(DocumentEvent e) {
                debounceTimer.restart();
            }
            @Override
            public void changedUpdate(DocumentEvent e) {
                debounceTimer.restart();
            }
        });
    }

    private void onFilterChanged() {
        if (archiveMode) runArchiveSearch(); else refreshHistory();
    }

    private void refreshHistory() {
        SwingUtilities.invokeLater(() -> {
            if (archiveMode) return;
            String query = searchField.getText();
            String statusFilter = (String) statusFilterCombo.getSelectedItem();

            List<PatientRecord> all = historyService.filter(query, statusFilter);
            List<PatientRecord> shown = all.size() > MAX_DISPLAY_ROWS ? new ArrayList<>(all.subList(0, MAX_DISPLAY_ROWS)) : all;
            populate(shown);

            String base = "메모리: 최근 " + historyService.getMemoryDays() + "일 기록 (" + historyService.getMemoryFrom().toLocalDate() + " 이후)";
            if (all.size() > MAX_DISPLAY_ROWS) {
                infoLabel.setText(base + " | 일치 " + all.size() + "건 중 최신 " + MAX_DISPLAY_ROWS + "건 표시 - 검색어로 좁혀 주세요");
            } else {
                infoLabel.setText(base);
            }
            infoLabel.setForeground(Color.DARK_GRAY);
        });
    }

    /** 표에 목록을 채운다 (EDT 에서 호출) */
    private void populate(List<PatientRecord> rows) {
        currentDisplayedList = rows;
        historyTableModel.setRowCount(0);
        for (PatientRecord r : rows) {
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
    }

    /** 오래된 기록을 파일에서 찾는다. 파일 스캔은 백그라운드 스레드에서 수행해 화면이 멈추지 않는다. */
    private void runArchiveSearch() {
        final LocalDate from;
        final LocalDate to;
        try {
            from = LocalDate.parse(fromDateField.getText().trim());
            to = LocalDate.parse(toDateField.getText().trim());
        } catch (DateTimeParseException ex) {
            infoLabel.setText("날짜는 yyyy-MM-dd 형식으로 입력하세요 (예: 2026-01-31)");
            infoLabel.setForeground(UIStyle.COLOR_HIGH_TEXT);
            return;
        }
        if (from.isAfter(to)) {
            infoLabel.setText("시작일이 종료일보다 늦습니다.");
            infoLabel.setForeground(UIStyle.COLOR_HIGH_TEXT);
            return;
        }
        final String query = searchField.getText();
        final String status = (String) statusFilterCombo.getSelectedItem();
        final int seq = ++searchSeq;
        archiveMode = true;
        archiveSearchBtn.setEnabled(false);
        infoLabel.setText("검색 중... (" + from + " ~ " + to + ")");
        infoLabel.setForeground(Color.DARK_GRAY);

        new SwingWorker<HistoryService.ArchiveResult, Void>() {
            @Override
            protected HistoryService.ArchiveResult doInBackground() throws Exception {
                return historyService.searchArchive(from, to, query, status, MAX_ARCHIVE_ROWS);
            }

            @Override
            protected void done() {
                if (seq == searchSeq) archiveSearchBtn.setEnabled(true);
                if (seq != searchSeq) return;     // 더 최근 검색이 있으면 이 결과는 버린다
                try {
                    HistoryService.ArchiveResult res = get();
                    populate(res.rows);
                    String msg = "과거 기록 검색 결과: " + res.rows.size() + "건 (" + from + " ~ " + to + ")";
                    if (res.truncated) msg += " - 일치 " + res.matchedScanned + "건 중 최신 " + MAX_ARCHIVE_ROWS + "건만 표시, 기간/검색어를 좁혀 주세요";
                    infoLabel.setText(msg);
                    infoLabel.setForeground(Color.DARK_GRAY);
                } catch (Exception ex) {
                    infoLabel.setText("검색 실패: " + ex.getMessage());
                    infoLabel.setForeground(UIStyle.COLOR_HIGH_TEXT);
                    com.hitachi3100.util.AppLog.error("과거 기록 검색 실패", ex);
                }
            }
        }.execute();
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
