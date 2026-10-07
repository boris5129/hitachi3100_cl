package com.hitachi3100;

import com.hitachi3100.model.*;
import com.hitachi3100.protocol.*;
import com.hitachi3100.service.*;
import com.hitachi3100.util.CsvAppender;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static com.hitachi3100.protocol.Hitachi3100Constants.*;

/**
 * 외부 라이브러리 없이 실행되는 통합 테스트.  실행: java -cp bin com.hitachi3100.ProtocolAndSystemTest
 * (데이터 파일은 임시 폴더에만 생성된다)
 */
public class ProtocolAndSystemTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("h3100test");
        System.setProperty("hitachi.data.dir", tmp.toString());
        System.setProperty("hitachi.log.dir", Files.createTempDirectory("h3100log").toString());

        run("BCC / 제어 프레임", ProtocolAndSystemTest::testBccAndControl);
        run("TS 지시(SPE) 프레임 규격 (88바이트)", ProtocolAndSystemTest::testDirectiveLayout);
        run("TS 문의(SPE) 프레임 규격 (43바이트)", ProtocolAndSystemTest::testInquiryLayout);
        run("결과 프레임 왕복 + 알람 H/L 은 High/Low 가 아님", ProtocolAndSystemTest::testResultRoundTrip);
        run("결과 프레임: 음수/값 없음/6자리 초과/미정의 채널", ProtocolAndSystemTest::testResultEdgeCases);
        run("비정상 프레임 거부 (BCC, 길이, 문자 범위, FU)", ProtocolAndSystemTest::testRejectInvalid);
        run("FrameAssembler: 분할/연속/잡음/재동기화", ProtocolAndSystemTest::testAssembler);
        run("Function Character 분류 (ID 에 CAL/QC 포함돼도 환자)", ProtocolAndSystemTest::testFunctionCodes);
        run("오더 검증 (범위/길이/중복)", ProtocolAndSystemTest::testOrderValidation);
        run("ID 자동 증가", ProtocolAndSystemTest::testIncrementId);
        run("핸들러: ANY->MOR, 100ms 지연, 잘못된 BCC->REP, REP 수신 시 재전송",
                ProtocolAndSystemTest::testHandlerBasics);
        run("핸들러: 결과 중복 재수신 무시 + 다중 텍스트(FR1/END) 조립", ProtocolAndSystemTest::testHandlerDuplicateAndMultiText);
        run("종단 간: Batch 오더 -> 결과 -> 이력/시약/상태", ProtocolAndSystemTest::testEndToEndBatch);
        run("종단 간: 실시간 TS 문의 (Stat)", ProtocolAndSystemTest::testEndToEndInquiryStat);
        run("종단 간: Control 샘플은 FU 로 QC 분류, 환자 이력에 안 들어감", ProtocolAndSystemTest::testControlSample);
        run("서비스: 이력 재시작 후 최신순, 시약 원자적 저장, 로케일 독립", ProtocolAndSystemTest::testPersistence);
        run("QC 기준값 검증 / 바코드 파싱", ProtocolAndSystemTest::testQcValidation);
        run("CSV: 쉼표/따옴표가 든 ID 왕복, 손상된 줄 무시", ProtocolAndSystemTest::testCsvRoundTrip);
        run("파일 로그: 날짜별 파일, 통신 스레드를 막지 않음", ProtocolAndSystemTest::testAppLog);
        run("느린 디스크(3초)에서도 MOR 응답이 늦어지지 않음", ProtocolAndSystemTest::testSlowDiskDoesNotDelayMor);
        run("파일 잠김: .pending 보관 -> 잠금 해제 시 병합, 유실/중복 없음", ProtocolAndSystemTest::testFileLockFallbackAndMerge);
        run("병합 도중 종료(본 파일+pending 중복)에서도 중복 없음", ProtocolAndSystemTest::testMergeCrashNoDuplicates);
        run("시약 파일 저장 실패 시 메모리 유지 + 재시도 후 저장", ProtocolAndSystemTest::testReagentSaveRetry);
        run("지연 로딩: 최근 N일만 메모리, 과거는 기간 검색", ProtocolAndSystemTest::testLazyLoadAndArchiveSearch);
        run("메모리 상한(20,000건) 및 QC 최근 기간 로딩", ProtocolAndSystemTest::testMemoryCapAndQcWindow);

        System.out.println("\n결과: 통과 " + passed + " / 실패 " + failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ 도구

    interface T { void run() throws Exception; }

    static void run(String name, T t) {
        try {
            t.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("[FAIL] " + name + " -> " + e);
            e.printStackTrace(System.out);
        }
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void expectThrows(Class<? extends Throwable> c, T t, String msg) {
        try {
            t.run();
        } catch (Throwable e) {
            if (c.isInstance(e)) return;
            throw new AssertionError(msg + " (다른 예외: " + e + ")");
        }
        throw new AssertionError(msg + " (예외가 발생하지 않음)");
    }

    static boolean waitUntil(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return cond.getAsBoolean();
    }

    static Path freshDataDir() throws Exception {
        Path d = Files.createTempDirectory("h3100data");
        System.setProperty("hitachi.data.dir", d.toString());
        return d;
    }

    /** 송신된 프레임만 기록하는 가짜 채널 */
    static class FakeChannel implements IHitachiChannel {
        final List<HitachiChannelListener> ls = new CopyOnWriteArrayList<>();
        final List<byte[]> sent = new CopyOnWriteArrayList<>();
        final List<Long> sentAt = new CopyOnWriteArrayList<>();
        volatile boolean connected = true;
        public void connect() { connected = true; }
        public void disconnect() { connected = false; }
        public boolean isConnected() { return connected; }
        public boolean sendFrame(byte[] f) { sent.add(f); sentAt.add(System.currentTimeMillis()); return true; }
        public void addListener(HitachiChannelListener l) { ls.add(l); }
        public void removeListener(HitachiChannelListener l) { ls.remove(l); }
        public String getChannelName() { return "fake"; }
        void fromAu(byte[] f) { for (HitachiChannelListener l : ls) l.onFrameReceived(f); }
        char lastChar() {
            byte[] f = sent.get(sent.size() - 1);
            return (char) f[1];
        }
    }

    static List<TestResult> results(Object... itemValuePairs) {
        List<TestResult> r = new ArrayList<>();
        for (int i = 0; i < itemValuePairs.length; i += 2) {
            TestItem it = (TestItem) itemValuePairs[i];
            double v = ((Number) itemValuePairs[i + 1]).doubleValue();
            r.add(new TestResult(it, v, null, ""));
        }
        return r;
    }

    // ------------------------------------------------------------------ 프레임 단위 테스트

    static void testBccAndControl() {
        byte[] any = Hitachi3100Frame.createControlFrame(FRAME_ANY);
        check(any.length == 4, "제어 프레임은 4바이트");
        check(any[0] == STX && any[1] == '>' && any[2] == ETX, "STX > ETX");
        check(any[3] == (byte) ('>' ^ ETX), "BCC = '>' XOR ETX");
        check(Hitachi3100Frame.hasValidBcc(any), "유효한 BCC");
        any[3] ^= 0x10;
        check(!Hitachi3100Frame.hasValidBcc(any), "변조된 BCC 는 무효");
        check(Hitachi3100Frame.createControlFrame(FRAME_MOR)[1] == ' ', "MOR 은 공백 문자");
        check(Hitachi3100Frame.createControlFrame(FRAME_REP)[1] == '?', "REP 은 ?");
    }

    static void testDirectiveLayout() {
        SampleInfo info = SampleInfo.forDirective(0, ' ', 0, "PAT-1001");
        byte[] f = Hitachi3100Frame.createTestSelectionInstruction(FU_ROUTINE_ID, info, List.of(TestItem.AST, TestItem.ALT));
        check(f.length == 88, "SPE 지시는 88바이트 (실제 " + f.length + ")");
        check(f[1] == ';', "프레임 문자 ;");
        String c = new String(f, 1, f.length - 3);
        check(c.substring(1, 3).equals("A "), "FU = 'A '");
        check(c.substring(3, 40).length() == 37, "Sample info 37");
        check(c.substring(3, 8).isBlank(), "ID 모드 Sample No. 공백");
        check(c.substring(9, 12).isBlank(), "Batch 지시 Position 공백");
        check(c.substring(3 + 9, 3 + 22).trim().equals("PAT-1001"), "ID 필드 우측 정렬");
        check(c.substring(40, 43).equals(" 37"), "Channel count = ' 37'");
        String sel = c.substring(43, 80);
        check(sel.charAt(TestItem.AST.getChannel() - 1) == '1' && sel.charAt(TestItem.ALT.getChannel() - 1) == '1', "요청 채널 플래그");
        check(sel.chars().filter(ch -> ch == '1').count() == 2, "요청하지 않은 채널은 0");
        check(c.substring(80, 85).equals("00000"), "끝 Zero(5)");
        check(Hitachi3100Frame.hasValidBcc(f), "BCC");
        Hitachi3100Frame.ParsedFrame p = Hitachi3100Frame.parse(f);
        check(p.isTestSelectionDirective() && p.sample.getId().equals("PAT-1001"), "지시 파싱");
    }

    static void testInquiryLayout() {
        byte[] f = Hitachi3100Frame.createTestSelectionInquiry(FU_STAT_ID, SampleInfo.forResult(0, 3, "S-9"));
        check(f.length == 43, "SPE 문의는 43바이트 (실제 " + f.length + ")");
        Hitachi3100Frame.ParsedFrame p = Hitachi3100Frame.parse(f);
        check(!p.isTestSelectionDirective() && p.kind == Hitachi3100Frame.SampleKind.STAT, "문의로 파싱");
        check(p.sample.getPositionNumber() == 3 && p.sample.getId().equals("S-9"), "Position/ID");
    }

    static void testResultRoundTrip() {
        SampleInfo info = SampleInfo.forResult(0, 5, "PAT-77");
        List<TestResult> in = new ArrayList<>();
        in.add(new TestResult(TestItem.AST, 35.0, null, ""));
        in.add(new TestResult(TestItem.ALT, 12.5, null, "H"));     // 알람 'H' = Standard 1 absorbance abnormal
        byte[] f = Hitachi3100Frame.createResultDataFrame(FRAME_END, FU_RESULT_ROUTINE_ID, info, in);
        Hitachi3100Frame.ParsedFrame p = Hitachi3100Frame.parse(f);
        check(p.frameChar == FRAME_END && p.kind == Hitachi3100Frame.SampleKind.ROUTINE, "END/ROUTINE");
        check(p.results.size() == 2, "2항목");
        TestResult ast = p.results.get(0), alt = p.results.get(1);
        check(Math.abs(ast.getValue() - 35.0) < 1e-9 && !ast.hasAlarm(), "AST 값/알람없음");
        check(alt.getAlarmCode().equals("H"), "알람 문자 보존");
        check(!"H".equals(alt.getFlag()) || alt.getValue() > TestItem.ALT.getRefHigh(),
                "알람 H 가 High 플래그로 해석되면 안 됨 (값=" + alt.getValue() + ", flag=" + alt.getFlag() + ")");
        check(alt.getAlarmDescription().contains("Standard 1"), "알람 설명");
        check(p.results.get(0).getFlag().equals(TestItem.AST.evaluateFlag(35.0)), "Flag 는 참고치로 호스트가 판정");
    }

    static void testResultEdgeCases() {
        SampleInfo info = SampleInfo.forResult(0, 1, "X1");
        List<TestResult> in = new ArrayList<>();
        in.add(new TestResult(TestItem.GLUCOSE, Double.NaN, null, "V"));   // 값 공백 + 검체량 부족
        in.add(new TestResult(TestItem.AST, 123456.0, null, ""));
        in.add(new TestResult(TestItem.ALT, -12.34, null, ""));
        Hitachi3100Frame.ParsedFrame p = Hitachi3100Frame.parse(
                Hitachi3100Frame.createResultDataFrame(FRAME_END, FU_RESULT_ROUTINE_ID, info, in));
        check(!p.results.get(0).hasValue() && p.results.get(0).getAlarmCode().equals("V"), "값 없음 + 알람 V");
        check(Math.abs(p.results.get(1).getValue() - 123456.0) < 1e-9, "6자리 정수");
        check(Math.abs(p.results.get(2).getValue() + 12.34) < 1e-9 || Math.abs(p.results.get(2).getValue() + 12.3) < 0.06, "음수");
        expectThrows(IllegalArgumentException.class, () -> Hitachi3100Frame.formatValue6(1234567.0, 1), "7자리는 잘못된 값");
        check(Hitachi3100Frame.formatValue6(1234.567, 3).equals("1234.6"), "소수 자리를 줄여 6자리에 맞춤");

        // 정의되지 않은 채널(ISE 38) 은 무시하되 프레임은 정상 처리
        StringBuilder sb = new StringBuilder();
        sb.append(FRAME_END).append("a ").append(info.encode()).append("  2");
        sb.append("  1  35.0 ").append(" 38 140.0 ");
        Hitachi3100Frame.ParsedFrame q = Hitachi3100Frame.parse(Hitachi3100Frame.wrap(sb.toString()));
        check(q.results.size() == 1 && q.ignoredChannels.contains(38), "ISE 채널 무시: " + q.ignoredChannels);
    }

    static void testRejectInvalid() {
        SampleInfo info = SampleInfo.forResult(0, 1, "X1");
        byte[] good = Hitachi3100Frame.createResultDataFrame(FRAME_END, FU_RESULT_ROUTINE_ID, info, results(TestItem.AST, 30));
        byte[] badBcc = good.clone();
        badBcc[badBcc.length - 1] ^= 0x01;
        expectThrows(FrameFormatException.class, () -> Hitachi3100Frame.parse(badBcc), "BCC 불일치");

        // 채널 수는 2 인데 데이터는 1개 분량 -> 길이 부족
        String c = new String(good, 1, good.length - 3).replace("  1", "  2");
        expectThrows(FrameFormatException.class, () -> Hitachi3100Frame.parse(Hitachi3100Frame.wrap(c)), "채널 수와 길이 불일치");
        expectThrows(FrameFormatException.class, () -> Hitachi3100Frame.parse(Hitachi3100Frame.wrap("Z")), "부적절한 프레임 문자");
        expectThrows(FrameFormatException.class,
                () -> Hitachi3100Frame.parse(Hitachi3100Frame.wrap(":x " + info.encode() + "  0")), "알 수 없는 FU");
        byte[] ctrl = good.clone();
        ctrl[5] = 0x07;   // 제어문자 삽입
        expectThrows(FrameFormatException.class, () -> Hitachi3100Frame.parse(ctrl), "허용되지 않은 문자");
        expectThrows(FrameFormatException.class, () -> Hitachi3100Frame.parse(new byte[]{STX, ETX}), "너무 짧음");
    }

    static void testAssembler() {
        byte[] a = Hitachi3100Frame.createControlFrame(FRAME_ANY);
        byte[] b = Hitachi3100Frame.createResultDataFrame(FRAME_END, FU_RESULT_ROUTINE_ID, SampleInfo.forResult(0, 1, "ID1"), results(TestItem.AST, 30));
        FrameAssembler as = new FrameAssembler();

        // 1바이트씩 분할 도착
        List<byte[]> out = new ArrayList<>();
        for (byte x : b) out.addAll(as.feed(new byte[]{x}, 1));
        check(out.size() == 1 && Arrays.equals(out.get(0), b), "분할 수신 조립");

        // 두 프레임이 붙어서 도착 + 앞쪽 잡음
        byte[] glued = new byte[3 + a.length + b.length];
        glued[0] = 'x'; glued[1] = 'y'; glued[2] = 'z';
        System.arraycopy(a, 0, glued, 3, a.length);
        System.arraycopy(b, 0, glued, 3 + a.length, b.length);
        out = as.feed(glued, glued.length);
        check(out.size() == 2 && Arrays.equals(out.get(0), a) && Arrays.equals(out.get(1), b), "연속 프레임 분리");
        check(as.getDiscardedBytes() >= 3, "잡음 폐기 집계");

        // 프레임 도중 STX 재등장 -> 재동기화
        byte[] half = Arrays.copyOf(b, 20);
        byte[] mix = new byte[half.length + a.length];
        System.arraycopy(half, 0, mix, 0, half.length);
        System.arraycopy(a, 0, mix, half.length, a.length);
        out = as.feed(mix, mix.length);
        check(out.size() == 1 && Arrays.equals(out.get(0), a) && as.getResyncCount() == 1, "재동기화");

        // BCC 가 ETX(0x03) 와 같은 값이어도 프레임 종료 처리
        out = as.feed(new byte[]{STX, 'A', ETX, 0x03}, 4);
        check(out.size() == 1 && out.get(0).length == 4, "BCC 바이트 처리");

        // 7비트 통신에서 패리티 비트가 섞여 들어와도 STX/ETX 인식
        out = as.feed(new byte[]{(byte) (STX | 0x80), '>', (byte) (ETX | 0x80), (byte) ('>' ^ ETX)}, 4);
        check(out.size() == 1, "패리티 비트 마스크");
    }

    static void testFunctionCodes() {
        check(Hitachi3100Frame.kindOf("a ") == Hitachi3100Frame.SampleKind.ROUTINE, "a -> routine");
        check(Hitachi3100Frame.kindOf("D ") == Hitachi3100Frame.SampleKind.STAT, "D -> stat");
        check(Hitachi3100Frame.kindOf("f ") == Hitachi3100Frame.SampleKind.CONTROL, "f -> control");
        check(Hitachi3100Frame.kindOf("G1") == Hitachi3100Frame.SampleKind.CALIBRATION, "G1 -> calibration");
        check(Hitachi3100Frame.kindOf("Hb") == Hitachi3100Frame.SampleKind.CALIBRATION, "Hb -> ISE calibration");
        check(Hitachi3100Frame.kindOf("i ") == Hitachi3100Frame.SampleKind.ABSORBANCE, "i -> absorbance");
        check(Hitachi3100Frame.kindOf("z ") == Hitachi3100Frame.SampleKind.UNKNOWN, "z -> unknown");
        SampleInfo c = SampleInfo.forControl(2, 7);
        check(c.getControlNo() == 2 && c.getAnalyzeCount() == 7, "Control No./Analyze count 해석");
    }

    static void testOrderValidation() throws Exception {
        freshDataDir();
        OrderService os = new OrderService(new QCService(), new ReagentService(), new HistoryService());
        List<TestItem> items = List.of(TestItem.AST);
        expectThrows(IllegalArgumentException.class, () -> os.addOrder(0, "A1", items), "Position 0");
        expectThrows(IllegalArgumentException.class, () -> os.addOrder(MAX_POSITION + 1, "A1", items), "Position 초과");
        expectThrows(IllegalArgumentException.class, () -> os.addOrder(1, "", items), "빈 ID");
        expectThrows(IllegalArgumentException.class, () -> os.addOrder(1, "ABCDEFGHIJKLMN", items), "ID 14자");
        expectThrows(IllegalArgumentException.class, () -> os.addOrder(1, "한글ID", items), "비 ASCII ID");
        expectThrows(IllegalArgumentException.class, () -> os.addOrder(1, "A1", List.of()), "항목 없음");
        os.addOrder(1, "LOCAL-01", items);   // 'CAL' 포함 ID 도 환자 오더로 허용
        expectThrows(IllegalArgumentException.class, () -> os.addOrder(1, "B1", items), "Position 중복");
        expectThrows(IllegalArgumentException.class, () -> os.addOrder(2, "local-01", items), "ID 중복(대소문자 무시)");
        check(os.getCurrentDiskPosition() == 2 && os.getNextPatientId().equals("LOCAL-02"), "자동 증가");
        os.addOrder(MAX_POSITION, "Z9", items);
        check(os.getCurrentDiskPosition() == 1, "Position 순환");
        expectThrows(IllegalStateException.class, os::transmitPendingOrders, "연결 없이 전송");
        os.shutdown();
    }

    static void testIncrementId() {
        check(OrderService.incrementIdString("PAT-1001").equals("PAT-1002"), "PAT");
        check(OrderService.incrementIdString("PAT-0099").equals("PAT-0100"), "자리수 유지");
        check(OrderService.incrementIdString("100").equals("101"), "숫자만");
        check(OrderService.incrementIdString("ABC").equals("ABC-1"), "숫자 없음");
    }

    // ------------------------------------------------------------------ 핸들러 테스트

    static void testHandlerBasics() throws Exception {
        freshDataDir();
        OrderService os = new OrderService(new QCService(), new ReagentService(), new HistoryService());
        FakeChannel ch = new FakeChannel();
        os.setCommunicationChannel(ch);

        long t0 = System.currentTimeMillis();
        ch.fromAu(Hitachi3100Frame.createControlFrame(FRAME_ANY));
        check(waitUntil(() -> ch.sent.size() == 1, 1500), "ANY 에 응답해야 함");
        check(ch.lastChar() == ' ', "오더가 없으면 MOR");
        check(ch.sentAt.get(0) - t0 >= 100, "응답은 수신 후 100ms 이상 지연 (실제 " + (ch.sentAt.get(0) - t0) + "ms)");

        // BCC 오류 -> REP
        byte[] bad = Hitachi3100Frame.createControlFrame(FRAME_ANY);
        bad[3] ^= 0x7F;
        ch.fromAu(bad);
        check(waitUntil(() -> ch.sent.size() == 2, 1500) && ch.lastChar() == '?', "BCC 오류에는 REP");

        // AU 가 REP -> 마지막 송신 텍스트(REP 아닌 정상 텍스트) 재전송 확인: 먼저 정상 텍스트 한 번 더 보냄
        ch.fromAu(Hitachi3100Frame.createControlFrame(FRAME_ANY));
        check(waitUntil(() -> ch.sent.size() == 3, 1500), "정상 응답");
        byte[] lastMor = ch.sent.get(2);
        ch.fromAu(Hitachi3100Frame.createControlFrame(FRAME_REP));
        check(waitUntil(() -> ch.sent.size() == 4, 1500) && Arrays.equals(ch.sent.get(3), lastMor), "REP 수신 시 재전송");

        // Batch 오더 -> ANY 에 SPE 로 응답
        os.addOrder(1, "B-001", List.of(TestItem.AST, TestItem.ALT));
        ch.fromAu(Hitachi3100Frame.createControlFrame(FRAME_ANY));
        check(waitUntil(() -> ch.sent.size() == 5, 1500) && ch.lastChar() == ' ', "전송 요청 전에는 MOR");
        os.transmitPendingOrders();
        ch.fromAu(Hitachi3100Frame.createControlFrame(FRAME_ANY));
        check(waitUntil(() -> ch.sent.size() == 6, 1500) && ch.lastChar() == ';', "Batch 전송 요청 후에는 SPE");
        check(os.getPendingOrders().get(0).getStatus() == Order.OrderStatus.SENT, "상태 SENT");
        ch.fromAu(Hitachi3100Frame.createControlFrame(FRAME_ANY));
        check(waitUntil(() -> ch.sent.size() == 7, 1500) && ch.lastChar() == ' ', "SENT 오더를 다시 보내지 않음");

        // 알 수 없는 ID 에 대한 TS 문의 -> MOR
        ch.fromAu(Hitachi3100Frame.createTestSelectionInquiry(FU_ROUTINE_ID, SampleInfo.forResult(0, 9, "NOPE")));
        check(waitUntil(() -> ch.sent.size() == 8, 1500) && ch.lastChar() == ' ', "없는 오더 문의에는 MOR");
        // ID 공백 문의 -> MOR
        ch.fromAu(Hitachi3100Frame.createTestSelectionInquiry(FU_ROUTINE_ID, SampleInfo.forResult(0, 9, "")));
        check(waitUntil(() -> ch.sent.size() == 9, 1500) && ch.lastChar() == ' ', "ID 공백 문의에는 MOR");
        os.shutdown();
    }

    static void testHandlerDuplicateAndMultiText() throws Exception {
        Path dir = freshDataDir();
        HistoryService hs = new HistoryService();
        ReagentService rs = new ReagentService();
        OrderService os = new OrderService(new QCService(), rs, hs);
        FakeChannel ch = new FakeChannel();
        os.setCommunicationChannel(ch);
        os.addOrder(3, "MULTI-1", List.of(TestItem.AST, TestItem.ALT, TestItem.GGT));
        os.transmitPendingOrders();
        ch.fromAu(Hitachi3100Frame.createControlFrame(FRAME_ANY));
        check(waitUntil(() -> ch.sent.size() == 1, 1500) && ch.lastChar() == ';', "SPE 전송");

        List<TestResult> all = results(TestItem.AST, 30, TestItem.ALT, 25, TestItem.GGT, 40);
        List<byte[]> texts = Hitachi3100Frame.createResultFrames(FU_RESULT_ROUTINE_ID, SampleInfo.forResult(0, 7, "MULTI-1"), all, 2);
        check(texts.size() == 2 && texts.get(0)[1] == '1' && texts.get(1)[1] == ':', "FR1 + END 로 분할");

        double r1Before = reagentTests(rs, TestItem.AST);
        ch.fromAu(texts.get(0));
        check(waitUntil(() -> ch.sent.size() == 2, 1500) && ch.lastChar() == ' ', "FR1 에 MOR");
        check(hs.getAllHistory().isEmpty(), "END 전에는 완료 처리 안 함");
        ch.fromAu(texts.get(1));
        check(waitUntil(() -> ch.sent.size() == 3, 1500) && ch.lastChar() == ' ', "END 에 MOR");
        check(waitUntil(() -> hs.getAllHistory().size() == 1, 1500), "이력 1건");
        check(hs.getAllHistory().get(0).getResults().size() == 3, "3항목 조립");
        check(hs.getAllHistory().get(0).getPatientId().equals("MULTI-1"), "ID 로 매칭");
        check(hs.getAllHistory().get(0).getPosition() == 3, "오더의 Position 유지 (AU 가 배정한 7 이 아님)");

        // AU 가 MOR 을 못 받아 END 를 재전송 -> 중복 처리 금지
        ch.fromAu(texts.get(1));
        check(waitUntil(() -> ch.sent.size() == 4, 1500) && ch.lastChar() == ' ', "중복 END 에도 MOR");
        Thread.sleep(300);
        check(hs.getAllHistory().size() == 1, "중복 END 는 이력에 추가되지 않음");
        check(reagentTests(rs, TestItem.AST) == r1Before - 2, "시약(R1+R2)은 1번만 차감");
        check(os.getPendingOrders().isEmpty(), "완료된 오더는 큐에서 제거");
        os.shutdown();
    }

    static double reagentTests(ReagentService rs, TestItem it) {
        double sum = 0;
        for (ReagentItem r : rs.getAllReagents()) if (r.getItem() == it) sum += r.getCurrentTests();
        return sum;
    }

    // ------------------------------------------------------------------ 종단 간 (시뮬레이터)

    static class Env {
        QCService qc; ReagentService rs; HistoryService hs; OrderService os; Hitachi3100Simulator sim;
        List<String> events = new CopyOnWriteArrayList<>();
        Env() throws Exception {
            freshDataDir();
            qc = new QCService(); rs = new ReagentService(); hs = new HistoryService();
            os = new OrderService(qc, rs, hs);
            os.addEventListener(events::add);
            sim = new Hitachi3100Simulator();
            sim.setCycleMs(40);
            sim.setAnalysisDelayMs(100);
            os.setCommunicationChannel(sim);
            sim.connect();
        }
        void close() { sim.disconnect(); os.shutdown(); }
    }

    static void testEndToEndBatch() throws Exception {
        Env e = new Env();
        try {
            e.os.addOrder(1, "PAT-1001", List.of(TestItem.AST, TestItem.ALT, TestItem.CREA));
            e.os.addOrder(2, "PAT-1002", List.of(TestItem.GLUCOSE));
            check(e.os.transmitPendingOrders() == 2, "Batch 대기열 2건");
            check(waitUntil(() -> e.hs.getAllHistory().size() == 2, 10000), "결과 2건 수신 (이벤트: " + e.events + ")");
            check(e.os.getPendingOrders().isEmpty(), "큐 비워짐");
            check(e.sim.getReceivedDirectives().size() == 2, "AU 가 TS 지시 2건 수신: " + e.sim.getReceivedDirectives());
            check(e.sim.getReceivedDirectives().get(0).startsWith("A|PAT-1001|3"), "첫 지시 형식: " + e.sim.getReceivedDirectives());
            PatientRecord rec = e.hs.getAllHistory().stream().filter(r -> r.getPatientId().equals("PAT-1001")).findFirst().orElseThrow();
            check(rec.getResults().size() == 3, "요청한 3항목만 결과 수신");
            check(e.qc.getAllRecords().isEmpty(), "환자 결과는 QC 에 기록되지 않음");
        } finally {
            e.close();
        }
    }

    static void testEndToEndInquiryStat() throws Exception {
        Env e = new Env();
        try {
            e.os.addOrder(5, "STAT-001", List.of(TestItem.GLUCOSE, TestItem.BUN), true);
            check(e.os.transmitPendingOrders() == 0, "Stat 은 Batch 대상이 아님");
            Thread.sleep(400);
            check(e.sim.getReceivedDirectives().isEmpty(), "AU 문의 전에는 Stat TS 를 보내지 않음");
            e.sim.requestTestSelection(5, "STAT-001", true);
            check(waitUntil(() -> e.hs.getAllHistory().size() == 1, 10000), "실시간 문의 -> 결과 (이벤트: " + e.events + ")");
            check(e.sim.getReceivedDirectives().get(0).startsWith("D|STAT-001|2"), "지시 FU 'D ': " + e.sim.getReceivedDirectives());
            check(e.hs.getAllHistory().get(0).getPatientId().equals("STAT-001"), "ID");
        } finally {
            e.close();
        }
    }

    static void testControlSample() throws Exception {
        Env e = new Env();
        try {
            // 환자 ID 에 QC1/CAL 이 들어 있어도 환자로 처리
            e.os.addOrder(1, "QC1-CAL-7", List.of(TestItem.AST));
            e.os.transmitPendingOrders();
            e.sim.injectControlSample(2, List.of(TestItem.AST, TestItem.ALT));
            check(waitUntil(() -> e.hs.getAllHistory().size() == 1 && e.qc.getAllRecords().size() == 2, 10000),
                    "환자 1 + QC 2 (이벤트: " + e.events + ")");
            check(e.qc.getAllRecords().stream().allMatch(r -> r.getQcLevel().equals("QC2")), "Control No.2 -> QC2");
            check(e.hs.getAllHistory().get(0).getPatientId().equals("QC1-CAL-7"), "ID 에 QC/CAL 이 있어도 환자 이력");
            check(e.hs.getAllHistory().size() == 1, "Control 결과는 환자 이력에 없음");
        } finally {
            e.close();
        }
    }

    // ------------------------------------------------------------------ 서비스

    static void testPersistence() throws Exception {
        Path dir = freshDataDir();
        HistoryService h1 = new HistoryService();
        h1.recordPatientResult(1, "OLD", "AST", results(TestItem.AST, 30));
        Thread.sleep(1100);   // 시각 해상도(초)
        h1.recordPatientResult(2, "NEW", "AST", results(TestItem.AST, 31));
        check(h1.getAllHistory().get(0).getPatientId().equals("NEW"), "실행 중: 최신 우선");
        check(h1.flush(3000), "저장 대기열 비움");
        HistoryService h2 = new HistoryService();
        check(h2.getAllHistory().get(0).getPatientId().equals("NEW"), "재시작 후에도 최신 우선");
        h2.recordPatientResult(3, "NEWER", "AST", results(TestItem.AST, 32));
        check(h2.getAllHistory().get(0).getPatientId().equals("NEWER"), "재시작 후 새 기록이 맨 앞");
        h2.flush(3000);

        ReagentService r1 = new ReagentService();
        double before = reagentTests(r1, TestItem.AST);
        r1.deductReagents(List.of(TestItem.AST));
        check(r1.flush(3000), "시약 저장 완료");
        try (var s = Files.list(dir)) {
            check(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "임시 파일이 남지 않음");
        }
        check(reagentTests(new ReagentService(), TestItem.AST) == before - 2, "시약(R1+R2) 저장/재로드");

        // 소수점이 쉼표인 로케일에서도 CSV/프레임이 깨지지 않음
        Locale old = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            QCRecord rec = new QCRecord(java.time.LocalDateTime.now(), "QC1", TestItem.AST, 30.5, 30, 20, 40);
            check(rec.toCsvLine().split(",", -1).length == 8, "QC CSV 8열 유지: " + rec.toCsvLine());
            check(TestItem.AST.formatValue(30.5).equals("30.5"), "값 포맷은 점(.) 사용");
            byte[] f = Hitachi3100Frame.createResultDataFrame(FRAME_END, FU_RESULT_ROUTINE_ID, SampleInfo.forResult(0, 1, "L"), results(TestItem.AST, 30.5));
            check(Math.abs(Hitachi3100Frame.parse(f).results.get(0).getValue() - 30.5) < 1e-9, "프레임 값 포맷");
        } finally {
            Locale.setDefault(old);
        }
    }

    static void testQcValidation() throws Exception {
        freshDataDir();
        QCService qc = new QCService();
        expectThrows(IllegalArgumentException.class, () -> qc.updateParam(TestItem.AST, 50, 20, 40, 1.0, 30), "Target 이 범위 밖");
        expectThrows(IllegalArgumentException.class, () -> qc.updateParam(TestItem.AST, 30, 40, 20, 1.0, 30), "Min >= Max");
        expectThrows(IllegalArgumentException.class, () -> qc.updateParam(TestItem.AST, 30, 20, 40, 0.0, 30), "K-Factor 0");
        qc.updateParam(TestItem.AST, 30, 20, 40, 1.0, 30);
        check(qc.getParam(TestItem.AST).getTarget() == 30, "정상 값 저장");
        check(qc.recordQCResult("QC1", List.of(new TestResult(TestItem.AST, Double.NaN, null, "V"))) == 0, "값 없는 결과는 QC 에 기록 안 함");
        check(qc.recordQCResult("QC1", results(TestItem.AST, 31)) == 1, "정상 기록");
    }

    // ------------------------------------------------------------------ A단계: 비동기 저장 / 잠금 / 로그

    static String slowOrLocked = null;

    static void testAppLog() throws Exception {
        Path logDir = Files.createTempDirectory("logtest");
        System.setProperty("hitachi.log.dir", logDir.toString());
        long t0 = System.nanoTime();
        for (int i = 0; i < 5000; i++) com.hitachi3100.util.AppLog.info("line " + i);
        com.hitachi3100.util.AppLog.comm("RECV [AU->HOST] END 결과 | <STX>:a x<ETX>");
        com.hitachi3100.util.AppLog.error("오류 예시", new RuntimeException("boom"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(ms < 500, "5000줄 기록 호출이 호출 스레드를 막지 않음 (" + ms + "ms)");
        check(com.hitachi3100.util.AppLog.flush(5000), "로그 flush");
        String today = java.time.LocalDate.now().toString();
        Path app = logDir.resolve("app-" + today + ".log");
        Path comm = logDir.resolve("comm-" + today + ".log");
        check(Files.exists(app) && Files.exists(comm), "app-날짜.log / comm-날짜.log 생성");
        String txt = Files.readString(app);
        check(txt.contains("line 4999") && txt.contains("RuntimeException: boom"), "메시지와 스택트레이스 기록");
        check(Files.readString(comm).contains("END 결과"), "통신 로그 분리");
        // 오래된 로그 정리: 100일 전 파일은 다음 쓰기 때 삭제됨 (하루 1회 정리)
        Path old = logDir.resolve("app-" + java.time.LocalDate.now().minusDays(100) + ".log");
        Files.writeString(old, "old");
        check(Files.exists(old), "준비");
        com.hitachi3100.util.AppLog.purgeOldLogs();
        check(!Files.exists(old), "90일 지난 로그는 삭제");
        check(Files.exists(app), "오늘 로그는 유지");
    }

    static void testSlowDiskDoesNotDelayMor() throws Exception {
        freshDataDir();
        HistoryService hs = new HistoryService();
        QCService qc = new QCService();
        ReagentService rs = new ReagentService();
        OrderService os = new OrderService(qc, rs, hs);
        FakeChannel ch = new FakeChannel();
        os.setCommunicationChannel(ch);
        os.addOrder(1, "SLOW-1", List.of(TestItem.AST, TestItem.ALT));
        os.transmitPendingOrders();
        ch.fromAu(Hitachi3100Frame.createControlFrame(FRAME_ANY));
        check(waitUntil(() -> ch.sent.size() == 1, 1500), "SPE 전송");

        // 디스크가 3초씩 멈추는 상황 주입
        CsvAppender.setTestHook(target -> {
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            byte[] end = Hitachi3100Frame.createResultDataFrame(FRAME_END, FU_RESULT_ROUTINE_ID, SampleInfo.forResult(0, 4, "SLOW-1"),
                    results(TestItem.AST, 30, TestItem.ALT, 25));
            long t0 = System.currentTimeMillis();
            ch.fromAu(end);
            check(waitUntil(() -> ch.sent.size() == 2, 1500), "결과에 대한 MOR");
            long dt = ch.sentAt.get(1) - t0;
            check(dt < 1000, "디스크 3초 정지 중에도 MOR 는 1초 안에 전송 (실제 " + dt + "ms)");
            check(ch.lastChar() == ' ', "MOR");
            check(hs.getAllHistory().size() == 1, "메모리에는 즉시 반영");
            long t1 = System.currentTimeMillis();
            hs.recordPatientResult(9, "X", "AST", results(TestItem.AST, 1));
            check(System.currentTimeMillis() - t1 < 200, "recordPatientResult 는 디스크를 기다리지 않음");
        } finally {
            CsvAppender.setTestHook(null);
        }
        check(hs.flush(15000), "지연이 끝난 뒤 모두 저장됨");
        check(Files.readAllLines(com.hitachi3100.util.DataPaths.file("results.csv")).size() == 3, "헤더+2건 저장");
        os.shutdown();
    }

    /** 본 파일(results.csv)만 쓰기 실패하도록 만드는 훅 (엑셀이 파일을 연 상태를 흉내) */
    static volatile boolean locked = false;

    static void lockMainFile() {
        locked = true;
        CsvAppender.setTestHook(target -> {
            if (locked && !target.getFileName().toString().endsWith(".pending")) {
                throw new java.io.IOException("다른 프로세스가 파일을 사용 중이기 때문에 프로세스가 액세스 할 수 없습니다 (시뮬레이션)");
            }
        });
    }

    static void testFileLockFallbackAndMerge() throws Exception {
        Path dir = freshDataDir();
        List<String> msgs = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<String> l = msgs::add;
        com.hitachi3100.util.Storage.addListener(l);
        HistoryService hs = new HistoryService();
        hs.recordPatientResult(1, "BEFORE", "AST", results(TestItem.AST, 30));
        check(hs.flush(3000), "정상 저장");
        lockMainFile();
        try {
            hs.recordPatientResult(2, "LOCK-1", "AST", results(TestItem.AST, 31));
            hs.recordPatientResult(3, "LOCK-2", "AST", results(TestItem.AST, 32));
            check(hs.flush(5000), "잠긴 동안에도 flush 가능(.pending 에 기록)");
            Path main = dir.resolve("results.csv");
            Path pend = dir.resolve("results.csv.pending");
            check(Files.exists(pend) && Files.readAllLines(pend).size() == 2, "pending 에 2건 보관");
            check(Files.readAllLines(main).size() == 2, "본 파일은 헤더+BEFORE 만 (잠김)");
            check(hs.getAllHistory().size() == 3, "화면(메모리)에는 3건");
            check(msgs.stream().anyMatch(m -> m.contains("쓸 수 없습니다")), "잠김 경고 알림: " + msgs);

            // 프로그램 재시작 흉내: 잠긴 채로 새로 읽어도 pending 의 2건이 보임
            HistoryService restarted = new HistoryService();
            check(restarted.getAllHistory().size() == 3, "재시작해도 3건 (본 파일 + pending): " + restarted.getAllHistory().size());
        } finally {
            locked = false;      // 엑셀을 닫음
        }
        check(waitUntil(() -> !Files.exists(dir.resolve("results.csv.pending")), 10000), "잠금 해제 후 pending 자동 병합");
        List<String> lines = Files.readAllLines(dir.resolve("results.csv"));
        check(lines.size() == 4, "헤더+3건 (중복/유실 없음): " + lines.size());
        check(lines.get(1).contains("BEFORE") && lines.get(2).contains("LOCK-1") && lines.get(3).contains("LOCK-2"), "순서 유지");
        check(msgs.stream().anyMatch(m -> m.contains("병합")), "복구 알림");
        CsvAppender.setTestHook(null);
        com.hitachi3100.util.Storage.removeListener(l);
    }

    static void testMergeCrashNoDuplicates() throws Exception {
        Path dir = freshDataDir();
        HistoryService seed = new HistoryService();
        seed.recordPatientResult(1, "DUP-1", "AST", results(TestItem.AST, 30));
        seed.recordPatientResult(2, "DUP-2", "AST", results(TestItem.AST, 31));
        seed.flush(3000);
        // 병합 직후 pending 삭제 전에 프로그램이 종료된 상황: 같은 줄이 pending 에도 남아 있음
        List<String> data = Files.readAllLines(dir.resolve("results.csv"));
        Files.write(dir.resolve("results.csv.pending"), data.subList(1, 3));
        HistoryService h = new HistoryService();
        check(h.getAllHistory().size() == 2, "읽을 때 중복 제거: " + h.getAllHistory().size());
        check(waitUntil(() -> !Files.exists(dir.resolve("results.csv.pending")), 10000), "pending 정리");
        check(Files.readAllLines(dir.resolve("results.csv")).size() == 3, "본 파일에 중복이 추가되지 않음");
    }

    static void testReagentSaveRetry() throws Exception {
        Path dir = freshDataDir();
        List<String> msgs = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<String> l = msgs::add;
        com.hitachi3100.util.Storage.addListener(l);
        ReagentService first = new ReagentService();
        check(first.flush(3000), "초기 저장");
        double before = reagentTests(first, TestItem.AST);
        first.close();

        // 파일 교체가 불가능한 상태(디렉터리가 같은 이름으로 존재)를 만들어 잠김을 흉내
        Path target = dir.resolve("reagents.csv");
        Path backup = dir.resolve("reagents.bak");
        Files.move(target, backup);
        Files.createDirectory(target);
        Files.writeString(target.resolve("x"), "x");

        ReagentService rs = new ReagentService();
        rs.deductReagents(List.of(TestItem.AST));
        check(reagentTests(rs, TestItem.AST) == before - 2, "메모리 값은 정상 차감");
        check(waitUntil(() -> msgs.stream().anyMatch(m -> m.contains("저장할 수 없습니다")), 6000), "저장 실패 알림: " + msgs);

        Files.delete(target.resolve("x"));
        Files.delete(target);                 // 잠금 해제
        check(waitUntil(() -> Files.isRegularFile(target), 10000), "재시도로 파일 생성");
        check(rs.flush(5000), "저장 완료");
        rs.close();
        check(reagentTests(new ReagentService(), TestItem.AST) == before - 2, "재시작 후에도 차감 값 유지");
        check(msgs.stream().anyMatch(m -> m.contains("복구")), "복구 알림");
        com.hitachi3100.util.Storage.removeListener(l);
    }

    // ------------------------------------------------------------------ B단계: 지연 로딩 / 기간 검색

    static String csvRow(java.time.LocalDateTime t, int pos, String id, String status) {
        return new PatientRecord(t, pos, id, "AST", status, results(TestItem.AST, 30)).toCsvLine();
    }

    static void testLazyLoadAndArchiveSearch() throws Exception {
        Path dir = freshDataDir();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        List<String> lines = new ArrayList<>();
        lines.add("TestDateTime,Position,PatientId,TestItems,Status,Results");
        for (int i = 0; i < 50; i++) lines.add(csvRow(now.minusDays(400).plusMinutes(i), 1 + i % 35, "OLD-" + i, i % 10 == 0 ? "이상치" : "정상"));
        for (int i = 0; i < 20; i++) lines.add(csvRow(now.minusDays(100).plusMinutes(i), 1, "MID-" + i, "정상"));
        for (int i = 0; i < 30; i++) lines.add(csvRow(now.minusDays(5).plusMinutes(i), 2, "NEW-" + i, "정상"));
        Files.write(dir.resolve("results.csv"), lines);

        HistoryService hs = new HistoryService(30);
        check(hs.getAllHistory().size() == 30, "최근 30일 기록만 메모리에: " + hs.getAllHistory().size());
        check(hs.getAllHistory().get(0).getPatientId().equals("NEW-29"), "최신순");
        check(hs.filter("OLD-", "전체").isEmpty(), "메모리 필터에는 과거 기록이 없음");

        java.time.LocalDate oldFrom = now.minusDays(401).toLocalDate();
        java.time.LocalDate oldTo = now.minusDays(399).toLocalDate();
        HistoryService.ArchiveResult r = hs.searchArchive(oldFrom, oldTo, "", "전체", 2000);
        check(r.rows.size() == 50 && !r.truncated, "과거 기간 검색 50건: " + r.rows.size());
        check(r.rows.get(0).getPatientId().equals("OLD-49"), "결과도 최신순");
        HistoryService.ArchiveResult q = hs.searchArchive(oldFrom, oldTo, "OLD-1", "전체", 2000);
        check(q.rows.size() == 11, "검색어 필터 (OLD-1, OLD-10~19): " + q.rows.size());
        HistoryService.ArchiveResult st = hs.searchArchive(oldFrom, oldTo, "", "이상치", 2000);
        check(st.rows.size() == 5, "상태 필터: " + st.rows.size());
        HistoryService.ArchiveResult lim = hs.searchArchive(oldFrom, oldTo, "", "전체", 10);
        check(lim.rows.size() == 10 && lim.truncated && lim.matchedScanned == 50, "limit 적용 + truncated");
        check(lim.rows.get(0).getPatientId().equals("OLD-49"), "limit 시 최신 쪽을 유지");
        check(hs.searchArchive(now.minusDays(300).toLocalDate(), now.minusDays(200).toLocalDate(), "", "전체", 100).rows.isEmpty(), "데이터 없는 기간");

        // 방금 기록한(아직 파일에 안 쓴) 건도 기간 검색에 포함
        hs.recordPatientResult(5, "JUST-NOW", "AST", results(TestItem.AST, 30));
        check(hs.searchArchive(now.toLocalDate(), now.toLocalDate(), "JUST-NOW", "전체", 10).rows.size() == 1, "최근 기록 포함");
    }

    static void testMemoryCapAndQcWindow() throws Exception {
        Path dir = freshDataDir();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        StringBuilder sb = new StringBuilder("TestDateTime,Position,PatientId,TestItems,Status,Results\n");
        for (int i = 0; i < 25_000; i++) sb.append(csvRow(now.minusMinutes(25_000 - i), 1, "P-" + i, "정상")).append("\n");
        Files.writeString(dir.resolve("results.csv"), sb.toString());
        HistoryService hs = new HistoryService(30);
        check(hs.getAllHistory().size() == HistoryService.MAX_IN_MEMORY, "상한 20,000건: " + hs.getAllHistory().size());
        check(hs.getAllHistory().get(0).getPatientId().equals("P-24999"), "가장 최근부터 유지");
        check(hs.getMemoryFrom().isAfter(now.minusDays(30)), "메모리 시작 시각이 상한에 맞게 조정");
        check(hs.searchArchive(now.minusDays(30).toLocalDate(), now.toLocalDate(), "P-0", "전체", 3000).rows.size() > 0, "상한 밖 기록도 기간 검색으로 접근");

        // QC: 최근 N일만 로딩
        List<String> q = new ArrayList<>();
        q.add("Timestamp,QCLevel,Item,Value,Target,Min,Max,Status");
        q.add(new QCRecord(now.minusDays(200), "QC1", TestItem.AST, 30, 30, 20, 40).toCsvLine());
        q.add(new QCRecord(now.minusDays(10), "QC1", TestItem.AST, 31, 30, 20, 40).toCsvLine());
        q.add(new QCRecord(now.minusDays(1), "QC2", TestItem.ALT, 32, 30, 20, 40).toCsvLine());
        Files.write(dir.resolve("qc_results.csv"), q);
        check(new QCService(30).getAllRecords().size() == 2, "QC 최근 30일만 로딩");
        check(new QCService(365).getAllRecords().size() == 3, "QC 365일이면 전부");
    }

    static void testCsvRoundTrip() {
        String tricky = "A,\"B\",C";
        PatientRecord r = new PatientRecord(java.time.LocalDateTime.of(2026, 1, 2, 3, 4, 5), 7, tricky, "AST, ALT", "정상",
                results(TestItem.AST, 30.5, TestItem.ALT, 12));
        PatientRecord back = PatientRecord.fromCsvLine(r.toCsvLine());
        check(back != null && back.getPatientId().equals(tricky), "쉼표/따옴표 포함 ID 복원: " + (back == null ? null : back.getPatientId()));
        check(back.getResults().size() == 2 && back.getPosition() == 7 && back.getStatus().equals("정상"), "나머지 열");
        check(PatientRecord.fromCsvLine("garbage") == null, "손상된 줄은 null");
        check(PatientRecord.fromCsvLine("2026-01-02 03:04:05,x,ID,AST,정상,\"\"") == null, "숫자 아닌 Position 은 null");
    }
}
