package com.hitachi3100.ui.components;

import com.hitachi3100.protocol.IHitachiChannel;
import com.hitachi3100.ui.theme.UIStyle;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Hitachi 3100 통신 트레이스(Communication Trace) 창 (매뉴얼 15.1.7 규격)
 */
public class HitachiTraceDialog extends JDialog implements IHitachiChannel.HitachiChannelListener {

    private final DefaultTableModel traceTableModel;
    private final JTable traceTable;
    private final JTextArea rawDetailArea;

    public HitachiTraceDialog(Window parent) {
        super(parent, "Hitachi 3100 Communication Trace (통신 모니터)", ModalityType.MODELESS);
        setSize(850, 550);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(8, 8));
        getContentPane().setBackground(UIStyle.COLOR_BG_APP);

        String[] cols = {"시각", "방향", "길이", "전문 요약", "ASCII 내용"};
        traceTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(Color.WHITE);
        topBar.setBorder(new EmptyBorder(8, 12, 8, 12));

        JLabel title = new JLabel("RS-232C 통신 프레임 송수신 실시간 로그 (Trace Log - 최대 1200 사이클)");
        title.setFont(UIStyle.FONT_BOLD);
        topBar.add(title, BorderLayout.WEST);

        JButton clearBtn = UIStyle.createSecondaryButton("트레이스 비우기 (Clear)");
        clearBtn.addActionListener(e -> traceTableModel.setRowCount(0));
        topBar.add(clearBtn, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        traceTable = new JTable(traceTableModel);
        traceTable.setFont(UIStyle.FONT_REGULAR);
        traceTable.getTableHeader().setFont(UIStyle.FONT_BOLD);
        traceTable.setRowHeight(22);
        traceTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        traceTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        traceTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        traceTable.getColumnModel().getColumn(3).setPreferredWidth(160);
        traceTable.getColumnModel().getColumn(4).setPreferredWidth(360);

        rawDetailArea = new JTextArea(4, 50);
        rawDetailArea.setFont(UIStyle.FONT_MONO);
        rawDetailArea.setEditable(false);
        rawDetailArea.setBackground(new Color(248, 250, 252));

        traceTable.getSelectionModel().addListSelectionListener(e -> {
            int row = traceTable.getSelectedRow();
            if (row >= 0) {
                String text = (String) traceTableModel.getValueAt(row, 4);
                rawDetailArea.setText("ASCII:\n" + text);
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(traceTable), new JScrollPane(rawDetailArea));
        split.setDividerLocation(340);
        add(split, BorderLayout.CENTER);
    }

    @Override
    public void onFrameReceived(byte[] rawFrame) {
        // Logged via onRawLog
    }

    /** 프로토콜 이벤트(REP 송신, 시간 초과 등)를 트레이스에 함께 표시 */
    public void logSystem(String message) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            traceTableModel.addRow(new Object[]{time, "EVENT", "-", "Protocol", message});
            if (traceTableModel.getRowCount() > 1200) {
                traceTableModel.removeRow(0);
            }
        });
    }

    @Override
    public void onStatusChanged(boolean connected, String message) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            traceTableModel.addRow(new Object[]{time, "SYSTEM", "-", "Status", message});
        });
    }

    @Override
    public void onRawLog(String direction, byte[] rawData, String summary) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            String ascii = new String(rawData, StandardCharsets.US_ASCII)
                    .replace("\u0002", "<STX>")
                    .replace("\u0003", "<ETX>")
                    .replace("\r", "<CR>")
                    .replace("\n", "<LF>");

            traceTableModel.addRow(new Object[]{time, direction, rawData.length + "b", summary, ascii});
            // 최대 1200 건 유지 (매뉴얼 15.1.7 (5) 규격)
            if (traceTableModel.getRowCount() > 1200) {
                traceTableModel.removeRow(0);
            }
        });
    }
}
