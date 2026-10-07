package com.hitachi3100.service;

import com.hitachi3100.model.PatientRecord;
import com.hitachi3100.model.TestResult;

import com.hitachi3100.util.AppLog;
import com.hitachi3100.util.CsvAppender;
import com.hitachi3100.util.DataPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 환자 검사 이력 관리 서비스.
 *
 *  - 시작 시에는 최근 N일(기본 30일, 최대 20,000건)만 메모리에 올린다. 그보다 오래된 기록은 {@link #searchArchive} 로
 *    기간을 지정해 파일에서 직접 찾는다 (장기 운영 시 시작 지연/메모리 증가 방지).
 *  - 파일 저장은 CsvAppender 가 백그라운드로 처리한다 (통신 스레드가 디스크를 기다리지 않음, 엑셀 잠금 시 .pending 보관).
 */
public class HistoryService {
    private static final String RESULTS_NAME = "results.csv";
    private static final String RESULTS_HEADER = "TestDateTime,Position,PatientId,TestItems,Status,Results";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final int DEFAULT_MEMORY_DAYS = 30;
    public static final int MAX_IN_MEMORY = 20_000;

    // 최신 기록이 맨 앞. 수신 스레드가 추가하고 UI 스레드가 읽으므로 CopyOnWrite 사용
    private final List<PatientRecord> historyList = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final CsvAppender appender;
    private final int memoryDays;
    private volatile LocalDateTime memoryFrom;

    /** 검색 결과 (limit 건까지만 담고, 더 있으면 truncated=true) */
    public static final class ArchiveResult {
        public final List<PatientRecord> rows;
        public final boolean truncated;
        public final int matchedScanned;
        ArchiveResult(List<PatientRecord> rows, boolean truncated, int matchedScanned) {
            this.rows = rows;
            this.truncated = truncated;
            this.matchedScanned = matchedScanned;
        }
    }

    public HistoryService() {
        this(Integer.getInteger("hitachi.history.days", DEFAULT_MEMORY_DAYS));
    }

    public HistoryService(int memoryDays) {
        this.memoryDays = Math.max(1, memoryDays);
        this.appender = new CsvAppender(DataPaths.file(RESULTS_NAME), RESULTS_HEADER);
        loadHistory();
    }

    public void recordPatientResult(int position, String patientId, String testItemsSummary, List<TestResult> results) {
        boolean hasAbnormal = results.stream().anyMatch(r -> r.isAbnormal() || r.hasAlarm());
        String status = hasAbnormal ? "이상치" : "정상";

        PatientRecord record = new PatientRecord(
                LocalDateTime.now(),
                position,
                patientId,
                testItemsSummary,
                status,
                results
        );

        historyList.add(0, record); // 최신순 맨 앞
        appender.append(record.toCsvLine());   // 비동기 저장 (즉시 반환)
        notifyListeners();
    }

    public List<PatientRecord> getAllHistory() {
        return Collections.unmodifiableList(historyList);
    }

    /** 메모리에 올라와 있는 기록의 시작 시각 (이보다 오래된 기록은 기간 검색으로 조회) */
    public LocalDateTime getMemoryFrom() {
        return memoryFrom;
    }

    public int getMemoryDays() {
        return memoryDays;
    }

    /** 저장 대기 중인 항목을 모두 기록 (종료/테스트용) */
    public boolean flush(long timeoutMs) {
        return appender.flush(timeoutMs);
    }

    public void close() {
        appender.close();
    }

    /**
     * 환자 ID 또는 Position 기반 실시간 검색/필터링 기능 (메모리에 올라온 최근 기록 대상)
     */
    public List<PatientRecord> filter(String query, String statusFilter) {
        return historyList.stream()
                .filter(rec -> matches(rec, query, statusFilter))
                .collect(Collectors.toList());
    }

    private static boolean matches(PatientRecord rec, String query, String statusFilter) {
        if (statusFilter != null && !statusFilter.equals("전체") && !statusFilter.isBlank()) {
            if (!rec.getStatus().equalsIgnoreCase(statusFilter)) {
                return false;
            }
        }
        if (query == null || query.isBlank()) return true;
        String q = query.trim().toLowerCase();
        return rec.getPatientId().toLowerCase().contains(q)
                || String.valueOf(rec.getPosition()).contains(q)
                || rec.getTestItemsSummary().toLowerCase().contains(q);
    }

    /**
     * 오래된 기록을 포함한 기간 검색 (파일을 순차 스캔하므로 백그라운드 스레드에서 호출할 것).
     * 결과는 최신순, 최대 limit 건.
     */
    public ArchiveResult searchArchive(LocalDate from, LocalDate to, String query, String statusFilter, int limit) throws IOException {
        appender.flush(3000);   // 아직 파일에 안 쓴 최근 기록도 포함되도록
        String fromStr = from.atStartOfDay().format(FMT);
        String toExclusive = to.plusDays(1).atStartOfDay().format(FMT);

        final String q = (query == null) ? "" : query.trim().toLowerCase();
        final boolean noQuery = q.isEmpty();
        final boolean noStatus = statusFilter == null || statusFilter.isBlank() || statusFilter.equals("전체");
        // 줄 문자열에 검색어/상태가 들어 있지 않으면 파싱 없이 건너뛴다. (파싱 비용이 스캔 시간의 대부분이다)
        // 줄에는 ID/Position/검사항목/상태가 그대로 들어 있으므로 이 검사는 항상 "실제 일치 조건보다 느슨"하다.
        final boolean canPrefilter = q.indexOf('"') < 0;

        Deque<String> keptLines = new ArrayDeque<>();     // 조건이 단순하면 줄 그대로 보관하고 마지막에만 파싱
        Deque<PatientRecord> keptRecs = new ArrayDeque<>();
        boolean[] truncated = {false};
        int[] count = {0};
        final boolean lazyParse = noQuery && noStatus;

        CsvAppender.scanLines(DataPaths.file(RESULTS_NAME), fromStr, line -> {
            if (CsvAppender.compare19(line, toExclusive) >= 0) return;
            if (lazyParse) {
                count[0]++;
                keptLines.addLast(line);
                if (keptLines.size() > limit) {
                    keptLines.removeFirst();
                    truncated[0] = true;
                }
                return;
            }
            if (canPrefilter && !noQuery && !line.toLowerCase().contains(q)) return;
            if (!noStatus && !line.contains(statusFilter)) return;   // 사전 필터(느슨한 조건), 정확한 판정은 파싱 후 matches()
            PatientRecord rec = PatientRecord.fromCsvLine(line);
            if (rec == null || !matches(rec, query, statusFilter)) return;
            count[0]++;
            keptRecs.addLast(rec);
            if (keptRecs.size() > limit) {
                keptRecs.removeFirst();     // 파일은 오래된 순이므로 가장 오래된 것을 버린다
                truncated[0] = true;
            }
        });

        List<PatientRecord> rows = new ArrayList<>(keptRecs);
        for (String line : keptLines) {
            PatientRecord rec = PatientRecord.fromCsvLine(line);
            if (rec != null) rows.add(rec);
        }
        rows.sort(Comparator.comparing(PatientRecord::getTestDateTime).reversed());
        return new ArchiveResult(rows, truncated[0], count[0]);
    }

    public void addChangeListener(Runnable r) {
        listeners.add(r);
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException e) {
                AppLog.error("HistoryService 리스너 오류", e);
            }
        }
    }

    private void loadHistory() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(memoryDays);
        memoryFrom = cutoff;
        List<PatientRecord> loaded = new ArrayList<>();
        try {
            // 오래된 줄은 파싱하지 않고 앞 19자(시각)만 비교해서 건너뛴다
            CsvAppender.scanLines(DataPaths.file(RESULTS_NAME), cutoff.format(FMT), line -> {
                PatientRecord rec = PatientRecord.fromCsvLine(line);
                if (rec != null) loaded.add(rec);
            });
        } catch (Exception e) {
            AppLog.error("results.csv 읽기 실패", e);
        }
        // 파일은 오래된 순으로 쌓여 있으므로 실행 중과 동일하게 최신순으로 정렬한다
        loaded.sort(Comparator.comparing(PatientRecord::getTestDateTime).reversed());
        if (loaded.size() > MAX_IN_MEMORY) {
            List<PatientRecord> newest = new ArrayList<>(loaded.subList(0, MAX_IN_MEMORY));
            memoryFrom = newest.get(newest.size() - 1).getTestDateTime();
            loaded.clear();
            loaded.addAll(newest);
            AppLog.info("이력이 많아 최근 " + MAX_IN_MEMORY + "건만 메모리에 올렸습니다 (기간 검색으로 이전 기록 조회 가능)");
        }
        historyList.addAll(loaded);
        AppLog.info("검사 이력 로드: " + loaded.size() + "건 (" + memoryFrom.format(FMT) + " 이후)");
    }
}
