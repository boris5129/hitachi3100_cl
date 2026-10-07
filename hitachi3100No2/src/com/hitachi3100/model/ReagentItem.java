package com.hitachi3100.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * R1, R2 시약 개별 관리 모델 (총 34개 시약 관리)
 */
public class ReagentItem {
    public enum ReagentType {
        R1, R2
    }

    public enum ReagentStatus {
        NORMAL("정상"),
        LOW("잔량 부족"),
        DEPLETED("소진");

        private final String label;
        ReagentStatus(String label) {
            this.label = label;
        }
        public String getLabel() {
            return label;
        }
    }

    private final TestItem item;
    private final ReagentType type;
    private int currentTests;
    private int maxTests;
    private LocalDateTime lastUsedTime;

    public ReagentItem(TestItem item, ReagentType type, int currentTests, int maxTests) {
        this.item = item;
        this.type = type;
        this.maxTests = Math.max(1, maxTests);
        this.currentTests = Math.clamp(currentTests, 0, this.maxTests);
        this.lastUsedTime = LocalDateTime.now();
    }

    public TestItem getItem() {
        return item;
    }

    public ReagentType getType() {
        return type;
    }

    public String getName() {
        return item.getCode() + "-" + type.name();
    }

    public int getCurrentTests() {
        return currentTests;
    }

    public void setCurrentTests(int currentTests) {
        this.currentTests = Math.clamp(currentTests, 0, maxTests);
    }

    public int getMaxTests() {
        return maxTests;
    }

    /**
     * 시약별 총 test 수 변동 기능
     */
    public void setMaxTests(int newMax) {
        this.maxTests = Math.max(1, newMax);
        if (this.currentTests > this.maxTests) {
            this.currentTests = this.maxTests;
        }
    }

    /**
     * 결과 수신 시 1 카운트 자동 차감
     */
    public synchronized boolean decrement() {
        if (currentTests > 0) {
            currentTests--;
            lastUsedTime = LocalDateTime.now();
            return true;
        }
        return false;
    }

    /**
     * 시약 리셋: 변동한 수량 max로 리셋
     */
    public synchronized void resetToMax() {
        this.currentTests = this.maxTests;
        this.lastUsedTime = LocalDateTime.now();
    }

    public double getRemainingPercentage() {
        return (double) currentTests / maxTests * 100.0;
    }

    public ReagentStatus getStatus() {
        if (currentTests <= 0) {
            return ReagentStatus.DEPLETED;
        } else if (getRemainingPercentage() <= 15.0) {
            return ReagentStatus.LOW;
        } else {
            return ReagentStatus.NORMAL;
        }
    }

    public LocalDateTime getLastUsedTime() {
        return lastUsedTime;
    }

    public void setLastUsedTime(LocalDateTime time) {
        this.lastUsedTime = time;
    }

    public String getFormattedLastUsedTime() {
        return lastUsedTime != null ? lastUsedTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : "-";
    }

    public String toCsvLine() {
        return String.join(",",
                item.getCode(),
                type.name(),
                String.valueOf(currentTests),
                String.valueOf(maxTests),
                lastUsedTime != null ? lastUsedTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : ""
        );
    }

    public static ReagentItem fromCsvLine(String line) {
        if (line == null || line.isBlank()) return null;
        String[] p = line.split(",");
        if (p.length < 4) return null;
        try {
            TestItem item = TestItem.fromCode(p[0].trim()).orElse(null);
            if (item == null) return null;
            ReagentType type = ReagentType.valueOf(p[1].trim());
            int cur = Integer.parseInt(p[2].trim());
            int max = Integer.parseInt(p[3].trim());
            ReagentItem ri = new ReagentItem(item, type, cur, max);
            if (p.length >= 5 && !p[4].isBlank()) {
                ri.setLastUsedTime(LocalDateTime.parse(p[4].trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            return ri;
        } catch (Exception e) {
            return null;
        }
    }
}
