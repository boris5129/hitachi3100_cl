package com.hitachi3100.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 환자 검사 이력 레코드 모델
 */
public class PatientRecord {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LocalDateTime testDateTime;
    private final int position;
    private final String patientId;
    private final String testItemsSummary;
    private final String status; // "완료", "이상치", "정상"
    private final List<TestResult> results;

    public PatientRecord(LocalDateTime testDateTime, int position, String patientId, String testItemsSummary, String status, List<TestResult> results) {
        this.testDateTime = testDateTime != null ? testDateTime : LocalDateTime.now();
        this.position = position;
        this.patientId = patientId != null ? patientId.trim() : "";
        this.testItemsSummary = testItemsSummary != null ? testItemsSummary : "";
        this.status = status != null ? status : "완료";
        this.results = new ArrayList<>(results != null ? results : Collections.emptyList());
    }

    public LocalDateTime getTestDateTime() {
        return testDateTime;
    }

    public String getFormattedDateTime() {
        return testDateTime.format(FORMATTER);
    }

    public int getPosition() {
        return position;
    }

    public String getPatientId() {
        return patientId;
    }

    public String getTestItemsSummary() {
        return testItemsSummary;
    }

    public String getStatus() {
        return status;
    }

    public List<TestResult> getResults() {
        return Collections.unmodifiableList(results);
    }

    /**
     * 항목별 검사 결과 종합 문자열: 예) AST: 35.0 (Normal) | ALT: 65.0 (H)
     */
    public String getResultsSummary() {
        return results.stream()
                .map(r -> r.getItem().getCode() + ": " + r.getFormattedValue() + " " + r.getUnit() + " (" + r.getFlag() + ")")
                .collect(Collectors.joining(" | "));
    }

    public boolean hasAbnormal() {
        return results.stream().anyMatch(TestResult::isAbnormal);
    }

    /**
     * CSV 저장용 포맷
     * testDateTime,position,patientId,testItemsSummary,status,serializedResults
     */
    public String toCsvLine() {
        String serializedResults = results.stream()
                .map(r -> r.getItem().getCode() + ":" + r.getValue() + ":" + r.getFlag() + ":" + r.getAlarmCode())
                .collect(Collectors.joining(";"));

        return String.join(",",
                getFormattedDateTime(),
                String.valueOf(position),
                escapeCsv(patientId),
                escapeCsv(testItemsSummary),
                escapeCsv(status),
                escapeCsv(serializedResults)
        );
    }

    public static PatientRecord fromCsvLine(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = splitCsv(line);
        if (parts.length < 6) return null;

        try {
            LocalDateTime dt = LocalDateTime.parse(parts[0].trim(), FORMATTER);
            int pos = Integer.parseInt(parts[1].trim());
            String pid = unescapeCsv(parts[2].trim());
            String items = unescapeCsv(parts[3].trim());
            String stat = unescapeCsv(parts[4].trim());
            String serResults = unescapeCsv(parts[5].trim());

            List<TestResult> resList = new ArrayList<>();
            if (!serResults.isEmpty()) {
                String[] itemsParts = serResults.split(";");
                for (String ip : itemsParts) {
                    String[] tokens = ip.split(":");
                    if (tokens.length >= 3) {
                        TestItem.fromCode(tokens[0]).ifPresent(item -> {
                            double val = Double.parseDouble(tokens[1]);
                            String fl = tokens[2];
                            String alarm = tokens.length > 3 ? tokens[3] : "";
                            resList.add(new TestResult(item, val, fl, alarm));
                        });
                    }
                }
            }
            return new PatientRecord(dt, pos, pid, items, stat, resList);
        } catch (Exception e) {
            com.hitachi3100.util.AppLog.error("Error parsing CSV line: " + line + " -> " + e.getMessage());
            return null;
        }
    }

    /** 따옴표 밖의 쉼표로만 나누는 단일 패스 분리기 (기존 정규식 방식보다 수십 배 빠름, 동작은 동일) */
    static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>(8);
        int start = 0;
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ',' && !inQuote) {
                out.add(line.substring(start, i));
                start = i + 1;
            }
        }
        out.add(line.substring(start));
        return out.toArray(new String[0]);
    }

    private static String escapeCsv(String str) {
        if (str == null) return "\"\"";
        return "\"" + str.replace("\"", "\"\"") + "\"";
    }

    private static String unescapeCsv(String str) {
        if (str == null) return "";
        if (str.startsWith("\"") && str.endsWith("\"") && str.length() >= 2) {
            str = str.substring(1, str.length() - 1).replace("\"\"", "\"");
        }
        return str;
    }
}
