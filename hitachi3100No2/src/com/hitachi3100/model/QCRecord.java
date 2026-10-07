package com.hitachi3100.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 정도관리 (QC) 및 캘리브레이션 수신 데이터 레코드
 */
public class QCRecord {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LocalDateTime timestamp;
    private final String qcLevel; // "QC1", "QC2", "CAL"
    private final TestItem item;
    private final double value;
    private final double target;
    private final double min;
    private final double max;
    private final double diff; // value - target
    private final String status; // "In-Control", "Warning(2SD)", "Out-of-Control"

    public QCRecord(LocalDateTime timestamp, String qcLevel, TestItem item, double value, double target, double min, double max) {
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
        this.qcLevel = qcLevel != null ? qcLevel.trim() : "QC";
        this.item = item;
        this.value = value;
        this.target = target;
        this.min = min;
        this.max = max;
        this.diff = value - target;

        // 상태 판정
        if (value < min || value > max) {
            this.status = "Out-of-Control";
        } else {
            // SD 추정: (max - min) / 6 approx
            double sd = (max - min) / 6.0;
            if (sd > 0.0001 && Math.abs(value - target) > 2 * sd) {
                this.status = "Warning(2SD)";
            } else {
                this.status = "In-Control";
            }
        }
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getFormattedTimestamp() {
        return timestamp.format(FORMATTER);
    }

    public String getQcLevel() {
        return qcLevel;
    }

    public TestItem getItem() {
        return item;
    }

    public double getValue() {
        return value;
    }

    public double getTarget() {
        return target;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getDiff() {
        return diff;
    }

    public String getStatus() {
        return status;
    }

    public String toCsvLine() {
        return String.join(",",
                getFormattedTimestamp(),
                qcLevel,
                item.getCode(),
                String.format(java.util.Locale.ROOT, "%.4f", value),
                String.format(java.util.Locale.ROOT, "%.4f", target),
                String.format(java.util.Locale.ROOT, "%.4f", min),
                String.format(java.util.Locale.ROOT, "%.4f", max),
                status
        );
    }

    public static QCRecord fromCsvLine(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.split(",");
        if (parts.length < 8) return null;

        try {
            LocalDateTime dt = LocalDateTime.parse(parts[0].trim(), FORMATTER);
            String level = parts[1].trim();
            TestItem item = TestItem.fromCode(parts[2].trim()).orElse(null);
            if (item == null) return null;

            double val = Double.parseDouble(parts[3].trim());
            double tgt = Double.parseDouble(parts[4].trim());
            double mn = Double.parseDouble(parts[5].trim());
            double mx = Double.parseDouble(parts[6].trim());

            return new QCRecord(dt, level, item, val, tgt, mn, mx);
        } catch (Exception e) {
            return null;
        }
    }
}
