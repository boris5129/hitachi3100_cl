package com.hitachi3100.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 검사 오더 모델 (Routine / Stat 검체).
 * Control / Calibration 검체는 장비(AU)에 직접 설정하며 호스트가 TS 를 지시할 수 없다 (15.1.4 (3) 3).
 */
public class Order {
    public enum OrderStatus {
        WAITING("대기"),
        SENT("전송됨"),
        COMPLETED("완료"),
        ERROR("오류/시간초과");

        private final String label;
        OrderStatus(String label) {
            this.label = label;
        }
        public String getLabel() {
            return label;
        }
    }

    private final int position;       // Disk Position
    private final String sampleId;    // 환자/검체 ID (최대 13자)
    private final List<TestItem> testItems;
    private final LocalDateTime orderTime;
    private final boolean stat;
    private final int sampleNo;       // Sample No. 모드(바코드 리더 없음)에서 사용하는 번호
    private volatile OrderStatus status;
    private volatile boolean batchRequested;  // "오더 전송" 버튼으로 Batch 전송이 요청됨
    private volatile long sentAtMillis;

    public Order(int position, String sampleId, List<TestItem> testItems) {
        this(position, sampleId, testItems, false, 0);
    }

    public Order(int position, String sampleId, List<TestItem> testItems, boolean stat, int sampleNo) {
        this.position = position;
        this.sampleId = sampleId != null ? sampleId.trim() : "";
        this.testItems = new ArrayList<>(testItems != null ? testItems : Collections.emptyList());
        this.orderTime = LocalDateTime.now();
        this.stat = stat;
        this.sampleNo = sampleNo;
        this.status = OrderStatus.WAITING;
    }

    public int getPosition() {
        return position;
    }

    public String getSampleId() {
        return sampleId;
    }

    public List<TestItem> getTestItems() {
        return Collections.unmodifiableList(testItems);
    }

    public LocalDateTime getOrderTime() {
        return orderTime;
    }

    public String getFormattedOrderTime() {
        return orderTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public boolean isStat() {
        return stat;
    }

    public int getSampleNo() {
        return sampleNo;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
        if (status == OrderStatus.SENT) {
            this.sentAtMillis = System.currentTimeMillis();
        }
    }

    public boolean isBatchRequested() {
        return batchRequested;
    }

    public void setBatchRequested(boolean batchRequested) {
        this.batchRequested = batchRequested;
    }

    public long getSentAtMillis() {
        return sentAtMillis;
    }

    public String getItemsSummary() {
        return testItems.stream().map(TestItem::getCode).collect(Collectors.joining(", "));
    }
}
