package com.hitachi3100.protocol;

import com.hitachi3100.model.TestItem;
import com.hitachi3100.model.TestResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.hitachi3100.protocol.Hitachi3100Constants.*;

/**
 * Hitachi 3100 (AU) 측 동작을 매뉴얼 15.1.4 절차대로 흉내 내는 내장 시뮬레이터.
 *
 *  - 연결되면 ANY 를 보내고, Host 의 응답(MOR/SPE/REP)을 받을 때마다 Communication cycle 후에 다음 텍스트를 보낸다.
 *  - 결과 데이터는 Host 가 MOR 을 보낸 경우에만 송신한다 (15.1.4 (3) 4)).
 *  - Host 의 TS 지시(SPE)를 받으면 검체를 등록하고 ANY 로 응답한다. 일정 시간 후 측정이 끝나면 결과를 대기열에 넣는다.
 *  - Control 샘플은 Host 가 TS 를 줄 수 없으므로 {@link #injectControlSample} 로 장비 측에서 발생시킨다.
 *  - 실시간 TS 문의(SPE)는 {@link #requestTestSelection} 으로 발생시킨다.
 * 모든 AU 상태 변경은 단일 스레드에서 직렬 처리된다.
 */
public class Hitachi3100Simulator implements IHitachiChannel {

    private static final class AuSample {
        final String fu;           // 결과 FU (소문자)
        final SampleInfo info;     // 결과에 실을 Sample Information
        final List<TestItem> items;
        AuSample(String fu, SampleInfo info, List<TestItem> items) {
            this.fu = fu;
            this.info = info;
            this.items = items;
        }
    }

    private final List<HitachiChannelListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean connected = false;
    private ScheduledExecutorService executor;
    private final Random random = new Random();

    private volatile long cycleMs = 2000;           // Communication cycle (2/3/5/10초 중 가장 짧은 값)
    private volatile long analysisDelayMs = 1500;   // 측정 소요 시간(가상)
    private volatile int maxChannelsPerText = 20;   // 256 byte 모드
    private volatile boolean dropNextResultToHost = false;

    // AU 상태 (executor 스레드에서만 접근)
    private final Deque<byte[]> resultQueue = new ArrayDeque<>();
    private final Deque<byte[]> inquiryQueue = new ArrayDeque<>();
    private byte[] lastSent;
    private int autoPosition = 0;
    private int controlAnalyzeCount = 0;
    private final List<String> receivedDirectives = new CopyOnWriteArrayList<>();

    public void setCycleMs(long ms) { this.cycleMs = Math.max(10, ms); }
    public void setAnalysisDelayMs(long ms) { this.analysisDelayMs = Math.max(0, ms); }
    public void setMaxChannelsPerText(int n) { this.maxChannelsPerText = Math.max(1, n); }

    /** 테스트용: AU 가 수신한 TS 지시 요약 목록 */
    public List<String> getReceivedDirectives() { return receivedDirectives; }

    @Override
    public synchronized void connect() {
        if (connected) return;
        connected = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Hitachi3100Simulator");
            t.setDaemon(true);
            return t;
        });
        resultQueue.clear();
        inquiryQueue.clear();
        notifyStatus(true, "Hitachi 3100 에뮬레이터 연결됨 (AU 절차 시뮬레이션)");
        schedule(this::sendNextAuText, 200);   // On Line -> 첫 ANY
    }

    @Override
    public synchronized void disconnect() {
        if (!connected) return;
        connected = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        notifyStatus(false, "Hitachi 3100 연결 해제됨");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /** Host -> AU */
    @Override
    public boolean sendFrame(byte[] frame) {
        if (!connected) {
            notifyStatus(false, "연결되지 않았습니다.");
            return false;
        }
        log("SEND [HOST->AU]", frame, Hitachi3100Frame.summarize(frame));
        run(() -> receiveFromHost(frame));
        return true;
    }

    // ------------------------------------------------------------ AU 동작

    private void receiveFromHost(byte[] frame) {
        Hitachi3100Frame.ParsedFrame pf;
        try {
            pf = Hitachi3100Frame.parse(frame);
        } catch (FrameFormatException e) {
            emit(Hitachi3100Frame.createControlFrame(FRAME_REP));   // 비정상 텍스트 -> REP
            return;
        }

        switch (pf.frameChar) {
            case FRAME_MOR:
                scheduleNext();                     // 결과 데이터는 MOR 을 받았을 때만 송신 가능
                break;
            case FRAME_SPE:
                if (pf.isTestSelectionDirective()) {
                    acceptDirective(pf);
                }
                scheduleNextAfterNonMor();
                break;
            case FRAME_REP:
                if (lastSent != null) {
                    final byte[] again = lastSent;
                    schedule(() -> emit(again), 50);
                }
                break;
            case FRAME_RES:
            default:
                scheduleNextAfterNonMor();
        }
    }

    private void acceptDirective(Hitachi3100Frame.ParsedFrame pf) {
        boolean idMode = Hitachi3100Frame.isIdModeFu(pf.functionCode);
        if (idMode && pf.sample.getId().isEmpty()) {
            return;   // ID 가 공백인 TS 지시는 무시 (알람)
        }
        List<TestItem> items = new ArrayList<>();
        for (int i = 0; i < pf.testSelection.length(); i++) {
            if (pf.testSelection.charAt(i) == '1') {
                TestItem.fromChannel(i + 1).ifPresent(items::add);
            }
        }
        receivedDirectives.add(pf.functionCode.trim() + "|" + pf.sample.getId() + "|" + items.size());
        if (items.isEmpty()) return;

        String resultFu = pf.functionCode.toLowerCase();
        // Batch 지시에서는 Position 이 공백이므로 AU 가 실제 위치를 배정한다
        int pos = pf.sample.getPositionNumber() > 0 ? pf.sample.getPositionNumber() : nextAutoPosition();
        int sampleNo = idMode ? 0 : pf.sample.getSampleNumber();
        SampleInfo resultInfo = SampleInfo.forResult(sampleNo, pos, pf.sample.getId());
        final AuSample sample = new AuSample(resultFu, resultInfo, items);
        schedule(() -> finishAnalysis(sample), analysisDelayMs);
    }

    private int nextAutoPosition() {
        autoPosition = (autoPosition % MAX_POSITION) + 1;
        return autoPosition;
    }

    private void finishAnalysis(AuSample s) {
        List<TestResult> results = new ArrayList<>();
        for (TestItem item : s.items) {
            results.add(new TestResult(item, generatePatientValue(item), null, ""));
        }
        enqueueResults(s.fu, s.info, results);
    }

    private void enqueueResults(String fu, SampleInfo info, List<TestResult> results) {
        resultQueue.addAll(Hitachi3100Frame.createResultFrames(fu, info, results, maxChannelsPerText));
    }

    /** Host 응답 이후 다음 AU 텍스트를 Communication cycle 뒤에 송신 */
    private void scheduleNext() {
        schedule(this::sendNextAuText, cycleMs);
    }

    private void scheduleNextAfterNonMor() {
        schedule(() -> {
            // Host 가 MOR 이 아닌 텍스트를 보낸 직후에는 결과 데이터를 보내지 않고 ANY(또는 TS 문의)로 응답
            if (!inquiryQueue.isEmpty()) {
                emit(inquiryQueue.poll());
            } else {
                emit(Hitachi3100Frame.createControlFrame(FRAME_ANY));
            }
        }, cycleMs);
    }

    private void sendNextAuText() {
        if (!connected) return;
        byte[] next;
        if (!inquiryQueue.isEmpty()) {
            next = inquiryQueue.poll();
        } else if (!resultQueue.isEmpty()) {
            next = resultQueue.poll();
        } else {
            next = Hitachi3100Frame.createControlFrame(FRAME_ANY);
        }
        emit(next);
    }

    private void emit(byte[] frame) {
        if (!connected) return;
        lastSent = frame;
        log("RECV [AU->HOST]", frame, Hitachi3100Frame.summarize(frame));
        for (HitachiChannelListener l : listeners) {
            try {
                l.onFrameReceived(frame);
            } catch (RuntimeException e) {
                System.err.println("Simulator listener error: " + e);
            }
        }
    }

    // ------------------------------------------------------------ 외부 발생(주입) API

    /** 장비 측에서 Control 샘플 측정이 끝나 결과가 생긴 상황을 만든다 (Host 가 오더를 낼 수 없는 샘플) */
    public void injectControlSample(int controlNo, List<TestItem> items) {
        run(() -> {
            controlAnalyzeCount = (controlAnalyzeCount % 30) + 1;
            SampleInfo info = SampleInfo.forControl(controlNo, controlAnalyzeCount);
            List<TestResult> results = new ArrayList<>();
            for (TestItem item : items) {
                results.add(new TestResult(item, generateControlValue(item, controlNo), null, ""));
            }
            enqueueResults(FU_RESULT_CONTROL, info, results);
        });
    }

    /** 특정 검체에 대한 TS 문의(SPE)를 AU 가 보내는 상황을 만든다 (실시간 통신) */
    public void requestTestSelection(int position, String id, boolean stat) {
        run(() -> {
            String fu = stat ? FU_STAT_ID : FU_ROUTINE_ID;
            inquiryQueue.add(Hitachi3100Frame.createTestSelectionInquiry(fu, SampleInfo.forResult(0, position, id)));
        });
    }

    // ------------------------------------------------------------ 값 생성

    private double generatePatientValue(TestItem item) {
        int roll = random.nextInt(100);
        double low = item.getRefLow();
        double high = item.getRefHigh();
        if (roll < 15) {
            return high + random.nextDouble() * (high * 0.35);                 // High
        } else if (roll < 25) {
            return Math.max(0.1, low - random.nextDouble() * (low * 0.25));    // Low
        }
        double mid = (low + high) / 2.0;
        double spread = (high - low) * 0.35;
        double v = mid + random.nextGaussian() * (spread / 2.0);
        return Math.clamp(v, low, high);
    }

    private double generateControlValue(TestItem item, int controlNo) {
        double sd = (item.getRefHigh() - item.getRefLow()) / 6.0;
        double offset = controlNo == 1 ? -0.4 : controlNo == 2 ? 0.8 : 0.0;
        double v = item.getDefaultTarget() + sd * offset + random.nextGaussian() * sd * 0.35;
        return Math.max(0.0, v);
    }

    // ------------------------------------------------------------ 공통

    private void run(Runnable r) {
        ScheduledExecutorService ex = executor;
        if (ex == null) return;
        try {
            ex.execute(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    System.err.println("Simulator error: " + t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void schedule(Runnable r, long delayMs) {
        ScheduledExecutorService ex = executor;
        if (ex == null) return;
        try {
            ex.schedule(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    System.err.println("Simulator error: " + t);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    @Override
    public void addListener(HitachiChannelListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(HitachiChannelListener listener) {
        listeners.remove(listener);
    }

    @Override
    public String getChannelName() {
        return "Hitachi 3100 Internal Simulator";
    }

    private void notifyStatus(boolean conn, String message) {
        for (HitachiChannelListener l : listeners) {
            l.onStatusChanged(conn, message);
        }
    }

    private void log(String dir, byte[] raw, String summary) {
        for (HitachiChannelListener l : listeners) {
            l.onRawLog(dir, raw, summary);
        }
    }
}
