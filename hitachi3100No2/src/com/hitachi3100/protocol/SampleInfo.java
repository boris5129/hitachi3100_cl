package com.hitachi3100.protocol;

import java.util.Locale;

/**
 * Sample Information (37 bytes) - Table 15.1.5-12/13
 * Sample No.(5) + Cup Size(1) + Position No.(3) + ID No.(13) + Cell No.(2) + Space(13)
 */
public final class SampleInfo {
    private final String sampleNo;  // 5
    private final char cupSize;     // 1  (' ', '1', '2')
    private final String position;  // 3
    private final String id;        // 13
    private final String cellNo;    // 2
    private final String reserved;  // 13

    private SampleInfo(String sampleNo, char cupSize, String position, String id, String cellNo, String reserved) {
        this.sampleNo = sampleNo;
        this.cupSize = cupSize;
        this.position = position;
        this.id = id;
        this.cellNo = cellNo;
        this.reserved = reserved;
    }

    public static SampleInfo parse(String s) {
        if (s == null || s.length() != Hitachi3100Constants.SAMPLE_INFO_LENGTH) {
            throw new FrameFormatException("Sample Information 길이 오류 (37바이트 필요)");
        }
        return new SampleInfo(s.substring(0, 5), s.charAt(5), s.substring(6, 9),
                s.substring(9, 22), s.substring(22, 24), s.substring(24, 37));
    }

    /** Host -> AU TS 지시용. sampleNo/position 이 0 이면 공백으로 채움 */
    public static SampleInfo forDirective(int sampleNo, char cupSize, int position, String id) {
        return new SampleInfo(
                sampleNo > 0 ? padLeft(String.valueOf(sampleNo), 5) : "     ",
                cupSize,
                position > 0 ? padLeft(String.valueOf(position), 3) : "   ",
                padLeft(id == null ? "" : id, 13),
                "  ", "             ");
    }

    /** 결과/문의 프레임 생성용 (AU 시뮬레이터, 테스트) */
    public static SampleInfo forResult(int sampleNo, int position, String id) {
        return new SampleInfo(
                sampleNo > 0 ? padLeft(String.valueOf(sampleNo), 5) : "     ",
                '1',
                position > 0 ? padLeft(String.valueOf(position), 3) : "   ",
                padLeft(id == null ? "" : id, 13),
                "  ", "             ");
    }

    /** Control sample: Sample No. 필드 = [공백2][Control No. 1자리][Analyze Count 2자리] (Table 15.1.5-13 해석) */
    public static SampleInfo forControl(int controlNo, int analyzeCount) {
        String no = "  " + controlNo + String.format(Locale.ROOT, "%02d", analyzeCount);
        return new SampleInfo(no, '1', "   ", "             ", "  ", "             ");
    }

    public String encode() {
        return sampleNo + cupSize + position + id + cellNo + reserved;
    }

    public String getId() {
        return id.trim();
    }

    public String getRawSampleNo() {
        return sampleNo;
    }

    public int getSampleNumber() {
        return toInt(sampleNo.trim());
    }

    public int getPositionNumber() {
        return toInt(position.trim());
    }

    public char getCupSize() {
        return cupSize;
    }

    /** Control sample 의 Control No. (1~5), 해석 실패 시 0 */
    public int getControlNo() {
        char c = sampleNo.charAt(2);
        int d = Character.digit(c, 10);
        return (d >= 1 && d <= 5) ? d : 0;
    }

    public int getAnalyzeCount() {
        return toInt(sampleNo.substring(3).trim());
    }

    private static int toInt(String s) {
        try {
            return s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String padLeft(String s, int width) {
        if (s.length() > width) {
            throw new IllegalArgumentException("필드 길이 초과(" + width + "): " + s);
        }
        return " ".repeat(width - s.length()) + s;
    }

    @Override
    public String toString() {
        return "SampleInfo[no=" + sampleNo.trim() + ", pos=" + position.trim() + ", id=" + getId() + "]";
    }
}
