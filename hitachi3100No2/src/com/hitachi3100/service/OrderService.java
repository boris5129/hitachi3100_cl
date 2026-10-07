package com.hitachi3100.service;

import com.hitachi3100.model.Order;
import com.hitachi3100.model.TestItem;
import com.hitachi3100.model.TestResult;
import com.hitachi3100.protocol.Hitachi3100Constants;
import com.hitachi3100.protocol.Hitachi3100Frame;
import com.hitachi3100.protocol.HostProtocolHandler;
import com.hitachi3100.protocol.IHitachiChannel;
import com.hitachi3100.protocol.SampleInfo;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 검사 오더 관리 및 호스트 프로토콜 업무 계층.
 *
 * - 통신 절차(ANY/MOR/SPE/REP)는 {@link HostProtocolHandler} 가 담당하고, 이 클래스는 "무엇을 보낼지 / 받은 결과를 어떻게 처리할지"를 결정한다.
 * - Routine 검체: "오더 전송" 시 Batch(ANY 에 대한 SPE 응답)로 전달되고, AU 가 실시간 TS 문의를 하면 거기에도 응답한다.
 * - Stat 검체: 매뉴얼상 Batch 통신 불가(Table 15.1.2-1) → AU 의 실시간 TS 문의에만 응답한다.
 * - Control / Calibration: Host 가 TS 를 지시할 수 없다. 결과 프레임의 Function Character('f')로만 판별한다 (ID 문자열로 추정하지 않음).
 */
public class OrderService implements HostProtocolHandler.Delegate {

    /** 아직 완료되지 않은 오더 (WAITING / SENT / ERROR). 완료되면 목록에서 제거되고 이력으로 이동 */
    private final List<Order> orders = new CopyOnWriteArrayList<>();
    private final List<Runnable> orderChangeListeners = new CopyOnWriteArrayList<>();
    private final List<ResultListener> resultListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> eventListeners = new CopyOnWriteArrayList<>();

    private final QCService qcService;
    private final ReagentService reagentService;
    private final HistoryService historyService;

    private volatile IHitachiChannel currentChannel;
    private HostProtocolHandler handler;
    private volatile int currentDiskPosition = 1;
    private volatile String nextPatientId = "PAT-1001";
    private volatile boolean idMode = true;               // true: 바코드 리더 장착(ID 모드), false: Sample No. 모드
    private volatile long resultTimeoutMs = 30 * 60 * 1000L;
    private volatile long hostResponseDelayMs = Hitachi3100Constants.DEFAULT_HOST_RESPONSE_DELAY_MS;
    private int nextRoutineSampleNo = 1;
    private int nextStatSampleNo = 1;

    private final ScheduledExecutorService sweeper;

    public interface ResultListener {
        void onResultReceived(Order order, List<TestResult> results, String summaryText);
    }

    public OrderService(QCService qcService, ReagentService reagentService, HistoryService historyService) {
        this.qcService = qcService;
        this.reagentService = reagentService;
        this.historyService = historyService;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OrderTimeoutSweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweeper.scheduleWithFixedDelay(this::sweepTimeouts, 5, 5, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------ 채널 연결

    public synchronized void setCommunicationChannel(IHitachiChannel channel) {
        if (handler != null) {
            if (currentChannel != null) currentChannel.removeListener(handler);
            handler.close();
            handler = null;
        }
        this.currentChannel = channel;
        if (channel != null) {
            handler = new HostProtocolHandler(channel, this);
            handler.setHostResponseDelayMs(hostResponseDelayMs);
            channel.addListener(handler);
        }
    }

    public IHitachiChannel getCommunicationChannel() {
        return currentChannel;
    }

    public synchronized void shutdown() {
        if (handler != null) {
            if (currentChannel != null) currentChannel.removeListener(handler);
            handler.close();
            handler = null;
        }
        sweeper.shutdownNow();
    }

    // ------------------------------------------------------------ 설정

    public boolean isIdMode() { return idMode; }
    public void setIdMode(boolean idMode) { this.idMode = idMode; }

    public void setResultTimeoutMs(long ms) { this.resultTimeoutMs = Math.max(1000, ms); }

    public synchronized void setHostResponseDelayMs(long ms) {
        this.hostResponseDelayMs = ms;
        if (handler != null) handler.setHostResponseDelayMs(ms);
    }

    public int getCurrentDiskPosition() { return currentDiskPosition; }

    public void setCurrentDiskPosition(int pos) {
        this.currentDiskPosition = Math.clamp(pos, 1, Hitachi3100Constants.MAX_POSITION);
    }

    public String getNextPatientId() { return nextPatientId; }
    public void setNextPatientId(String nextPatientId) { this.nextPatientId = nextPatientId; }

    // ------------------------------------------------------------ 오더 관리

    public Order addOrder(int position, String sampleId, List<TestItem> items) {
        return addOrder(position, sampleId, items, false);
    }

    /**
     * 오더 추가. 규격(15.1.5, 15.1.6)에 맞지 않거나 중복이면 IllegalArgumentException.
     * 성공하면 Disk Position / 환자 ID 가 자동으로 1씩 증가한다.
     */
    public synchronized Order addOrder(int position, String sampleId, List<TestItem> items, boolean stat) {
        String id = sampleId == null ? "" : sampleId.trim();
        if (position < 1 || position > Hitachi3100Constants.MAX_POSITION) {
            throw new IllegalArgumentException("Disk Position 은 1~" + Hitachi3100Constants.MAX_POSITION + " 범위여야 합니다.");
        }
        if (id.isEmpty()) {
            throw new IllegalArgumentException("검체 ID 를 입력하세요.");
        }
        if (id.length() > Hitachi3100Constants.MAX_ID_LENGTH) {
            throw new IllegalArgumentException("검체 ID 는 최대 " + Hitachi3100Constants.MAX_ID_LENGTH + "자입니다 (현재 " + id.length() + "자).");
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException("검체 ID 는 영문/숫자/기호(ASCII)만 사용할 수 있습니다: '" + c + "'");
            }
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("검사 항목을 하나 이상 선택하세요.");
        }
        for (TestItem ti : items) {
            if (ti.getChannel() < 1 || ti.getChannel() > 37) {
                throw new IllegalArgumentException(ti.getCode() + " 의 채널 번호가 올바르지 않습니다.");
            }
        }
        for (Order o : orders) {
            if (o.getPosition() == position) {
                throw new IllegalArgumentException("Position " + position + " 에 이미 처리 중인 오더가 있습니다 (" + o.getSampleId() + ").");
            }
            if (o.getSampleId().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException("검체 ID '" + id + "' 오더가 이미 있습니다 (Position " + o.getPosition() + ").");
            }
        }

        int sampleNo;
        if (stat) {
            sampleNo = nextStatSampleNo;
            nextStatSampleNo = (nextStatSampleNo % Hitachi3100Constants.MAX_STAT_SAMPLE_NO) + 1;
        } else {
            sampleNo = nextRoutineSampleNo;
            nextRoutineSampleNo = (nextRoutineSampleNo % Hitachi3100Constants.MAX_ROUTINE_SAMPLE_NO) + 1;
        }

        Order order = new Order(position, id, items, stat, sampleNo);
        orders.add(order);
        currentDiskPosition = (position % Hitachi3100Constants.MAX_POSITION) + 1;
        nextPatientId = incrementIdString(id);
        notifyOrderChanged();
        return order;
    }

    /**
     * ID 문자열 내의 마지막 숫자 부분을 찾아 1 증가시키는 유틸리티
     * 예: "PAT-1001" -> "PAT-1002", "100" -> "101"
     */
    public static String incrementIdString(String original) {
        if (original == null || original.isBlank()) return "1";

        Pattern p = Pattern.compile("(.*?)(\\d+)(\\D*)$");
        Matcher m = p.matcher(original);
        if (m.matches()) {
            String prefix = m.group(1);
            String numStr = m.group(2);
            String suffix = m.group(3);
            try {
                long val = Long.parseLong(numStr) + 1;
                String formatStr = "%0" + numStr.length() + "d";
                return prefix + String.format(Locale.ROOT, formatStr, val) + suffix;
            } catch (NumberFormatException ignored) {
            }
        }
        return original + "-1";
    }

    public List<Order> getPendingOrders() {
        return Collections.unmodifiableList(orders);
    }

    public synchronized void removeOrder(Order order) {
        if (orders.remove(order)) notifyOrderChanged();
    }

    public synchronized void clearPendingOrders() {
        orders.clear();
        notifyOrderChanged();
    }

    /**
     * "오더 전송": 대기 중인 Routine 오더를 Batch 전송 대상으로 표시한다. 실제 SPE 는 AU 의 다음 ANY 에 대한 응답으로 나간다.
     * Stat 오더는 Batch 통신이 불가하므로 AU 의 실시간 TS 문의 시 전달된다.
     * @return Batch 전송 대기열에 올라간 Routine 오더 수
     * @throws IllegalStateException 통신이 연결되어 있지 않을 때
     */
    public synchronized int transmitPendingOrders() {
        IHitachiChannel ch = currentChannel;
        if (ch == null || !ch.isConnected()) {
            throw new IllegalStateException("장비와 연결되어 있지 않습니다. 상단에서 연결 후 다시 시도하세요.");
        }
        int queued = 0;
        for (Order o : orders) {
            if (o.getStatus() == Order.OrderStatus.WAITING && !o.isStat() && !o.isBatchRequested()) {
                o.setBatchRequested(true);
                queued++;
            }
        }
        notifyOrderChanged();
        return queued;
    }

    public synchronized void transmitSingleOrder(Order order) {
        IHitachiChannel ch = currentChannel;
        if (ch == null || !ch.isConnected()) {
            throw new IllegalStateException("장비와 연결되어 있지 않습니다.");
        }
        if (order.getStatus() == Order.OrderStatus.WAITING && !order.isStat()) {
            order.setBatchRequested(true);
            notifyOrderChanged();
        }
    }

    // ------------------------------------------------------------ HostProtocolHandler.Delegate

    @Override
    public synchronized HostProtocolHandler.TestSelection nextBatchSelection() {
        for (Order o : orders) {
            if (o.getStatus() == Order.OrderStatus.WAITING && o.isBatchRequested() && !o.isStat()) {
                return buildSelection(o, null);
            }
        }
        return null;
    }

    @Override
    public synchronized HostProtocolHandler.TestSelection findTestSelection(SampleInfo inquiry, String fu) {
        Order o = findOrder(inquiry, fu);
        if (o == null || o.getStatus() == Order.OrderStatus.COMPLETED) return null;
        return buildSelection(o, inquiry);   // 문의에 실린 Sample Information 을 그대로 되돌려 준다 (Position 변경 불가, 15.1.5 (1) 4) (f))
    }

    private HostProtocolHandler.TestSelection buildSelection(Order o, SampleInfo echo) {
        String fu;
        SampleInfo info;
        if (echo != null) {
            fu = o.isStat()
                    ? (idMode ? Hitachi3100Constants.FU_STAT_ID : Hitachi3100Constants.FU_STAT_NO_ID)
                    : (idMode ? Hitachi3100Constants.FU_ROUTINE_ID : Hitachi3100Constants.FU_ROUTINE_NO_ID);
            info = echo;
        } else {
            fu = o.isStat()
                    ? (idMode ? Hitachi3100Constants.FU_STAT_ID : Hitachi3100Constants.FU_STAT_NO_ID)
                    : (idMode ? Hitachi3100Constants.FU_ROUTINE_ID : Hitachi3100Constants.FU_ROUTINE_NO_ID);
            // Batch 지시: Position 은 공백, ID 모드에서는 Sample No. 무시, Sample No. 모드에서는 번호 지정
            info = SampleInfo.forDirective(idMode ? 0 : o.getSampleNo(), ' ', o.isStat() ? o.getPosition() : 0, o.getSampleId());
        }
        return new HostProtocolHandler.TestSelection(fu, info, new ArrayList<>(o.getTestItems()), o);
    }

    @Override
    public void onTestSelectionSent(Object token) {
        if (token instanceof Order) {
            Order o = (Order) token;
            if (o.getStatus() == Order.OrderStatus.WAITING || o.getStatus() == Order.OrderStatus.ERROR) {
                o.setStatus(Order.OrderStatus.SENT);
            }
            o.setBatchRequested(false);
            notifyOrderChanged();
            fireEvent("TS 전송: Position " + o.getPosition() + " / " + o.getSampleId() + " (" + o.getTestItems().size() + "항목)");
        }
    }

    /** 진행 중인 오더 중 결과 샘플정보와 일치하는 것을 찾는다 (ID 모드: ID, Sample No. 모드: 번호/Position) */
    private Order findOrder(SampleInfo info, String fu) {
        boolean idFu = Hitachi3100Frame.isIdModeFu(fu);
        Hitachi3100Frame.SampleKind kind = Hitachi3100Frame.kindOf(fu);
        boolean stat = kind == Hitachi3100Frame.SampleKind.STAT;
        for (Order o : orders) {
            if (o.isStat() != stat) continue;
            if (idFu) {
                String id = info.getId();
                if (!id.isEmpty() && o.getSampleId().equalsIgnoreCase(id)) return o;
            } else if (stat) {
                if (info.getPositionNumber() > 0 && o.getPosition() == info.getPositionNumber()) return o;
            } else {
                if (info.getSampleNumber() > 0 && o.getSampleNo() == info.getSampleNumber()) return o;
            }
        }
        return null;
    }

    @Override
    public void onSampleResult(Hitachi3100Frame.SampleKind kind, String fu, SampleInfo sample, List<TestResult> results) {
        if (kind == Hitachi3100Frame.SampleKind.CONTROL) {
            handleControlResult(sample, results);
            return;
        }
        handlePatientResult(fu, sample, results);
    }

    private void handleControlResult(SampleInfo sample, List<TestResult> results) {
        int controlNo = sample.getControlNo();
        String level = controlNo > 0 ? "QC" + controlNo : "QC";
        int recorded = qcService.recordQCResult(level, results);
        deductReagents(results);
        fireEvent("Control 샘플 결과 수신: " + level + " (" + recorded + "항목 기록)");
    }

    private void handlePatientResult(String fu, SampleInfo sample, List<TestResult> results) {
        Order matched;
        synchronized (this) {
            matched = findOrder(sample, fu);
            if (matched != null) {
                matched.setStatus(Order.OrderStatus.COMPLETED);
                orders.remove(matched);
            }
        }

        if (matched == null) {
            // 이 프로그램에 오더가 없는 검체 (장비에서 직접 등록했거나 다른 시스템 오더)
            List<TestItem> items = results.stream().map(TestResult::getItem).distinct().collect(Collectors.toList());
            int pos = sample.getPositionNumber();
            String id = sample.getId().isEmpty() ? ("S.No " + sample.getSampleNumber()) : sample.getId();
            matched = new Order(pos, id, items, Hitachi3100Frame.kindOf(fu) == Hitachi3100Frame.SampleKind.STAT, sample.getSampleNumber());
            matched.setStatus(Order.OrderStatus.COMPLETED);
            fireEvent("오더 없는 검체의 결과 수신: " + id);
        }

        String summary = results.stream().map(TestResult::getSummaryString).collect(Collectors.joining(" | "));
        historyService.recordPatientResult(matched.getPosition(), matched.getSampleId(), matched.getItemsSummary(), results);
        deductReagents(results);

        for (ResultListener rl : resultListeners) {
            try {
                rl.onResultReceived(matched, results, summary);
            } catch (RuntimeException e) {
                com.hitachi3100.util.AppLog.error("ResultListener error: " + e);
            }
        }
        notifyOrderChanged();
    }

    private void deductReagents(List<TestResult> results) {
        List<TestItem> used = results.stream().map(TestResult::getItem).collect(Collectors.toList());
        List<String> empty = reagentService.deductReagents(used);
        if (!empty.isEmpty()) {
            fireEvent("시약 잔량이 0 인 상태에서 측정되었습니다: " + String.join(", ", empty));
        }
    }

    @Override
    public void onOtherData(String description) {
        fireEvent(description);
    }

    @Override
    public void onEvent(String message, boolean error) {
        fireEvent((error ? "⚠ " : "") + message);
    }

    // ------------------------------------------------------------ 타임아웃

    private void sweepTimeouts() {
        try {
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (Order o : orders) {
                if (o.getStatus() == Order.OrderStatus.SENT && now - o.getSentAtMillis() > resultTimeoutMs) {
                    o.setStatus(Order.OrderStatus.ERROR);
                    fireEvent("⚠ 결과 대기 시간 초과: Position " + o.getPosition() + " / " + o.getSampleId());
                    changed = true;
                }
            }
            if (changed) notifyOrderChanged();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 리스너

    public void addOrderChangeListener(Runnable r) {
        orderChangeListeners.add(r);
    }

    public void addResultListener(ResultListener rl) {
        resultListeners.add(rl);
    }

    /** 통신 이벤트/경고 메시지 수신 (UI 스레드가 아닌 스레드에서 호출될 수 있음) */
    public void addEventListener(Consumer<String> l) {
        eventListeners.add(l);
    }

    private void notifyOrderChanged() {
        for (Runnable r : orderChangeListeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                com.hitachi3100.util.AppLog.error("OrderChangeListener error: " + e);
            }
        }
    }

    private void fireEvent(String msg) {
        if (msg.startsWith("⚠")) com.hitachi3100.util.AppLog.warn(msg); else com.hitachi3100.util.AppLog.info(msg);
        for (Consumer<String> l : eventListeners) {
            try {
                l.accept(msg);
            } catch (RuntimeException e) {
                com.hitachi3100.util.AppLog.error("EventListener error: " + e);
            }
        }
    }
}
