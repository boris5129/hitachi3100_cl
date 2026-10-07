package com.hitachi3100.model;

/**
 * 단일 검사항목 측정 결과 모델.
 * flag 는 참고치 범위로 호스트가 판정한 값("Normal"/"H"/"L"), 값이 없으면 "-".
 * alarmCode 는 장비가 보낸 Data Alarm 문자(15.1.10)이며 High/Low 와 무관하다.
 */
public class TestResult {
    private final TestItem item;
    private final double value;
    private final String flag; // "Normal", "H", "L", "-"
    private final String alarmCode;
    private final String unit;
    private final String refRange;

    public TestResult(TestItem item, double value, String flag, String alarmCode) {
        this.item = item;
        this.value = value;
        this.flag = (flag != null && !flag.isBlank()) ? flag : item.evaluateFlag(value);
        this.alarmCode = alarmCode != null ? alarmCode.trim() : "";
        this.unit = item.getUnit();
        this.refRange = item.getRefLow() + " - " + item.getRefHigh() + " " + item.getUnit();
    }

    public TestItem getItem() {
        return item;
    }

    public double getValue() {
        return value;
    }

    /** 장비가 값을 공백으로 대체한 경우(예: 검체량 부족)에는 false */
    public boolean hasValue() {
        return !Double.isNaN(value);
    }

    public String getFlag() {
        return flag;
    }

    public String getAlarmCode() {
        return alarmCode;
    }

    public boolean hasAlarm() {
        return !alarmCode.isEmpty();
    }

    public String getAlarmDescription() {
        return DataAlarm.describe(alarmCode);
    }

    public String getUnit() {
        return unit;
    }

    public String getRefRange() {
        return refRange;
    }

    public String getFormattedValue() {
        return item.formatValue(value);
    }

    /** 예: "AST: 35.0 U/L (Normal)" / "ALT: 65.0 U/L (H)" / "AST: ---- U/L (-) [알람 V]" */
    public String getSummaryString() {
        String s = item.getCode() + ": " + getFormattedValue() + " " + unit + " (" + flag + ")";
        if (hasAlarm()) s += " [알람 " + alarmCode + "]";
        return s;
    }

    /** High/Low 판정 여부 (알람은 포함하지 않음) */
    public boolean isAbnormal() {
        return "H".equalsIgnoreCase(flag) || "L".equalsIgnoreCase(flag);
    }

    @Override
    public String toString() {
        return getSummaryString();
    }
}
