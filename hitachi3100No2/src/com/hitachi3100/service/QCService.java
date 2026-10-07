package com.hitachi3100.service;

import com.hitachi3100.model.CalibrationParam;
import com.hitachi3100.model.QCRecord;
import com.hitachi3100.model.TestItem;
import com.hitachi3100.model.TestResult;

import com.hitachi3100.util.DataPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 정도관리 (QC) & Calibration 관리 서비스
 */
public class QCService {
    private static final String QC_RESULTS_NAME = "qc_results.csv";
    private static final String QC_CONFIG_NAME  = "qc_config.properties";
    private static final String QC_HEADER = "Timestamp,QCLevel,Item,Value,Target,Min,Max,Status";

    private final Map<TestItem, CalibrationParam> paramMap = new EnumMap<>(TestItem.class);
    private final List<QCRecord> qcRecords = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public QCService() {
        initCalibrationParams();
        loadQCResults();
    }

    private void initCalibrationParams() {
        for (TestItem item : TestItem.values()) {
            paramMap.put(item, CalibrationParam.createDefault(item));
        }
        loadConfig();
    }

    /** 현재 기준값의 복사본 (UI 스레드와 수신 스레드가 동시에 접근해도 안전) */
    public synchronized CalibrationParam getParam(TestItem item) {
        CalibrationParam p = paramMap.get(item);
        return p == null ? null : new CalibrationParam(item, p.getTarget(), p.getMin(), p.getMax(), p.getkFactor(), p.getStdConc());
    }

    public synchronized void updateParam(TestItem item, double target, double min, double max, double kFactor, double conc) {
        validateParam(target, min, max, kFactor, conc);
        CalibrationParam p = paramMap.get(item);
        if (p != null) {
            p.setTarget(target);
            p.setMin(min);
            p.setMax(max);
            p.setkFactor(kFactor);
            p.setStdConc(conc);
            saveConfig();
            notifyListeners();
        }
    }

    /** 기준값 검증: 모두 유한한 수, Min < Max, Min <= Target <= Max, K-Factor > 0 */
    public static void validateParam(double target, double min, double max, double kFactor, double conc) {
        for (double d : new double[]{target, min, max, kFactor, conc}) {
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("숫자가 아닌 값이 있습니다.");
        }
        if (min >= max) throw new IllegalArgumentException("Min 수치는 Max 수치보다 작아야 합니다.");
        if (target < min || target > max) throw new IllegalArgumentException("Target 은 Min~Max 범위 안에 있어야 합니다.");
        if (kFactor <= 0) throw new IllegalArgumentException("K-Factor 는 0보다 커야 합니다.");
    }

    /**
     * 장비가 Control 샘플(Function Character 'f')로 보낸 결과를 기록한다.
     * qcLevel 은 Control No. 로 결정된 "QC1".."QC5". 값이 없는(공백) 결과는 기록하지 않는다.
     * @return 기록된 레코드 수
     */
    public synchronized int recordQCResult(String qcLevel, List<TestResult> results) {
        if (results == null || results.isEmpty()) return 0;
        String level = (qcLevel == null || qcLevel.isBlank()) ? "QC" : qcLevel.trim();

        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (TestResult tr : results) {
            if (!tr.hasValue()) continue;
            CalibrationParam p = paramMap.get(tr.getItem());
            double tgt = p != null ? p.getTarget() : tr.getItem().getDefaultTarget();
            double mn = p != null ? p.getMin() : tr.getItem().getRefLow();
            double mx = p != null ? p.getMax() : tr.getItem().getRefHigh();

            QCRecord rec = new QCRecord(now, level, tr.getItem(), tr.getValue(), tgt, mn, mx);
            qcRecords.add(rec);
            appendQCRecordToCsv(rec);
            count++;
        }
        if (count > 0) notifyListeners();
        return count;
    }

    public List<QCRecord> getAllRecords() {
        return new ArrayList<>(qcRecords);   // 스냅샷 (수신 스레드가 추가해도 UI 순회가 안전)
    }

    public List<QCRecord> getRecordsForItem(TestItem item) {
        List<QCRecord> list = new ArrayList<>();
        for (QCRecord r : qcRecords) {
            if (r.getItem() == item) {
                list.add(r);
            }
        }
        return list;
    }

    /**
     * 바코드 텍스트 파싱
     * 지원 형식 1: ITEM=AST;LOT=2026A;TGT=32.0;MIN=26.0;MAX=38.0;K=1.02;CONC=100.0
     * 지원 형식 2: QC|AST|LOT123|T:32.0|M:26.0|X:38.0
     */
    public boolean parseBarcode(String barcodeText, BarcodeParseResult outResult) {
        if (barcodeText == null || barcodeText.isBlank()) return false;
        String text = barcodeText.trim();

        try {
            TestItem foundItem = null;
            double target = Double.NaN;
            double min = Double.NaN;
            double max = Double.NaN;
            double kFactor = Double.NaN;
            double conc = Double.NaN;
            String lot = "";

            if (text.contains(";") || text.contains("&")) {
                String[] tokens = text.split("[;&]");
                for (String t : tokens) {
                    String[] kv = t.split("=");
                    if (kv.length == 2) {
                        String k = kv[0].trim().toUpperCase();
                        String v = kv[1].trim();
                        switch (k) {
                            case "ITEM", "TEST" -> foundItem = TestItem.fromCode(v).orElse(null);
                            case "TGT", "TARGET" -> target = Double.parseDouble(v);
                            case "MIN" -> min = Double.parseDouble(v);
                            case "MAX" -> max = Double.parseDouble(v);
                            case "K", "KFACTOR" -> kFactor = Double.parseDouble(v);
                            case "CONC" -> conc = Double.parseDouble(v);
                            case "LOT" -> lot = v;
                        }
                    }
                }
            } else if (text.contains("|")) {
                String[] parts = text.split("\\|");
                for (String p : parts) {
                    String pu = p.toUpperCase().trim();
                    if (foundItem == null) {
                        Optional<TestItem> opt = TestItem.fromCode(pu);
                        if (opt.isPresent()) {
                            foundItem = opt.get();
                            continue;
                        }
                    }
                    if (pu.startsWith("T:") || pu.startsWith("TGT:")) {
                        target = Double.parseDouble(p.substring(p.indexOf(':') + 1));
                    } else if (pu.startsWith("M:") || pu.startsWith("MIN:")) {
                        min = Double.parseDouble(p.substring(p.indexOf(':') + 1));
                    } else if (pu.startsWith("X:") || pu.startsWith("MAX:")) {
                        max = Double.parseDouble(p.substring(p.indexOf(':') + 1));
                    } else if (pu.startsWith("K:")) {
                        kFactor = Double.parseDouble(p.substring(p.indexOf(':') + 1));
                    } else if (pu.startsWith("C:") || pu.startsWith("CONC:")) {
                        conc = Double.parseDouble(p.substring(p.indexOf(':') + 1));
                    } else if (pu.startsWith("LOT:")) {
                        lot = p.substring(p.indexOf(':') + 1);
                    }
                }
            }

            if (foundItem != null) {
                double chkTarget = !Double.isNaN(target) ? target : foundItem.getDefaultTarget();
                double chkMin = !Double.isNaN(min) ? min : foundItem.getRefLow();
                double chkMax = !Double.isNaN(max) ? max : foundItem.getRefHigh();
                double chkK = !Double.isNaN(kFactor) ? kFactor : 1.0;
                validateParam(chkTarget, chkMin, chkMax, chkK, 1.0);
                outResult.item = foundItem;
                outResult.lotNumber = lot;
                outResult.target = !Double.isNaN(target) ? target : foundItem.getDefaultTarget();
                outResult.min = !Double.isNaN(min) ? min : foundItem.getRefLow();
                outResult.max = !Double.isNaN(max) ? max : foundItem.getRefHigh();
                outResult.kFactor = !Double.isNaN(kFactor) ? kFactor : 1.0;
                outResult.conc = !Double.isNaN(conc) ? conc : outResult.target;
                return true;
            }
        } catch (Exception e) {
            System.err.println("Barcode parse failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * 가상 바코드 테스트 생성기
     */
    public String generateVirtualBarcode(TestItem item) {
        CalibrationParam p = getParam(item);
        return String.format(java.util.Locale.ROOT, "ITEM=%s;LOT=LOT%d;TGT=%.1f;MIN=%.1f;MAX=%.1f;K=%.2f;CONC=%.1f",
                item.getCode(),
                System.currentTimeMillis() % 10000,
                p.getTarget(),
                p.getMin(),
                p.getMax(),
                p.getkFactor(),
                p.getStdConc()
        );
    }

    public static class BarcodeParseResult {
        public TestItem item;
        public String lotNumber;
        public double target;
        public double min;
        public double max;
        public double kFactor;
        public double conc;
    }

    public void addChangeListener(Runnable r) {
        listeners.add(r);
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            r.run();
        }
    }

    private void loadConfig() {
        File f = DataPaths.file(QC_CONFIG_NAME).toFile();
        if (!f.exists()) return;
        Properties p = new Properties();
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (Exception e) {
            System.err.println("Error loading qc_config.properties: " + e.getMessage());
            return;
        }
        for (TestItem item : TestItem.values()) {
            String code = item.getCode();
            if (!p.containsKey(code + ".target")) continue;
            try {   // 항목 하나가 잘못되어도 나머지는 계속 읽는다
                double tgt = Double.parseDouble(p.getProperty(code + ".target"));
                double mn = Double.parseDouble(p.getProperty(code + ".min"));
                double mx = Double.parseDouble(p.getProperty(code + ".max"));
                double k = Double.parseDouble(p.getProperty(code + ".kFactor", "1.0"));
                double conc = Double.parseDouble(p.getProperty(code + ".conc", String.valueOf(tgt)));
                validateParam(tgt, mn, mx, k, conc);
                paramMap.put(item, new CalibrationParam(item, tgt, mn, mx, k, conc));
            } catch (Exception e) {
                System.err.println("qc_config.properties: " + code + " 항목 무시(" + e.getMessage() + ")");
            }
        }
    }

    public synchronized void saveConfig() {
        Properties p = new Properties();
        for (Map.Entry<TestItem, CalibrationParam> entry : paramMap.entrySet()) {
            String code = entry.getKey().getCode();
            CalibrationParam cp = entry.getValue();
            p.setProperty(code + ".target", String.valueOf(cp.getTarget()));
            p.setProperty(code + ".min", String.valueOf(cp.getMin()));
            p.setProperty(code + ".max", String.valueOf(cp.getMax()));
            p.setProperty(code + ".kFactor", String.valueOf(cp.getkFactor()));
            p.setProperty(code + ".conc", String.valueOf(cp.getStdConc()));
        }
        try {
            StringWriter w = new StringWriter();
            p.store(w, "Hitachi 3100 QC & Calibration Config");
            DataPaths.atomicWrite(DataPaths.file(QC_CONFIG_NAME), w.toString());
        } catch (Exception e) {
            System.err.println("Error saving qc_config.properties: " + e.getMessage());
        }
    }

    private void loadQCResults() {
        File f = DataPaths.file(QC_RESULTS_NAME).toFile();
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = br.readLine(); // Header
            while ((line = br.readLine()) != null) {
                QCRecord rec = QCRecord.fromCsvLine(line);
                if (rec != null) {
                    qcRecords.add(rec);
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading qc_results.csv: " + e.getMessage());
        }
    }

    private synchronized void appendQCRecordToCsv(QCRecord rec) {
        try {
            DataPaths.appendLine(DataPaths.file(QC_RESULTS_NAME), QC_HEADER, rec.toCsvLine());
        } catch (Exception e) {
            System.err.println("Error appending to qc_results.csv: " + e.getMessage());
        }
    }
}
