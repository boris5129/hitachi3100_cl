package com.hitachi3100.ui;

import com.hitachi3100.model.TestItem;
import com.hitachi3100.protocol.Hitachi3100Simulator;
import com.hitachi3100.protocol.IHitachiChannel;
import com.hitachi3100.protocol.SerialCommunicationChannel;
import com.hitachi3100.protocol.SerialSettings;
import com.hitachi3100.util.DataPaths;
import com.hitachi3100.service.*;
import com.hitachi3100.ui.components.HitachiTraceDialog;
import com.hitachi3100.ui.theme.UIStyle;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Hitachi 3100 임상화학 제어 시스템 메인 프레임 (4개 메인 탭 구성)
 */
public class MainFrame extends JFrame {

    private final QCService qcService;
    private final ReagentService reagentService;
    private final HistoryService historyService;
    private final PresetService presetService;
    private final OrderService orderService;

    private IHitachiChannel activeChannel;
    private final Hitachi3100Simulator simulator;
    private final SerialCommunicationChannel serialChannel;

    private Tab1OrderResultPanel tab1;
    private Tab2QCCalibrationPanel tab2;
    private Tab3ReagentPanel tab3;
    private Tab4HistoryPanel tab4;

    private HitachiTraceDialog traceDialog;

    private JComboBox<String> channelSelectorCombo;
    private JButton connectButton;
    private JLabel statusBadgeLabel;
    private JLabel reagentWarningLabel;
    private JLabel timeClockLabel;
    private JLabel lastEventLabel;
    private JButton virtualQc1Btn;
    private JButton virtualQc2Btn;

    public MainFrame() {
        super("Hitachi 3100 자동 분석기 제어 및 임상화학 시스템 (Hitachi Automatic Analyzer 3100)");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onClose();
            }
        });
        setSize(1350, 850);
        setMinimumSize(new Dimension(1100, 700));
        setLocationRelativeTo(null);

        // 서비스 초기화
        this.qcService = new QCService();
        this.reagentService = new ReagentService();
        this.historyService = new HistoryService();
        this.presetService = new PresetService();
        this.orderService = new OrderService(qcService, reagentService, historyService);

        // 장비 채널 설정 파일 (선택): 채널 번호 매핑, 시리얼 파라미터
        TestItem.loadChannelMap(DataPaths.file("channel_map.properties").toFile());
        SerialSettings serialSettings = SerialSettings.load(DataPaths.file("serial.properties").toFile());

        // 통신 채널 초기화
        this.simulator = new Hitachi3100Simulator();
        this.serialChannel = new SerialCommunicationChannel("COM1", serialSettings);

        // 기본값: 내장 시뮬레이터 채널 활성화
        this.activeChannel = simulator;
        this.orderService.setCommunicationChannel(activeChannel);

        this.traceDialog = new HitachiTraceDialog(this);
        // 리스너는 채널당 한 번만 등록한다 (채널 전환 시 누적/중복 방지)
        for (IHitachiChannel ch : new IHitachiChannel[]{simulator, serialChannel}) {
            ch.addListener(traceDialog);
            ch.addListener(createStatusListener(ch));
        }
        orderService.addEventListener(this::onProtocolEvent);

        initUI();
        startClockTimer();

        // 자동 시뮬레이터 연결 (즉시 사용 가능 상태)
        connectActiveChannelAsync(false);
    }

    private void initUI() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(UIStyle.COLOR_BG_APP);

        // 1. 상단 글로벌 헤더
        root.add(createHeaderBar(), BorderLayout.NORTH);

        // 2. 메인 4개 탭 컨테이너
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(UIStyle.FONT_HEADER);

        tab1 = new Tab1OrderResultPanel(orderService, presetService, qcService, historyService);
        tab2 = new Tab2QCCalibrationPanel(qcService);
        tab3 = new Tab3ReagentPanel(reagentService);
        tab4 = new Tab4HistoryPanel(historyService);

        tabbedPane.addTab("📋 [탭 1] 검사 오더 & 결과 수신", tab1);
        tabbedPane.addTab("📊 [탭 2] 정도관리 (QC) & Calibration", tab2);
        tabbedPane.addTab("🧪 [탭 3] 시약 잔량 관리 (34개 시약)", tab3);
        tabbedPane.addTab("📁 [탭 4] 환자 검사 이력 (History)", tab4);

        root.add(tabbedPane, BorderLayout.CENTER);

        // 3. 하단 글로벌 상태 표시줄
        root.add(createBottomStatusBar(), BorderLayout.SOUTH);

        setContentPane(root);
        updateVirtualButtons();
    }

    private JPanel createHeaderBar() {
        JPanel header = new JPanel(new BorderLayout(15, 0));
        header.setBackground(UIStyle.COLOR_HEADER_BG);
        header.setBorder(new EmptyBorder(10, 16, 10, 16));

        // 좌측 브랜드 타이틀
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);

        JLabel logoLabel = new JLabel("🔬 HITACHI 3100");
        logoLabel.setFont(new Font("Segoe UI", Font.BOLD, 20));
        logoLabel.setForeground(Color.WHITE);
        left.add(logoLabel);

        JLabel subTitle = new JLabel("|  Clinical Chemistry System (임상화학 분석 시스템)");
        subTitle.setFont(UIStyle.FONT_BOLD);
        subTitle.setForeground(new Color(148, 163, 184));
        left.add(subTitle);

        header.add(left, BorderLayout.WEST);

        // 우측 통신 모드 선택 및 액션
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        JLabel chLabel = new JLabel("장비 통신 모드:");
        chLabel.setFont(UIStyle.FONT_BOLD);
        chLabel.setForeground(Color.WHITE);
        right.add(chLabel);

        String[] channels = {
                "내장 시뮬레이터 (에뮬레이션)",
                "RS-232C COM1 (9600bps)",
                "RS-232C COM2 (9600bps)",
                "RS-232C COM3 (9600bps)",
                "RS-232C COM4 (9600bps)"
        };
        channelSelectorCombo = new JComboBox<>(channels);
        channelSelectorCombo.setFont(UIStyle.FONT_REGULAR);
        channelSelectorCombo.addActionListener(e -> onSwitchChannel());
        right.add(channelSelectorCombo);

        connectButton = new JButton("연결 해제");
        connectButton.setFont(UIStyle.FONT_BOLD);
        connectButton.setFocusPainted(false);
        connectButton.addActionListener(e -> onToggleConnection());
        right.add(connectButton);

        virtualQc1Btn = new JButton("🧪 가상 QC1");
        virtualQc1Btn.setToolTipText("시뮬레이터 전용: 장비에서 Control No.1 샘플 측정이 끝난 상황을 만듭니다 (Host 는 Control 오더를 낼 수 없음)");
        virtualQc1Btn.addActionListener(e -> injectVirtualControl(1));
        right.add(virtualQc1Btn);
        virtualQc2Btn = new JButton("🧪 가상 QC2");
        virtualQc2Btn.setToolTipText("시뮬레이터 전용: Control No.2 샘플");
        virtualQc2Btn.addActionListener(e -> injectVirtualControl(2));
        right.add(virtualQc2Btn);

        JButton traceBtn = new JButton("📡 통신 트레이스");
        traceBtn.setFont(UIStyle.FONT_BOLD);
        traceBtn.setFocusPainted(false);
        traceBtn.addActionListener(e -> traceDialog.setVisible(true));
        right.add(traceBtn);

        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JPanel createBottomStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBackground(Color.WHITE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIStyle.COLOR_BORDER, 1),
                new EmptyBorder(6, 14, 6, 14)
        ));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        left.setOpaque(false);

        statusBadgeLabel = new JLabel("● 장비 연결됨: Hitachi 3100 Simulator");
        statusBadgeLabel.setFont(UIStyle.FONT_BOLD);
        statusBadgeLabel.setForeground(UIStyle.COLOR_NORMAL_TEXT);
        left.add(statusBadgeLabel);

        reagentWarningLabel = new JLabel("");
        reagentWarningLabel.setFont(UIStyle.FONT_BOLD);
        left.add(reagentWarningLabel);

        lastEventLabel = new JLabel("");
        lastEventLabel.setFont(UIStyle.FONT_REGULAR);
        left.add(lastEventLabel);

        updateReagentStatusBanner();
        reagentService.addChangeListener(this::updateReagentStatusBanner);

        bar.add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 0));
        right.setOpaque(false);

        timeClockLabel = new JLabel("");
        timeClockLabel.setFont(UIStyle.FONT_REGULAR);
        timeClockLabel.setForeground(Color.DARK_GRAY);
        right.add(timeClockLabel);

        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void updateReagentStatusBanner() {
        SwingUtilities.invokeLater(() -> {
            int depleted = reagentService.getDepletedCount();
            int low = reagentService.getLowCount();
            if (depleted > 0) {
                reagentWarningLabel.setText("⚠️ [시약 경고] " + depleted + "개 시약 소진!");
                reagentWarningLabel.setForeground(UIStyle.COLOR_DEPLETED_TEXT);
            } else if (low > 0) {
                reagentWarningLabel.setText("⚠️ [시약 경고] " + low + "개 시약 잔량 부족 (15% 이하)");
                reagentWarningLabel.setForeground(UIStyle.COLOR_WARN_TEXT);
            } else {
                reagentWarningLabel.setText("✓ 모든 34개 시약 상태 정상");
                reagentWarningLabel.setForeground(UIStyle.COLOR_NORMAL_TEXT);
            }
        });
    }

    /** 채널별 상태 리스너. 현재 활성 채널의 상태만 화면에 반영한다. */
    private IHitachiChannel.HitachiChannelListener createStatusListener(IHitachiChannel source) {
        return new IHitachiChannel.HitachiChannelListener() {
            @Override
            public void onFrameReceived(byte[] rawFrame) {}

            @Override
            public void onStatusChanged(boolean connected, String message) {
                if (source != activeChannel) return;
                SwingUtilities.invokeLater(() -> {
                    statusBadgeLabel.setText("● " + message);
                    statusBadgeLabel.setForeground(connected ? UIStyle.COLOR_NORMAL_TEXT : UIStyle.COLOR_HIGH_TEXT);
                    connectButton.setText(connected ? "연결 해제" : "연결하기");
                    updateVirtualButtons();
                    if (tab1 != null) tab1.updateConnectionStatus(connected, message);
                });
            }

            @Override
            public void onRawLog(String direction, byte[] rawData, String summary) {}
        };
    }

    /** 통신 이벤트(REP 송신, 응답 없음, 시약 소진 상태 측정 등) 표시 */
    private void onProtocolEvent(String message) {
        traceDialog.logSystem(message);
        SwingUtilities.invokeLater(() -> {
            lastEventLabel.setText("| " + message);
            lastEventLabel.setForeground(message.startsWith("⚠") ? UIStyle.COLOR_HIGH_TEXT : Color.DARK_GRAY);
        });
    }

    private void updateVirtualButtons() {
        boolean on = activeChannel == simulator && simulator.isConnected();
        if (virtualQc1Btn != null) virtualQc1Btn.setEnabled(on);
        if (virtualQc2Btn != null) virtualQc2Btn.setEnabled(on);
    }

    private void injectVirtualControl(int controlNo) {
        if (activeChannel == simulator && simulator.isConnected()) {
            simulator.injectControlSample(controlNo, java.util.Arrays.asList(TestItem.values()));
            onProtocolEvent("가상 Control No." + controlNo + " 측정 완료 - 다음 통신 사이클에 결과가 수신됩니다");
        }
    }

    /** 포트 열기는 시간이 걸릴 수 있으므로 UI 스레드가 아닌 곳에서 수행 */
    private void connectActiveChannelAsync(boolean showErrors) {
        final IHitachiChannel ch = activeChannel;
        Thread t = new Thread(() -> {
            try {
                ch.connect();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    statusBadgeLabel.setText("● 연결 실패: " + e.getMessage());
                    statusBadgeLabel.setForeground(UIStyle.COLOR_HIGH_TEXT);
                    connectButton.setText("연결하기");
                    if (showErrors) {
                        JOptionPane.showMessageDialog(this, ch.getChannelName() + " 연결 실패:\n" + e.getMessage(),
                                "통신 연결 실패", JOptionPane.WARNING_MESSAGE);
                    }
                });
            }
        }, "ConnectChannel");
        t.setDaemon(true);
        t.start();
    }

    private void onSwitchChannel() {
        int idx = channelSelectorCombo.getSelectedIndex();
        IHitachiChannel old = activeChannel;

        IHitachiChannel next;
        if (idx == 0) {
            next = simulator;
        } else {
            serialChannel.setPortName("COM" + idx);
            next = serialChannel;
        }
        if (next == old && old.isConnected()) {
            if (next == serialChannel) {      // 같은 시리얼 채널에서 포트만 바뀐 경우 재연결
                old.disconnect();
            } else {
                return;
            }
        }
        activeChannel = next;                 // 먼저 교체하여 이전 채널의 상태 알림이 화면을 덮어쓰지 않게 한다
        if (old != next && old.isConnected()) old.disconnect();
        orderService.setCommunicationChannel(next);
        updateVirtualButtons();
        connectActiveChannelAsync(true);
    }

    private void onToggleConnection() {
        if (activeChannel.isConnected()) {
            activeChannel.disconnect();
        } else {
            connectActiveChannelAsync(true);
        }
    }

    private void onClose() {
        try {
            if (!orderService.getPendingOrders().isEmpty()) {
                int r = JOptionPane.showConfirmDialog(this,
                        "아직 완료되지 않은 오더 " + orderService.getPendingOrders().size() + "건이 있습니다.\n종료하시겠습니까?",
                        "종료 확인", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (r != JOptionPane.YES_OPTION) return;
            }
            orderService.shutdown();
            simulator.disconnect();
            serialChannel.disconnect();
        } finally {
            dispose();
            System.exit(0);
        }
    }

    private void startClockTimer() {
        Timer timer = new Timer(1000, e -> {
            String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            timeClockLabel.setText("시스템 시각: " + timeStr);
        });
        timer.start();
    }
}
