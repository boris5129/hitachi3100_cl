package com.hitachi3100.protocol;

import com.hitachi3100.model.TestItem;
import com.hitachi3100.model.TestResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static com.hitachi3100.protocol.Hitachi3100Constants.*;

/**
 * Hitachi 3100 통신 프레임 인코더/디코더 (매뉴얼 15.1.3 ~ 15.1.6).
 * 종료 코드는 기본값인 "ETX + BCC" 방식만 지원한다 (장비의 End-of-data code 설정을 맞출 것).
 */
public final class Hitachi3100Frame {
    private Hitachi3100Frame() {}

    /** Function Character 로 구분되는 샘플 종류 */
    public enum SampleKind { ROUTINE, STAT, CONTROL, CALIBRATION, ABSORBANCE, UNKNOWN }

    // ------------------------------------------------------------------ BCC

    /** BCC: offset 부터 length 바이트 XOR (STX 다음 문자 ~ ETX 까지, ETX 포함) */
    public static byte calculateBCC(byte[] data, int offset, int length) {
        byte bcc = 0;
        for (int i = 0; i < length; i++) {
            bcc ^= data[offset + i];
        }
        return bcc;
    }

    /** STX ... ETX BCC 구조와 BCC 값이 올바른지 검사 */
    public static boolean hasValidBcc(byte[] frame) {
        if (frame == null || frame.length < 4 || frame[0] != STX) return false;
        int etx = frame.length - 2;
        if (frame[etx] != ETX) return false;
        for (int i = 1; i < etx; i++) {
            if (frame[i] == ETX) return false;   // ETX 가 중간에 있으면 구조 오류
        }
        return calculateBCC(frame, 1, etx) == frame[frame.length - 1];
    }

    // ------------------------------------------------------------------ 인코더

    /** body(프레임 문자 + 데이터 필드)를 STX/ETX/BCC 로 감싼다 */
    public static byte[] wrap(String body) {
        byte[] b = body.getBytes(StandardCharsets.US_ASCII);
        byte[] frame = new byte[b.length + 3];
        frame[0] = STX;
        System.arraycopy(b, 0, frame, 1, b.length);
        frame[frame.length - 2] = ETX;
        frame[frame.length - 1] = calculateBCC(frame, 1, frame.length - 2);
        return frame;
    }

    /** 제어 프레임 (ANY, MOR, REP) */
    public static byte[] createControlFrame(char frameChar) {
        return wrap(String.valueOf(frameChar));
    }

    /** AU -> Host : TS 문의 (SPE) = ';' + FU(2) + SampleInfo(37)  (총 43바이트) */
    public static byte[] createTestSelectionInquiry(String fu, SampleInfo info) {
        return wrap(String.valueOf(FRAME_SPE) + checkFu(fu) + info.encode());
    }

    /**
     * Host -> AU : TS 지시 (SPE) = ';' + FU(2) + SampleInfo(37) + ChannelCount(3) + TestSelection(37) + Zero(5)  (총 88바이트)
     * Channel count 는 요청 검사가 하나라도 있으면 37 로 보낸다 ("it is preferable to set 37").
     */
    public static byte[] createTestSelectionInstruction(String fu, SampleInfo info, Collection<TestItem> items) {
        char[] flags = new char[TEST_SELECTION_LENGTH];
        java.util.Arrays.fill(flags, '0');
        boolean any = false;
        for (TestItem ti : items) {
            int ch = ti.getChannel();
            if (ch >= 1 && ch <= TEST_SELECTION_LENGTH) {
                flags[ch - 1] = '1';
                any = true;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(FRAME_SPE).append(checkFu(fu)).append(info.encode());
        sb.append(SampleInfo.padLeft(String.valueOf(any ? TEST_SELECTION_LENGTH : 0), CHANNEL_COUNT_LENGTH));
        sb.append(flags);
        sb.append("00000");
        return wrap(sb.toString());
    }

    /** 결과 데이터 텍스트 1개 = frameChar + FU(2) + SampleInfo(37) + ChannelCount(3) + [Ch(3)+Value(6)+Alarm(1)] x N */
    public static byte[] createResultDataFrame(char frameChar, String fu, SampleInfo info, List<TestResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(frameChar).append(checkFu(fu)).append(info.encode());
        sb.append(SampleInfo.padLeft(String.valueOf(results.size()), CHANNEL_COUNT_LENGTH));
        for (TestResult tr : results) {
            sb.append(SampleInfo.padLeft(String.valueOf(tr.getItem().getChannel()), 3));
            sb.append(formatValue6(tr.getValue(), tr.getItem().getDecimalPlaces()));
            sb.append(tr.hasAlarm() ? tr.getAlarmCode().charAt(0) : ' ');
        }
        return wrap(sb.toString());
    }

    /** 결과를 텍스트당 최대 maxPerText 채널로 나눠 FR1 / FR2 / END 순서의 프레임 목록을 생성 (15.1.5 (1) 5)) */
    public static List<byte[]> createResultFrames(String fu, SampleInfo info, List<TestResult> results, int maxPerText) {
        List<byte[]> frames = new ArrayList<>();
        int total = results.size();
        int chunks = Math.max(1, (total + maxPerText - 1) / maxPerText);
        for (int i = 0; i < chunks; i++) {
            List<TestResult> part = results.subList(Math.min(total, i * maxPerText), Math.min(total, (i + 1) * maxPerText));
            char fc;
            if (i == chunks - 1) fc = FRAME_END;
            else if (i == 0) fc = FRAME_FR1;
            else fc = FRAME_FR2;
            frames.add(createResultDataFrame(fc, fu, info, part));
        }
        return frames;
    }

    /** 6자리 우측 정렬 값. 자리수가 넘치면 소수 자리를 줄이고, 그래도 안 되면 예외 (조용히 자르지 않는다) */
    public static String formatValue6(double v, int decimals) {
        if (Double.isNaN(v)) return "      ";
        for (int d = decimals; d >= 0; d--) {
            String s = String.format(Locale.ROOT, "%." + d + "f", v);
            if (s.length() <= 6) return SampleInfo.padLeft(s, 6);
        }
        throw new IllegalArgumentException("측정값이 6자리를 초과합니다: " + v);
    }

    private static String checkFu(String fu) {
        if (fu == null || fu.length() != 2) throw new IllegalArgumentException("Function Character 는 2바이트여야 합니다: " + fu);
        return fu;
    }

    // ------------------------------------------------------------------ 디코더

    /** 파싱 결과 컨테이너 */
    public static final class ParsedFrame {
        public final char frameChar;
        public final String functionCode;      // 제어 프레임이면 ""
        public final SampleKind kind;
        public final SampleInfo sample;        // 없으면 null (제어 프레임, Calibration)
        public final int channelCount;
        public final List<TestResult> results; // 결과 프레임에서만 채워짐
        public final List<Integer> ignoredChannels; // 앱에 정의되지 않은 채널(ISE, 계산항목 등)
        public final List<String> warnings;
        public final String testSelection;     // TS 지시(SPE) 일 때 37자리 요청 플래그, 아니면 null
        public final int calibrationChannel;   // Calibration(G1/G2)일 때 채널 번호, 아니면 0

        ParsedFrame(char frameChar, String fu, SampleKind kind, SampleInfo sample, int channelCount,
                    List<TestResult> results, List<Integer> ignoredChannels, List<String> warnings,
                    String testSelection, int calibrationChannel) {
            this.frameChar = frameChar;
            this.functionCode = fu;
            this.kind = kind;
            this.sample = sample;
            this.channelCount = channelCount;
            this.results = results;
            this.ignoredChannels = ignoredChannels;
            this.warnings = warnings;
            this.testSelection = testSelection;
            this.calibrationChannel = calibrationChannel;
        }

        public boolean isResultText() {
            return frameChar == FRAME_FR1 || frameChar == FRAME_FR2 || frameChar == FRAME_END;
        }

        public boolean isControlFrame() {
            return frameChar == FRAME_ANY || frameChar == FRAME_MOR || frameChar == FRAME_REP;
        }

        public boolean isTestSelectionDirective() {
            return frameChar == FRAME_SPE && testSelection != null;
        }
    }

    public static SampleKind kindOf(String fu) {
        if (fu == null || fu.isEmpty()) return SampleKind.UNKNOWN;
        switch (fu.charAt(0)) {
            case 'A': case 'a': case 'N': case 'n': return SampleKind.ROUTINE;
            case 'D': case 'd': case 'Q': case 'q': return SampleKind.STAT;
            case 'F': case 'f': return SampleKind.CONTROL;
            case 'G': case 'g': case 'H': case 'h': return SampleKind.CALIBRATION;
            case 'I': case 'i': case 'K': case 'k': case 'M': case 'm': case 'S': case 's': return SampleKind.ABSORBANCE;
            default: return SampleKind.UNKNOWN;
        }
    }

    /** FU 가 ID 모드(바코드 리더 사용) 형식인지: A/a/D/d */
    public static boolean isIdModeFu(String fu) {
        if (fu == null || fu.isEmpty()) return false;
        char c = fu.charAt(0);
        return c == 'A' || c == 'a' || c == 'D' || c == 'd';
    }

    /**
     * 프레임 디코딩. BCC/구조/문자 범위 오류는 FrameFormatException (-> 호스트는 REP 로 응답).
     * 개별 채널 값의 형식 오류는 값 없음(NaN) + 경고로 처리하여 전체 텍스트를 버리지 않는다.
     */
    public static ParsedFrame parse(byte[] frame) {
        if (frame == null || frame.length < 4) throw new FrameFormatException("프레임이 너무 짧습니다");
        if (frame[0] != STX) throw new FrameFormatException("STX 없음");
        if (!hasValidBcc(frame)) throw new FrameFormatException("BCC/ETX 구조 불일치");

        int etx = frame.length - 2;
        for (int i = 1; i < etx; i++) {
            int b = frame[i] & 0xFF;
            if (b < 0x20 || b > 0x7E) throw new FrameFormatException("허용되지 않은 문자 (0x" + Integer.toHexString(b) + ")");
        }
        String c = new String(frame, 1, etx - 1, StandardCharsets.US_ASCII);
        if (c.isEmpty()) throw new FrameFormatException("프레임 문자 없음");

        char fc = c.charAt(0);
        List<TestResult> results = new ArrayList<>();
        List<Integer> ignored = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        switch (fc) {
            case FRAME_ANY:
            case FRAME_MOR:
            case FRAME_REP:
                if (c.length() != 1) throw new FrameFormatException("제어 프레임에 데이터 필드가 있습니다");
                return new ParsedFrame(fc, "", SampleKind.UNKNOWN, null, 0, results, ignored, warnings, null, 0);

            case FRAME_RES: {
                if (c.length() < 40) throw new FrameFormatException("RES 길이 오류");
                String fu = c.substring(1, 3);
                return new ParsedFrame(fc, fu, kindOf(fu), SampleInfo.parse(c.substring(3, 40)), 0, results, ignored, warnings, null, 0);
            }

            case FRAME_SPE: {
                if (c.length() < 40) throw new FrameFormatException("SPE 길이 오류 (최소 43바이트)");
                String fu = c.substring(1, 3);
                SampleKind kind = kindOf(fu);
                if (kind != SampleKind.ROUTINE && kind != SampleKind.STAT) {
                    throw new FrameFormatException("SPE 의 Function Character 오류: '" + fu + "'");
                }
                SampleInfo info = SampleInfo.parse(c.substring(3, 40));
                if (c.length() < 85) {
                    return new ParsedFrame(fc, fu, kind, info, 0, results, ignored, warnings, null, 0);  // AU 의 TS 문의
                }
                int chCount = parseIntStrict(c.substring(40, 43), "Channel count");
                String sel = c.substring(43, 80);
                for (int i = 0; i < sel.length(); i++) {
                    if (sel.charAt(i) != '0' && sel.charAt(i) != '1') throw new FrameFormatException("TS 요청 값은 0/1 이어야 합니다");
                }
                return new ParsedFrame(fc, fu, kind, info, chCount, results, ignored, warnings, sel, 0);
            }

            case FRAME_FR1:
            case FRAME_FR2:
            case FRAME_END: {
                if (c.length() < 3) throw new FrameFormatException("결과 프레임 길이 오류");
                String fu = c.substring(1, 3);
                SampleKind kind = kindOf(fu);
                if (kind == SampleKind.UNKNOWN) throw new FrameFormatException("알 수 없는 Function Character: '" + fu + "'");

                if (kind == SampleKind.CALIBRATION) {
                    // G1/G2/Hb 텍스트는 Sample Information 이 없음. 채널 번호(3자리)만 참고용으로 읽는다.
                    int ch = 0;
                    if (c.length() >= 6) {
                        try { ch = Integer.parseInt(c.substring(3, 6).trim()); } catch (NumberFormatException ignore) { }
                    }
                    return new ParsedFrame(fc, fu, kind, null, 0, results, ignored, warnings, null, ch);
                }

                if (c.length() < 43) throw new FrameFormatException("결과 프레임 길이 오류 (최소 43자)");
                SampleInfo info = SampleInfo.parse(c.substring(3, 40));
                int chCount = parseIntStrict(c.substring(40, 43), "Channel count");
                if (kind == SampleKind.ABSORBANCE) {
                    return new ParsedFrame(fc, fu, kind, info, chCount, results, ignored, warnings, null, 0);
                }
                if (chCount < 0 || chCount > 50) throw new FrameFormatException("Channel count 범위 오류: " + chCount);
                int need = 43 + RESULT_RECORD_LENGTH * chCount;
                if (c.length() < need) throw new FrameFormatException("결과 데이터 길이 부족 (필요 " + need + ", 수신 " + c.length() + ")");
                if (c.length() > need) warnings.add("결과 데이터 뒤에 " + (c.length() - need) + "자가 더 있습니다 (무시)");

                int off = 43;
                for (int i = 0; i < chCount; i++, off += RESULT_RECORD_LENGTH) {
                    int ch = parseIntStrict(c.substring(off, off + 3), "채널 번호");
                    String valStr = c.substring(off + 3, off + 9);
                    char alarmChar = c.charAt(off + 9);
                    TestItem item = TestItem.fromChannel(ch).orElse(null);
                    if (item == null) {
                        ignored.add(ch);
                        continue;
                    }
                    double val;
                    if (valStr.isBlank()) {
                        val = Double.NaN;
                    } else {
                        try {
                            val = Double.parseDouble(valStr.trim());
                        } catch (NumberFormatException e) {
                            val = Double.NaN;
                            warnings.add("채널 " + ch + " 값 형식 오류: '" + valStr + "'");
                        }
                    }
                    String alarm = (alarmChar == ' ') ? "" : String.valueOf(alarmChar);
                    results.add(new TestResult(item, val, item.evaluateFlag(val), alarm));
                }
                return new ParsedFrame(fc, fu, kind, info, chCount, results, ignored, warnings, null, 0);
            }

            default:
                throw new FrameFormatException("부적절한 프레임 문자: '" + fc + "'");
        }
    }

    private static int parseIntStrict(String s, String what) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new FrameFormatException(what + " 형식 오류: '" + s + "'");
        }
    }

    // ------------------------------------------------------------------ 트레이스용 요약

    public static String summarize(byte[] frame) {
        try {
            ParsedFrame p = parse(frame);
            switch (p.frameChar) {
                case FRAME_ANY: return "ANY (AU 대기)";
                case FRAME_MOR: return "MOR (Host 대기)";
                case FRAME_REP: return "REP (재전송 요청)";
                case FRAME_RES: return "RES (결과 요청)";
                case FRAME_SPE:
                    return p.isTestSelectionDirective()
                            ? "SPE TS 지시 [" + p.functionCode.trim() + "] " + p.sample
                            : "SPE TS 문의 [" + p.functionCode.trim() + "] " + p.sample;
                default: {
                    String name = p.frameChar == FRAME_FR1 ? "FR1" : p.frameChar == FRAME_FR2 ? "FR2" : "END";
                    if (p.kind == SampleKind.CALIBRATION) return name + " Calibration [" + p.functionCode.trim() + "] Ch " + p.calibrationChannel;
                    if (p.kind == SampleKind.ABSORBANCE) return name + " 흡광도 데이터 [" + p.functionCode.trim() + "]";
                    return name + " 결과 [" + p.functionCode.trim() + "] " + p.sample + " " + p.results.size() + "항목";
                }
            }
        } catch (FrameFormatException e) {
            return "INVALID: " + e.getMessage();
        }
    }
}
