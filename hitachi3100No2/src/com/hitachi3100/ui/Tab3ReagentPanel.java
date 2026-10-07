package com.hitachi3100.ui;

import com.hitachi3100.model.ReagentItem;
import com.hitachi3100.service.ReagentService;
import com.hitachi3100.ui.theme.UIStyle;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * [탭 3] 시약 잔량 관리 (R1, R2 개별 총 34개 독립 행)
 * - 각 검사항목별 R1, R2 독립 행 관리
 * - 결과 수신 시 자동 1 차감
 * - 잔량 부족(15% 이하) / 소진(0) 상태 표기
 * - 시약별 총 Test 수 변동 가능
 * - 시약 리셋은 변동한 수량 max로 리셋
 */
public class Tab3ReagentPanel extends JPanel {

    private final ReagentService reagentService;

    private DefaultTableModel tableModel;
    private JTable reagentTable;

    private JLabel totalCountLabel;
    private JLabel normalCountLabel;
    private JLabel lowCountLabel;
    private JLabel depletedCountLabel;

    public Tab3ReagentPanel(ReagentService reagentService) {
        this.reagentService = reagentService;

        setLayout(new BorderLayout(10, 10));
        setBackground(UIStyle.COLOR_BG_APP);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        initUI();
        initListeners();
        refreshTable();
    }

    private void initUI() {
        // 상단 요약 배너 및 관리 툴바
        add(createTopSummaryBar(), BorderLayout.NORTH);

        // 중앙: 34개 시약 테이블
        JPanel tableCard = new JPanel(new BorderLayout(8, 8));
        tableCard.setBackground(Color.WHITE);
        tableCard.setBorder(UIStyle.createCardBorder("시약 34개 개별 모니터링 (17개 항목 × R1 / R2)"));

        String[] cols = {"검사 항목", "시약 구분", "현재 잔량 (Tests)", "최대 용량 (Max)", "잔여 비율 (%)", "상태", "최종 사용일시"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        reagentTable = new JTable(tableModel);
        reagentTable.setRowHeight(26);
        reagentTable.setFont(UIStyle.FONT_REGULAR);
        reagentTable.getTableHeader().setFont(UIStyle.FONT_BOLD);
        reagentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // 상태 열 커스텀 렌더러
        reagentTable.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object val, boolean isSel, boolean hasFoc, int row, int col) {
                Component c = super.getTableCellRendererComponent(tbl, val, isSel, hasFoc, row, col);
                setHorizontalAlignment(SwingConstants.CENTER);
                String st = val != null ? val.toString() : "";
                if ("소진".equals(st)) {
                    c.setForeground(UIStyle.COLOR_DEPLETED_TEXT);
                    setFont(UIStyle.FONT_BOLD);
                } else if ("잔량 부족".equals(st)) {
                    c.setForeground(UIStyle.COLOR_WARN_TEXT);
                    setFont(UIStyle.FONT_BOLD);
                } else {
                    c.setForeground(UIStyle.COLOR_NORMAL_TEXT);
                    setFont(UIStyle.FONT_BOLD);
                }
                return c;
            }
        });

        // 잔량 및 Max 열 우측 정렬
        DefaultTableCellRenderer rightAlign = new DefaultTableCellRenderer();
        rightAlign.setHorizontalAlignment(SwingConstants.RIGHT);
        reagentTable.getColumnModel().getColumn(2).setCellRenderer(rightAlign);
        reagentTable.getColumnModel().getColumn(3).setCellRenderer(rightAlign);
        reagentTable.getColumnModel().getColumn(4).setCellRenderer(rightAlign);

        JScrollPane scrollPane = new JScrollPane(reagentTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(UIStyle.COLOR_BORDER));
        tableCard.add(scrollPane, BorderLayout.CENTER);

        add(tableCard, BorderLayout.CENTER);
    }

    private JPanel createTopSummaryBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 10));
        bar.setBackground(Color.WHITE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIStyle.COLOR_BORDER, 1),
                new EmptyBorder(10, 14, 10, 14)
        ));

        // 좌측 상태 뱃지 그룹
        JPanel leftBadges = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        leftBadges.setOpaque(false);

        totalCountLabel = new JLabel("총 시약: 34개 (R1: 17, R2: 17)");
        totalCountLabel.setFont(UIStyle.FONT_BOLD);
        leftBadges.add(totalCountLabel);

        normalCountLabel = new JLabel("정상: 34");
        normalCountLabel.setFont(UIStyle.FONT_BOLD);
        normalCountLabel.setForeground(UIStyle.COLOR_NORMAL_TEXT);
        leftBadges.add(normalCountLabel);

        lowCountLabel = new JLabel("잔량 부족: 0");
        lowCountLabel.setFont(UIStyle.FONT_BOLD);
        lowCountLabel.setForeground(UIStyle.COLOR_WARN_TEXT);
        leftBadges.add(lowCountLabel);

        depletedCountLabel = new JLabel("소진: 0");
        depletedCountLabel.setFont(UIStyle.FONT_BOLD);
        depletedCountLabel.setForeground(UIStyle.COLOR_DEPLETED_TEXT);
        leftBadges.add(depletedCountLabel);

        bar.add(leftBadges, BorderLayout.WEST);

        // 우측 액션 버튼들
        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightActions.setOpaque(false);

        JButton changeMaxBtn = UIStyle.createSecondaryButton("⚙️ 선택 시약 총 Test 수(Max) 변경");
        changeMaxBtn.addActionListener(e -> onChangeSelectedMaxCapacity());
        rightActions.add(changeMaxBtn);

        JButton resetSelectedBtn = UIStyle.createSecondaryButton("🔄 선택 시약 리셋 (Max로 복원)");
        resetSelectedBtn.addActionListener(e -> onResetSelectedReagent());
        rightActions.add(resetSelectedBtn);

        JButton resetAllBtn = UIStyle.createPrimaryButton("⚡ 전체 34개 시약 일괄 리셋");
        resetAllBtn.addActionListener(e -> onResetAllReagents());
        rightActions.add(resetAllBtn);

        bar.add(rightActions, BorderLayout.EAST);
        return bar;
    }

    private void initListeners() {
        reagentService.addChangeListener(this::refreshTable);
    }

    private void refreshTable() {
        SwingUtilities.invokeLater(() -> {
            int selectedRow = reagentTable.getSelectedRow();   // 결과 수신 때마다 선택이 풀리지 않도록 보존
            tableModel.setRowCount(0);
            List<ReagentItem> list = reagentService.getAllReagents();

            int normalCount = 0;
            int lowCount = 0;
            int depletedCount = 0;

            for (ReagentItem r : list) {
                if (r.getStatus() == ReagentItem.ReagentStatus.NORMAL) normalCount++;
                else if (r.getStatus() == ReagentItem.ReagentStatus.LOW) lowCount++;
                else if (r.getStatus() == ReagentItem.ReagentStatus.DEPLETED) depletedCount++;

                tableModel.addRow(new Object[]{
                        r.getItem().getCode() + " (" + r.getItem().getFullName() + ")",
                        r.getType().name(),
                        r.getCurrentTests() + " 회",
                        r.getMaxTests() + " 회",
                        String.format(java.util.Locale.ROOT, "%.1f %%", r.getRemainingPercentage()),
                        r.getStatus().getLabel(),
                        r.getFormattedLastUsedTime()
                });
            }

            if (selectedRow >= 0 && selectedRow < tableModel.getRowCount()) {
                reagentTable.setRowSelectionInterval(selectedRow, selectedRow);
            }
            normalCountLabel.setText("정상: " + normalCount);
            lowCountLabel.setText("잔량 부족: " + lowCount);
            depletedCountLabel.setText("소진: " + depletedCount);
        });
    }

    private ReagentItem getSelectedReagent() {
        int row = reagentTable.getSelectedRow();
        if (row < 0 || row >= reagentService.getAllReagents().size()) {
            JOptionPane.showMessageDialog(this, "테이블에서 시약 행을 먼저 선택해 주세요.", "선택 안내", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return reagentService.getAllReagents().get(row);
    }

    private void onChangeSelectedMaxCapacity() {
        ReagentItem selected = getSelectedReagent();
        if (selected == null) return;

        String input = JOptionPane.showInputDialog(this,
                "'" + selected.getName() + "' 시약의 변경할 총 Test 수(Max)를 입력하세요:",
                selected.getMaxTests());
        if (input != null && !input.isBlank()) {
            try {
                int newMax = Integer.parseInt(input.trim());
                if (newMax <= 0) {
                    JOptionPane.showMessageDialog(this, "1 이상의 숫자를 입력해야 합니다.", "오류", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                reagentService.updateMaxCapacity(selected.getItem(), selected.getType(), newMax);
                JOptionPane.showMessageDialog(this,
                        selected.getName() + " 최대 용량이 " + newMax + "회로 변경되었습니다.\n" +
                                "(리셋 시 변경된 " + newMax + "회로 복원됩니다)",
                        "용량 변경 완료", JOptionPane.INFORMATION_MESSAGE);
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "유효한 정수를 입력해 주세요.", "입력 오류", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void onResetSelectedReagent() {
        ReagentItem selected = getSelectedReagent();
        if (selected == null) return;

        reagentService.resetReagent(selected.getItem(), selected.getType());
        JOptionPane.showMessageDialog(this,
                selected.getName() + " 시약이 설정된 최대 용량(" + selected.getMaxTests() + "회)으로 리셋되었습니다.",
                "리셋 완료", JOptionPane.INFORMATION_MESSAGE);
    }

    private void onResetAllReagents() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "전체 34개 시약을 각 시약별 설정된 최대 용량(Max)으로 일괄 리셋하시겠습니까?",
                "전체 리셋 확인", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            reagentService.resetAllReagents();
            JOptionPane.showMessageDialog(this, "전체 34개 시약이 각자의 Max 용량으로 정상 리셋되었습니다.", "리셋 완료", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
