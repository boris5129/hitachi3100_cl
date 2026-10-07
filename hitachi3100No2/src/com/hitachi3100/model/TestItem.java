package com.hitachi3100.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Hitachi 3100 지원 17개 임상화학 검사 항목 정의
 */
public enum TestItem {
    AST(1, "AST", "Aspartate Aminotransferase", "U/L", 10.0, 40.0, 1, 30.0),
    ALT(2, "ALT", "Alanine Aminotransferase", "U/L", 7.0, 40.0, 1, 28.0),
    GGT(3, "GGT", "γ-GTP", "U/L", 9.0, 64.0, 1, 35.0),
    T_BIL(4, "T-bil", "Total Bilirubin", "mg/dL", 0.2, 1.2, 2, 0.7),
    D_BIL(5, "D-bil", "Direct Bilirubin", "mg/dL", 0.0, 0.4, 2, 0.2),
    BUN(6, "BUN", "Blood Urea Nitrogen", "mg/dL", 8.0, 20.0, 1, 14.0),
    CREA(7, "Crea", "Creatinine", "mg/dL", 0.5, 1.2, 2, 0.9),
    HDL_C(8, "HDL-C", "HDL Cholesterol", "mg/dL", 40.0, 60.0, 1, 50.0),
    LDL_C(9, "LDL-C", "LDL Cholesterol", "mg/dL", 0.0, 130.0, 1, 95.0),
    CHOL(10, "Cholesterol", "Total Cholesterol", "mg/dL", 120.0, 200.0, 1, 165.0),
    TG(11, "T.G", "Triglycerides", "mg/dL", 35.0, 160.0, 1, 110.0),
    ALBUMIN(12, "Albumin", "Albumin", "g/dL", 3.5, 5.2, 2, 4.2),
    GLUCOSE(13, "Glucose", "Glucose", "mg/dL", 70.0, 100.0, 1, 85.0),
    TP(14, "T.P", "Total Protein", "g/dL", 6.4, 8.3, 1, 7.3),
    CA(15, "Ca", "Calcium", "mg/dL", 8.6, 10.2, 2, 9.4),
    SSA(16, "SSA", "Serum Sialic Acid", "mg/dL", 45.0, 75.0, 1, 60.0),
    IMA(17, "IMA", "Ischemia-Modified Albumin", "U/mL", 0.0, 85.0, 1, 40.0);

    // 장비(AU)에 설정된 Test Code(채널 번호). data/channel_map.properties 로 재지정 가능
    private volatile int channel;
    private final String code;
    private final String fullName;
    private final String unit;
    private final double refLow;
    private final double refHigh;
    private final int decimalPlaces;
    private final double defaultTarget;

    TestItem(int channel, String code, String fullName, String unit, double refLow, double refHigh, int decimalPlaces, double defaultTarget) {
        this.channel = channel;
        this.code = code;
        this.fullName = fullName;
        this.unit = unit;
        this.refLow = refLow;
        this.refHigh = refHigh;
        this.decimalPlaces = decimalPlaces;
        this.defaultTarget = defaultTarget;
    }

    public int getChannel() {
        return channel;
    }

    public String getCode() {
        return code;
    }

    public String getFullName() {
        return fullName;
    }

    public String getUnit() {
        return unit;
    }

    public double getRefLow() {
        return refLow;
    }

    public double getRefHigh() {
        return refHigh;
    }

    public int getDecimalPlaces() {
        return decimalPlaces;
    }

    public double getDefaultTarget() {
        return defaultTarget;
    }

    public String formatValue(double val) {
        if (Double.isNaN(val)) return "----";
        return String.format(Locale.ROOT, "%." + decimalPlaces + "f", val);
    }

    public String evaluateFlag(double val) {
        if (Double.isNaN(val)) {
            return "-";
        } else if (val < refLow) {
            return "L";
        } else if (val > refHigh) {
            return "H";
        } else {
            return "Normal";
        }
    }

    public static Optional<TestItem> fromCode(String code) {
        if (code == null) return Optional.empty();
        String trimmed = code.trim();
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(trimmed) || item.name().equalsIgnoreCase(trimmed))
                .findFirst();
    }

    public static Optional<TestItem> fromChannel(int ch) {
        return Arrays.stream(values())
                .filter(item -> item.channel == ch)
                .findFirst();
    }

    /**
     * 채널 번호 매핑 적용. 형식: 항목코드(enum 이름 또는 표시 코드)=채널번호  예) AST=1, T-bil=4
     * 장비의 Test Code 배정이 기본값(1~17)과 다를 때 사용한다.
     */
    public static void applyChannelMap(Properties props) {
        for (TestItem item : values()) {
            String v = props.getProperty(item.name());
            if (v == null) v = props.getProperty(item.code);
            if (v == null) continue;
            try {
                int ch = Integer.parseInt(v.trim());
                if (ch >= 1 && ch <= 37) {
                    item.channel = ch;
                } else {
                    System.err.println("channel_map: 채널 범위 오류(1~37) " + item.code + "=" + v);
                }
            } catch (NumberFormatException e) {
                System.err.println("channel_map: 숫자 형식 오류 " + item.code + "=" + v);
            }
        }
    }

    public static void loadChannelMap(File file) {
        if (file == null || !file.exists()) return;
        Properties p = new Properties();
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            p.load(r);
            applyChannelMap(p);
        } catch (Exception e) {
            System.err.println("channel_map 로드 실패: " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return code;
    }
}
