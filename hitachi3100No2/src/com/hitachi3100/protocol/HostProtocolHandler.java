package com.hitachi3100.protocol;

import com.hitachi3100.model.TestItem;
import com.hitachi3100.model.TestResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.hitachi3100.protocol.Hitachi3100Constants.*;

/**
 * 호스트(HOST) 측 통신 절차 구현 (매뉴얼 15.1.4 Data Transmission Control Procedure).
 *
 * 동작 규칙
 *  - AU 가 먼저 ANY 를 보내고 호스트는 응답한다. 호스트가 임의로 먼저 송신하지 않는다.
 *  - 응답은 AU 의 텍스트 수신 후 최소 100ms 대기 후 보낸다 (15.1.8 (3)).
 *  - ANY 에 대한 응답: Batch TS 지시(SPE)가 있으면 SPE, 없으면 MOR.
 *  - AU 의 TS 문의(SPE) 에 대한 응답: 해당 오더가 있으면 SPE(TS 지시), 없으면 MOR.
 *  - 결과 데이터(FR1/FR2/END) 는 BCC/형식 검사 후 정상이면 MOR, 이상이면 REP (15.1.6).
 *  - AU 가 REP 를 보내면 마지막 송신 텍스트를 재전송한다.
 *  - 모든 처리는 단일 스레드에서 직렬로 수행된다.
 */
public class HostProtocolHandler implements IHitachiChannel.HitachiChannelListener {

    /** 호스트가 AU 로 보낼 TS(Test Selection) 정보 */
    public static final class TestSelection {
        public final String fu;
        public final SampleInfo info;
        public final List<TestItem> items;
        public final Object token;

        public TestSelection(String fu, SampleInfo info, List<TestItem> items, Object token) {
            this.fu = fu;
            this.info = info;
            this.items = items;
            this.token = token;
        }
    }

    /** 업무 계층(OrderService)이 구현하는 콜백 */
    public interface Delegate {
        /** AU 의 실시간 TS 문의에 대응할 오더가 있으면 반환, 없으면 null */
        TestSelection findTestSelection(SampleInfo inquiry, String fu);

        /** Batch 로 전송할 다음 오더(없으면 null) */
        TestSelection nextBatchSelection();

        /** TS 지시(SPE)를 실제로 전송한 직후 호출 */
        void onTestSelectionSent(Object token);

        /** 한 검체의 결과(FR1..END)가 모두 수신되었을 때 호출 */
        void onSampleResult(Hitachi3100Frame.SampleKind kind, String fu, SampleInfo sample, List<TestResult> results);

        /** Calibration / 흡광도 등 앱이 처리하지 않는 데이터 수신 알림 */
        void onOtherData(String description);

        /** 통신 이벤트(경고/오류 포함) */
        void onEvent(String message, boolean error);
    }

    private final IHitachiChannel channel;
    private final Delegate delegate;
    private final ScheduledExecutorService ex;

    private volatile long hostDelayMs = DEFAULT_HOST_RESPONSE_DELAY_MS;
    private volatile long linkTimeoutMs = DEFAULT_LINK_TIMEOUT_MS;

    // 아래 필드는 모두 ex 스레드에서만 접근
    private byte[] lastSent;
    private int resendCount;
    private int consecutiveRepSent;
    private long lastRxAt = System.currentTimeMillis();
    private boolean linkAlarmRaised;
    private byte[] lastTextRaw;
    private long lastTextAt;
    private final Map<String, List<TestResult>> partial = new HashMap<>();

    public HostProtocolHandler(IHitachiChannel channel, Delegate delegate) {
        this.channel = channel;
        this.delegate = delegate;
        this.ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HitachiHostProtocol");
            t.setDaemon(true);
            return t;
        });
        this.ex.scheduleWithFixedDelay(this::watchdog, 1, 1, TimeUnit.SECONDS);
    }

    public void setHostResponseDelayMs(long ms) {
        this.hostDelayMs = Math.max(MIN_HOST_RESPONSE_DELAY_MS, ms);
    }

    public void setLinkTimeoutMs(long ms) {
        this.linkTimeoutMs = Math.max(1000, ms);
    }

    public void close() {
        ex.shutdownNow();
    }

    // ------------------------------------------------------------ 채널 리스너

    @Override
    public void onFrameReceived(byte[] rawFrame) {
        try {
            ex.execute(() -> safely(() -> handle(rawFrame)));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // close() 이후 도착한 프레임
        }
    }

    @Override
    public void onStatusChanged(boolean connected, String message) {
        try {
            ex.execute(() -> {
                lastRxAt = System.currentTimeMillis();
                linkAlarmRaised = false;
                if (!connected) {
                    partial.clear();
                    lastSent = null;
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    @Override
    public void onRawLog(String direction, byte[] rawData, String summary) {
        // 트레이스 창이 별도로 처리
    }

    // ------------------------------------------------------------ 수신 처리

    private void handle(byte[] raw) {
        lastRxAt = System.currentTimeMillis();
        if (linkAlarmRaised) {
            linkAlarmRaised = false;
            delegate.onEvent("AU 통신이 복구되었습니다.", false);
        }

        Hitachi3100Frame.ParsedFrame pf;
        try {
            pf = Hitachi3100Frame.parse(raw);   // BCC / 구조 / 문자 범위 검사 포함
        } catch (FrameFormatException e) {
            sendRep("수신 텍스트 오류: " + e.getMessage());
            return;
        }
        consecutiveRepSent = 0;

        switch (pf.frameChar) {
            case FRAME_ANY:
                respondLater(this::answerAny);
                break;
            case FRAME_SPE:
                if (pf.isTestSelectionDirective()) {
                    sendRep("AU 가 보낸 SPE 가 TS 지시 형식입니다");
                } else {
                    respondLater(() -> answerInquiry(pf));
                }
                break;
            case FRAME_FR1:
            case FRAME_FR2:
            case FRAME_END:
                handleResultText(raw, pf);
                respondLater(() -> send(Hitachi3100Frame.createControlFrame(FRAME_MOR)));
                break;
            case FRAME_REP:
                resendLast();
                break;
            default:
                // AU 가 MOR/RES 를 보내는 일은 없음 (Table 15.1.5-2)
                sendRep("AU 에서 올 수 없는 프레임 문자 '" + pf.frameChar + "'");
        }
    }

    private void answerAny() {
        HostProtocolHandler.TestSelection ts = delegate.nextBatchSelection();
        if (ts != null) {
            send(Hitachi3100Frame.createTestSelectionInstruction(ts.fu, ts.info, ts.items));
            delegate.onTestSelectionSent(ts.token);
        } else {
            send(Hitachi3100Frame.createControlFrame(FRAME_MOR));
        }
    }

    private void answerInquiry(Hitachi3100Frame.ParsedFrame inquiry) {
        if (inquiry.sample.getId().isEmpty() && Hitachi3100Frame.isIdModeFu(inquiry.functionCode)) {
            // ID 가 공백인 문의(ID read error)는 TS 를 줄 수 없다 (15.1.5 (1) 3) (c))
            delegate.onEvent("ID 가 공백인 TS 문의(Position " + inquiry.sample.getPositionNumber() + ") - MOR 로 응답", true);
            send(Hitachi3100Frame.createControlFrame(FRAME_MOR));
            return;
        }
        TestSelection ts = delegate.findTestSelection(inquiry.sample, inquiry.functionCode);
        if (ts != null) {
            send(Hitachi3100Frame.createTestSelectionInstruction(ts.fu, ts.info, ts.items));
            delegate.onTestSelectionSent(ts.token);
        } else {
            delegate.onEvent("TS 문의에 해당하는 오더 없음: " + inquiry.sample, false);
            send(Hitachi3100Frame.createControlFrame(FRAME_MOR));
        }
    }

    private void handleResultText(byte[] raw, Hitachi3100Frame.ParsedFrame pf) {
        long now = System.currentTimeMillis();
        boolean duplicate = lastTextRaw != null && Arrays.equals(lastTextRaw, raw) && now - lastTextAt < 60_000;
        lastTextRaw = raw;
        lastTextAt = now;
        if (duplicate) {
            delegate.onEvent("동일한 결과 텍스트가 재수신되어 무시했습니다 (AU 재전송)", false);
            return;
        }

        for (String w : pf.warnings) delegate.onEvent("프레임 경고: " + w, false);
        if (!pf.ignoredChannels.isEmpty()) {
            delegate.onEvent("앱에 정의되지 않은 채널 무시: " + pf.ignoredChannels, false);
        }

        if (pf.kind == Hitachi3100Frame.SampleKind.CALIBRATION) {
            delegate.onOtherData("Calibration 데이터 수신 [" + pf.functionCode.trim() + "] 채널 " + pf.calibrationChannel);
            return;
        }
        if (pf.kind == Hitachi3100Frame.SampleKind.ABSORBANCE) {
            delegate.onOtherData("흡광도 데이터 수신 [" + pf.functionCode.trim() + "] " + pf.sample);
            return;
        }

        String key = pf.functionCode + pf.sample.encode();
        if (pf.frameChar == FRAME_FR1) partial.remove(key);
        List<TestResult> acc = partial.computeIfAbsent(key, k -> new ArrayList<>());
        acc.addAll(pf.results);

        if (pf.frameChar == FRAME_END) {
            partial.remove(key);
            delegate.onSampleResult(pf.kind, pf.functionCode, pf.sample, acc);
        } else if (partial.size() > 20) {
            partial.clear();   // 종료 텍스트가 오지 않은 찌꺼기 정리
        }
    }

    /** 스케줄러 스레드에서 예외가 조용히 사라지지 않도록 이벤트로 알린다 */
    private void safely(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            delegate.onEvent("통신 처리 중 내부 오류: " + t, true);
        }
    }

    // ------------------------------------------------------------ 송신

    private void respondLater(Runnable r) {
        try {
            ex.schedule(() -> safely(r), hostDelayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void send(byte[] frame) {
        lastSent = frame;
        resendCount = 0;
        if (!channel.sendFrame(frame)) {
            delegate.onEvent("프레임 전송 실패 (포트 연결 확인)", true);
        }
    }

    private void sendRep(String reason) {
        consecutiveRepSent++;
        delegate.onEvent("REP 송신(" + consecutiveRepSent + "회째): " + reason, consecutiveRepSent >= 2);
        if (consecutiveRepSent >= MAX_CONSECUTIVE_REP) {
            delegate.onEvent("REP 가 " + MAX_CONSECUTIVE_REP + "회 연속되어 AU 가 통신을 정지했을 수 있습니다. 케이블/통신 설정(데이터 비트, 패리티, 종료 코드)을 확인하세요.", true);
        }
        byte[] rep = Hitachi3100Frame.createControlFrame(FRAME_REP);
        respondLater(() -> {
            if (!channel.sendFrame(rep)) delegate.onEvent("REP 전송 실패", true);
        });
    }

    private void resendLast() {
        if (lastSent == null) {
            delegate.onEvent("AU 가 REP 를 보냈으나 재전송할 텍스트가 없습니다", true);
            return;
        }
        if (resendCount >= DEFAULT_RETRY_COUNT) {
            delegate.onEvent("AU 의 재전송 요청이 " + DEFAULT_RETRY_COUNT + "회를 초과했습니다", true);
            return;
        }
        resendCount++;
        final byte[] frame = lastSent;
        delegate.onEvent("AU 의 REP 수신 - 마지막 텍스트 재전송(" + resendCount + "회)", false);
        respondLater(() -> {
            if (!channel.sendFrame(frame)) delegate.onEvent("재전송 실패", true);
        });
    }

    // ------------------------------------------------------------ 링크 감시

    private void watchdog() {
        try {
            if (!channel.isConnected()) {
                return;
            }
            long idle = System.currentTimeMillis() - lastRxAt;
            if (idle > linkTimeoutMs && !linkAlarmRaised) {
                linkAlarmRaised = true;
                delegate.onEvent("AU 로부터 " + (idle / 1000) + "초 동안 수신이 없습니다. 장비가 On Line 상태인지, 케이블/통신 설정을 확인하세요.", true);
            }
        } catch (Throwable t) {
            // 감시 스레드는 절대 죽지 않게 한다
        }
    }
}
