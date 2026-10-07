package com.hitachi3100.ui;

import com.hitachi3100.model.CalibrationParam;
import com.hitachi3100.model.QCRecord;
import com.hitachi3100.model.TestItem;
import com.hitachi3100.service.QCService;
import com.hitachi3100.ui.components.LJChartPanel;
import com.hitachi3100.ui.theme.UIStyle;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * [탭 2] 정도관리 (QC) & Calibration 패널
 * - 수동 측정 시작 버튼 제거: 장비가 Control 샘플(Function Character 'f')로 보낸 결과를 자동 수신
 * - 기준값 관리, 바코드 스캔, Levey-Jennings(L-J) 관리도 그래픽 렌더링, qc_results.csv 자동 저장
 */
public class Tab2QCCalibrationPanel extends JPanel {

    private final QCService qcService;

    // 컴포넌트
    private JComboBox<TestItem> itemComboBox;
    private JTextField targetField;
    private JTextField minField;
    private JTextField maxField;
    private JTextField kFactorField;
    private JTextField concField;

    private JTextField barcodeInputField;
    private LJChartPanel ljChartPanel;

    private DefaultTableModel qcTableModel;
    private JTable qcTable;

    public Tab2QCCalibrationPanel(QCService qcService) {
        this.qcService = qcService;

        setLayout(new BorderLayout(10, 10));
        setBackground(UIStyle.COLOR_BG_APP);
        setBorder(new EmptyBorder(10, 10, 10, 10));

        initUI();
        initListeners();
        loadParamForSelectedItem();
        refreshAllData();
    }

    private void initUI() {
        // 상단: 안내 배너 (수동 시작 버튼 제거 명시 & 자동 수신 안내)
        JPanel infoBanner = new JPanel(new BorderLayout());
        infoBanner.setBackground(new Color(238, 242, 255));
        infoBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(199, 210, 254)),
                new EmptyBorder(8, 14, 8, 14)
        ));
        JLabel infoLabel = new JLabel("ℹ️ 정도관리(QC)는 장비에서 Control 샘플을 측정하면 결과(Control No.1~5 → QC1~QC5)가 자동으로 누적 기록되어 L-J 차트에 반영됩니다. (Host 는 Control/Calibration 오더를 낼 수 없습니다)");
        infoLabel.setFont(UIStyle.FONT_BOLD);
        infoLabel.setForeground(new Color(49, 46, 129));
        infoBanner.add(infoLabel, BorderLayout.CENTER);
        add(infoBanner, BorderLayout.NORTH);

        // 중앙: 좌측 (설정 및 바코드 스캔) vs 우측 (L-J 차트 + 데이터 테이블)
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createLeftConfigPanel(), createRightVisualPanel());
        splitPane.setDividerLocation(380);
        splitPane.setResizeWeight(0.3);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);
    }

    private JPanel createLeftConfigPanel() {
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);

        // 1. 항목 선택 및 기준값 / Calibration 설정 카드
        JPanel paramCard = new JPanel(new BorderLayout(8, 8));
        paramCard.setBackground(Color.WHITE);
        paramCard.setBorder(UIStyle.createCardBorder("QC 기준값 & Calibration 파라미터"));

        JPanel form = new JPanel(new GridLayout(6, 2, 8, 8));
        form.setOpaque(false);
        form.setBorder(new EmptyBorder(6, 6, 6, 6));

        form.add(new JLabel("검사 항목:"));
        itemComboBox = new JComboBox<>(TestItem.values());
        itemComboBox.setFont(UIStyle.FONT_BOLD);
        itemComboBox.addActionListener(e -> {
            loadParamForSelectedItem();
            refreshChart();
        });
        form.add(itemComboBox);

        form.add(new JLabel("Target (목표치):"));
        targetField = new JTextField();
        targetField.setFont(UIStyle.FONT_BOLD);
        form.add(targetField);

        form.add(new JLabel("Min (하한치):"));
        minField = new JTextField();
        form.add(minField);

        form.add(new JLabel("Max (상한치):"));
        maxField = new JTextField();
        form.add(maxField);

        form.add(new JLabel("Cal K-Factor:"));
        kFactorField = new JTextField();
        form.add(kFactorField);

        form.add(new JLabel("Cal Conc (표준 농도):"));
        concField = new JTextField();
        form.add(concField);

        paramCard.add(form, BorderLayout.CENTER);

        JButton saveParamBtn = UIStyle.createPrimaryButton("💾 기준값 / CAL 설정 저장");
        saveParamBtn.addActionListener(e -> onSaveParams());
        paramCard.add(saveParamBtn, BorderLayout.SOUTH);

        left.add(paramCard);
        left.add(Box.createVerticalStrut(12));

        // 2. 바코드 스캔 및 가상 바코드 테스트 카드
        JPanel barcodeCard = new JPanel(new BorderLayout(8, 8));
        barcodeCard.setBackground(Color.WHITE);
        barcodeCard.setBorder(UIStyle.createCardBorder("시약 / 컨트롤 바코드 스캔"));

        JPanel barcodeForm = new JPanel(new BorderLayout(6, 6));
        barcodeForm.setOpaque(false);
        barcodeForm.setBorder(new EmptyBorder(6, 6, 6, 6));

        barcodeForm.add(new JLabel("바코드 입력 (스캐너 입력 후 Enter):"), BorderLayout.NORTH);

        barcodeInputField = new JTextField();
        barcodeInputField.setFont(UIStyle.FONT_MONO);
        barcodeInputField.setToolTipText("바코드 리더기로 스캔하거나 텍스트를 입력 후 Enter를 누르세요.");
        barcodeInputField.addActionListener(e -> onParseBarcode());
        barcodeForm.add(barcodeInputField, BorderLayout.CENTER);

        JPanel barcodeBtns = new JPanel(new GridLayout(2, 1, 4, 4));
        barcodeBtns.setOpaque(false);

        JButton parseBtn = UIStyle.createPrimaryButton("🔍 바코드 수동 파싱 및 적용");
        parseBtn.addActionListener(e -> onParseBarcode());
        barcodeBtns.add(parseBtn);

        JButton testBarcodeBtn = UIStyle.createSecondaryButton("🧪 가상 바코드 자동 생성 & 테스트");
        testBarcodeBtn.setToolTipText("현재 선택 항목의 기준값을 바탕으로 가상 바코드 스트링을 생성하여 테스트합니다.");
        testBarcodeBtn.addActionListener(e -> onVirtualBarcodeTest());
        barcodeBtns.add(testBarcodeBtn);

        barcodeForm.add(barcodeBtns, BorderLayout.SOUTH);
        barcodeCard.add(barcodeForm, BorderLayout.CENTER);

        left.add(barcodeCard);
        left.add(Box.createVerticalGlue());

        return left;
    }

    private JPanel createRightVisualPanel() {
        JPanel right = new JPanel(new BorderLayout(8, 8));
        right.setOpaque(false);

        // 상단: Levey-Jennings Control Chart
        ljChartPanel = new LJChartPanel();
        ljChartPanel.setPreferredSize(new Dimension(500, 320));

        JPanel chartCard = new JPanel(new BorderLayout());
        chartCard.setBackground(Color.WHITE);
        chartCard.setBorder(UIStyle.createCardBorder("Levey-Jennings (L-J) Control Chart"));
        chartCard.add(ljChartPanel, BorderLayout.CENTER);

        // 하단: QC 데이터 테이블
        JPanel tableCard = new JPanel(new BorderLayout(6, 6));
        tableCard.setBackground(Color.WHITE);
        tableCard.setBorder(UIStyle.createCardBorder("QC 수신 이력 데이터 (qc_results.csv 연동)"));

        String[] cols = {"수신일시", "QC 구분", "검사항목", "측정 수치", "Target", "Min", "Max", "판정 상태"};
        qcTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        qcTable = new JTable(qcTableModel);
        qcTable.setRowHeight(24);
        qcTable.setFont(UIStyle.FONT_REGULAR);
        qcTable.getTableHeader().setFont(UIStyle.FONT_BOLD);

        // 상태 열 색상 렌더러
        qcTable.getColumnModel().getColumn(7).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object val, boolean isSel, boolean hasFoc, int row, int col) {
                Component c = super.getTableCellRendererComponent(tbl, val, isSel, hasFoc, row, col);
                setHorizontalAlignment(SwingConstants.CENTER);
                String st = val != null ? val.toString() : "";
                if ("Out-of-Control".equalsIgnoreCase(st)) {
                    c.setForeground(UIStyle.COLOR_HIGH_TEXT);
                    setFont(UIStyle.FONT_BOLD);
                } else if (st.contains("Warning")) {
                    c.setForeground(UIStyle.COLOR_WARN_TEXT);
                    setFont(UIStyle.FONT_BOLD);
                } else {
                    c.setForeground(UIStyle.COLOR_NORMAL_TEXT);
                    setFont(UIStyle.FONT_REGULAR);
                }
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(qcTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(UIStyle.COLOR_BORDER));
        scrollPane.setPreferredSize(new Dimension(500, 220));
        tableCard.add(scrollPane, BorderLayout.CENTER);

        JSplitPane vSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartCard, tableCard);
        vSplit.setDividerLocation(340);
        vSplit.setResizeWeight(0.6);
        vSplit.setBorder(null);

        right.add(vSplit, BorderLayout.CENTER);
        return right;
    }

    private void initListeners() {
        qcService.addChangeListener(this::refreshAllData);
    }

    private void loadParamForSelectedItem() {
        TestItem selected = (TestItem) itemComboBox.getSelectedItem();
        if (selected == null) return;

        CalibrationParam p = qcService.getParam(selected);
        if (p != null) {
            targetField.setText(selected.formatValue(p.getTarget()));
            minField.setText(selected.formatValue(p.getMin()));
            maxField.setText(selected.formatValue(p.getMax()));
            kFactorField.setText(String.format(java.util.Locale.ROOT, "%.3f", p.getkFactor()));
            concField.setText(selected.formatValue(p.getStdConc()));
        }
    }

    private void onSaveParams() {
        TestItem selected = (TestItem) itemComboBox.getSelectedItem();
        if (selected == null) return;

        try {
            double tgt = Double.parseDouble(targetField.getText().trim());
            double mn = Double.parseDouble(minField.getText().trim());
            double mx = Double.parseDouble(maxField.getText().trim());
            double k = Double.parseDouble(kFactorField.getText().trim());
            double conc = Double.parseDouble(concField.getText().trim());

            if (mn >= mx) {
                JOptionPane.showMessageDialog(this, "Min 수치는 Max 수치보다 작아야 합니다.", "입력 오류", JOptionPane.WARNING_MESSAGE);
                return;
            }

            qcService.updateParam(selected, tgt, mn, mx, k, conc);
            refreshChart();
            JOptionPane.showMessageDialog(this, selected.getCode() + " 기준값 및 Calibration 설정이 저장되었습니다.", "저장 완료", JOptionPane.INFORMATION_MESSAGE);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "모든 필드에 유효한 숫자를 입력해 주세요.", "입력 형식 오류", JOptionPane.ERROR_MESSAGE);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "입력 오류", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void onParseBarcode() {
        String text = barcodeInputField.getText().trim();
        if (text.isEmpty()) {
            JOptionPane.showMessageDialog(this, "파싱할 바코드 텍스트를 입력해 주세요.", "안내", JOptionPane.WARNING_MESSAGE);
            return;
        }

        QCService.BarcodeParseResult result = new QCService.BarcodeParseResult();
        boolean ok = qcService.parseBarcode(text, result);
        if (ok && result.item != null) {
            itemComboBox.setSelectedItem(result.item);
            targetField.setText(result.item.formatValue(result.target));
            minField.setText(result.item.formatValue(result.min));
            maxField.setText(result.item.formatValue(result.max));
            kFactorField.setText(String.format(java.util.Locale.ROOT, "%.3f", result.kFactor));
            concField.setText(result.item.formatValue(result.conc));

            // 기준값 업데이트 반영
            try {
                qcService.updateParam(result.item, result.target, result.min, result.max, result.kFactor, result.conc);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, "바코드 값이 올바르지 않습니다: " + ex.getMessage(), "바코드 오류", JOptionPane.WARNING_MESSAGE);
                return;
            }
            refreshChart();

            JOptionPane.showMessageDialog(this,
                    "바코드 인식 성공!\n" +
                            "항목: " + result.item.getCode() + "\n" +
                            "Lot: " + (result.lotNumber.isEmpty() ? "N/A" : result.lotNumber) + "\n" +
                            "Target: " + result.target + ", Min: " + result.min + ", Max: " + result.max,
                    "바코드 파싱 완료", JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this, "바코드 형식을 인식할 수 없습니다.\n지원 형식: ITEM=AST;LOT=xxx;TGT=30;MIN=20;MAX=40;K=1.0", "파싱 실패", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onVirtualBarcodeTest() {
        TestItem selected = (TestItem) itemComboBox.getSelectedItem();
        if (selected == null) return;

        String virtualBarcode = qcService.generateVirtualBarcode(selected);
        barcodeInputField.setText(virtualBarcode);
        onParseBarcode();
    }

    private void refreshChart() {
        TestItem selected = (TestItem) itemComboBox.getSelectedItem();
        if (selected == null) return;

        CalibrationParam p = qcService.getParam(selected);
        double tgt = p != null ? p.getTarget() : selected.getDefaultTarget();
        double mn = p != null ? p.getMin() : selected.getRefLow();
        double mx = p != null ? p.getMax() : selected.getRefHigh();

        List<QCRecord> records = qcService.getRecordsForItem(selected);
        ljChartPanel.updateData(selected, tgt, mn, mx, records);
    }

    private void refreshAllData() {
        SwingUtilities.invokeLater(() -> {
            refreshChart();

            // 테이블 갱신
            qcTableModel.setRowCount(0);
            List<QCRecord> all = qcService.getAllRecords();
            // 최신순 표시를 위해 역순 순회
            for (int i = all.size() - 1; i >= 0; i--) {
                QCRecord r = all.get(i);
                qcTableModel.addRow(new Object[]{
                        r.getFormattedTimestamp(),
                        r.getQcLevel(),
                        r.getItem().getCode(),
                        r.getItem().formatValue(r.getValue()),
                        r.getItem().formatValue(r.getTarget()),
                        r.getItem().formatValue(r.getMin()),
                        r.getItem().formatValue(r.getMax()),
                        r.getStatus()
                });
            }
        });
    }
}
