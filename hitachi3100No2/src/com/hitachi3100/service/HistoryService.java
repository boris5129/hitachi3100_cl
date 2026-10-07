package com.hitachi3100.service;

import com.hitachi3100.model.PatientRecord;
import com.hitachi3100.model.TestResult;

import com.hitachi3100.util.DataPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 환자 검사 이력 관리 및 results.csv 실시간 동기화 서비스
 */
public class HistoryService {
    private static final String RESULTS_NAME = "results.csv";
    private static final String RESULTS_HEADER = "TestDateTime,Position,PatientId,TestItems,Status,Results";
    // 최신 기록이 맨 앞. 수신 스레드가 추가하고 UI 스레드가 읽으므로 CopyOnWrite 사용
    private final List<PatientRecord> historyList = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public HistoryService() {
        loadHistory();
    }

    public synchronized void recordPatientResult(int position, String patientId, String testItemsSummary, List<TestResult> results) {
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
        appendRecordToCsv(record);
        notifyListeners();
    }

    public List<PatientRecord> getAllHistory() {
        return Collections.unmodifiableList(historyList);
    }

    /**
     * 환자 ID 또는 Position 기반 실시간 검색/필터링 기능
     */
    public List<PatientRecord> filter(String query, String statusFilter) {
        return historyList.stream()
                .filter(rec -> {
                    if (statusFilter != null && !statusFilter.equals("전체") && !statusFilter.isBlank()) {
                        if (!rec.getStatus().equalsIgnoreCase(statusFilter)) {
                            return false;
                        }
                    }
                    if (query == null || query.isBlank()) return true;
                    String q = query.trim().toLowerCase();
                    boolean matchId = rec.getPatientId().toLowerCase().contains(q);
                    boolean matchPos = String.valueOf(rec.getPosition()).contains(q);
                    boolean matchItems = rec.getTestItemsSummary().toLowerCase().contains(q);
                    return matchId || matchPos || matchItems;
                })
                .collect(Collectors.toList());
    }

    public void addChangeListener(Runnable r) {
        listeners.add(r);
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            r.run();
        }
    }

    private void loadHistory() {
        File file = DataPaths.file(RESULTS_NAME).toFile();
        if (!file.exists()) return;

        List<PatientRecord> loaded = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // Header
            while ((line = reader.readLine()) != null) {
                PatientRecord rec = PatientRecord.fromCsvLine(line);
                if (rec != null) {
                    loaded.add(rec);
                }
            }
            // 파일은 오래된 순으로 쌓여 있으므로 실행 중과 동일하게 최신순으로 정렬한다
            loaded.sort(java.util.Comparator.comparing(PatientRecord::getTestDateTime).reversed());
            historyList.addAll(loaded);
        } catch (Exception e) {
            System.err.println("Error reading results.csv: " + e.getMessage());
        }
    }

    private synchronized void appendRecordToCsv(PatientRecord record) {
        try {
            DataPaths.appendLine(DataPaths.file(RESULTS_NAME), RESULTS_HEADER, record.toCsvLine());
        } catch (Exception e) {
            System.err.println("Error appending to results.csv: " + e.getMessage());
        }
    }
}
