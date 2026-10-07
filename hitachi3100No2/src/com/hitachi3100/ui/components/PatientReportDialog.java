package com.hitachi3100.ui.components;

import com.hitachi3100.model.PatientRecord;
import com.hitachi3100.model.TestResult;
import com.hitachi3100.ui.theme.UIStyle;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.print.PrinterJob;

/**
 * 선택 환자 임상화학 검사 결과 보고서 및 인쇄 다이얼로그
 */
public class PatientReportDialog extends JDialog {

    public PatientReportDialog(Window parent, PatientRecord record) {
        super(parent, "검사 결과 보고서 - " + record.getPatientId(), ModalityType.APPLICATION_MODAL);
        setSize(750, 600);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(10, 10));
        getContentPane().setBackground(UIStyle.COLOR_BG_APP);

        JPanel printablePanel = new JPanel(new BorderLayout(15, 15));
        printablePanel.setBackground(Color.WHITE);
        printablePanel.setBorder(BorderFactory.createEmptyBorder(25, 30, 25, 30));

        // 1. 헤더 영역
        JPanel headerPanel = new JPanel(new BorderLayout(5, 5));
        headerPanel.setOpaque(false);

        JLabel titleLabel = new JLabel("HITACHI 3100 임상화학 검사 보고서", SwingConstants.CENTER);
        titleLabel.setFont(new Font("Malgun Gothic", Font.BOLD, 18));
        titleLabel.setForeground(new Color(15, 23, 42));

        JLabel subLabel = new JLabel("Hitachi Automatic Analyzer 3100 Clinical Chemistry Report", SwingConstants.CENTER);
        subLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        subLabel.setForeground(Color.GRAY);

        headerPanel.add(titleLabel, BorderLayout.NORTH);
        headerPanel.add(subLabel, BorderLayout.CENTER);

        // 환자 기본 정보 박스
        JPanel infoBox = new JPanel(new GridLayout(2, 4, 10, 8));
        infoBox.setBackground(new Color(248, 250, 252));
        infoBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIStyle.COLOR_BORDER, 1),
                BorderFactory.createEmptyBorder(10, 15, 10, 15)
        ));

        infoBox.add(new JLabel("<html><b>환자 ID:</b> " + record.getPatientId() + "</html>"));
        infoBox.add(new JLabel("<html><b>Disk Pos:</b> " + record.getPosition() + "</html>"));
        infoBox.add(new JLabel("<html><b>검사일시:</b> " + record.getFormattedDateTime() + "</html>"));
        infoBox.add(new JLabel("<html><b>종합상태:</b> " + record.getStatus() + "</html>"));

        infoBox.add(new JLabel("<html><b>검체 종류:</b> Serum (혈청)</html>"));
        infoBox.add(new JLabel("<html><b>장비 모델:</b> Hitachi 3100</html>"));
        infoBox.add(new JLabel("<html><b>검사 항목수:</b> " + record.getResults().size() + " 항목</html>"));
        infoBox.add(new JLabel("<html><b>판정:</b> " + (record.hasAbnormal() ? "<font color='red'><b>이상 소견</b></font>" : "<font color='green'><b>정상</b></font>") + "</html>"));

        JPanel topContainer = new JPanel(new BorderLayout(10, 10));
        topContainer.setOpaque(false);
        topContainer.add(headerPanel, BorderLayout.NORTH);
        topContainer.add(infoBox, BorderLayout.SOUTH);
        printablePanel.add(topContainer, BorderLayout.NORTH);

        // 2. 검사 결과 테이블
        String[] cols = {"검사항목", "전체 명칭", "측정 결과", "단위", "판정(Flag)", "참고 범위"};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        for (TestResult tr : record.getResults()) {
            model.addRow(new Object[]{
                    tr.getItem().getCode(),
                    tr.getItem().getFullName(),
                    tr.getFormattedValue(),
                    tr.getUnit(),
                    tr.getFlag(),
                    tr.getRefRange()
            });
        }

        JTable table = new JTable(model);
        table.setRowHeight(26);
        table.setFont(UIStyle.FONT_REGULAR);
        table.getTableHeader().setFont(UIStyle.FONT_BOLD);
        table.getTableHeader().setBackground(new Color(241, 245, 249));

        // High/Low 색상 렌더러
        table.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object val, boolean isSel, boolean hasFoc, int row, int col) {
                Component c = super.getTableCellRendererComponent(tbl, val, isSel, hasFoc, row, col);
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

        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object val, boolean isSel, boolean hasFoc, int row, int col) {
                Component c = super.getTableCellRendererComponent(tbl, val, isSel, hasFoc, row, col);
                setHorizontalAlignment(SwingConstants.RIGHT);
                setFont(UIStyle.FONT_BOLD);
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createLineBorder(UIStyle.COLOR_BORDER, 1));
        printablePanel.add(scrollPane, BorderLayout.CENTER);

        // 하단 서명란
        JPanel footerPanel = new JPanel(new BorderLayout());
        footerPanel.setOpaque(false);
        JLabel signLabel = new JLabel("검사자 / 전문의 확인: ____________________  (인)", SwingConstants.RIGHT);
        signLabel.setFont(UIStyle.FONT_SMALL);
        signLabel.setForeground(Color.DARK_GRAY);
        footerPanel.add(signLabel, BorderLayout.EAST);
        printablePanel.add(footerPanel, BorderLayout.SOUTH);

        add(printablePanel, BorderLayout.CENTER);

        // 3. 다이얼로그 버튼 바
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        bottomBar.setBackground(UIStyle.COLOR_BG_APP);

        JButton printBtn = UIStyle.createPrimaryButton("🖨️ 인쇄 (Print)");
        printBtn.addActionListener(e -> {
            try {
                PrinterJob job = PrinterJob.getPrinterJob();
                job.setJobName("Hitachi3100_Report_" + record.getPatientId());
                boolean ok = job.printDialog();
                if (ok) {
                    table.print();
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "인쇄 중 오류: " + ex.getMessage(), "인쇄 실패", JOptionPane.ERROR_MESSAGE);
            }
        });

        JButton closeBtn = UIStyle.createSecondaryButton("닫기");
        closeBtn.addActionListener(e -> dispose());

        bottomBar.add(printBtn);
        bottomBar.add(closeBtn);
        add(bottomBar, BorderLayout.SOUTH);
    }
}
