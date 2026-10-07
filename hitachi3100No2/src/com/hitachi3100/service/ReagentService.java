package com.hitachi3100.service;

import com.hitachi3100.model.ReagentItem;
import com.hitachi3100.model.TestItem;

import com.hitachi3100.util.DataPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 34개 시약(R1, R2) 잔량 관리 및 실시간 차감/리셋 서비스
 */
public class ReagentService {
    private static final String REAGENTS_NAME = "reagents.csv";
    private static final int DEFAULT_MAX_TESTS = 250;

    private final List<ReagentItem> reagents = new ArrayList<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public ReagentService() {
        initReagents();
    }

    private void initReagents() {
        File file = DataPaths.file(REAGENTS_NAME).toFile();
        if (file.exists()) {
            loadFromCsv(file);
        }

        // 혹시 누락된 항목이 있으면 기본값으로 보충
        Set<String> existingKeys = new HashSet<>();
        for (ReagentItem r : reagents) {
            existingKeys.add(r.getItem().getCode() + "_" + r.getType().name());
        }

        boolean added = false;
        for (TestItem item : TestItem.values()) {
            for (ReagentItem.ReagentType type : ReagentItem.ReagentType.values()) {
                String key = item.getCode() + "_" + type.name();
                if (!existingKeys.contains(key)) {
                    reagents.add(new ReagentItem(item, type, DEFAULT_MAX_TESTS, DEFAULT_MAX_TESTS));
                    added = true;
                }
            }
        }

        if (added || !file.exists()) {
            saveToCsv();
        }
    }

    public synchronized List<ReagentItem> getAllReagents() {
        return Collections.unmodifiableList(new ArrayList<>(reagents));
    }

    /**
     * 결과 수신 시 사용된 항목의 R1, R2 시약 카운트를 각각 1씩 차감한다.
     * @return 이미 잔량이 0 이어서 차감하지 못한 시약 이름 목록 (경고 표시용)
     */
    public synchronized List<String> deductReagents(List<TestItem> usedItems) {
        List<String> alreadyEmpty = new ArrayList<>();
        if (usedItems == null || usedItems.isEmpty()) return alreadyEmpty;

        Set<TestItem> itemSet = new HashSet<>(usedItems);
        boolean changed = false;

        for (ReagentItem r : reagents) {
            if (itemSet.contains(r.getItem())) {
                if (r.decrement()) {
                    changed = true;
                } else {
                    alreadyEmpty.add(r.getName());
                }
            }
        }

        if (changed) {
            saveToCsv();
            notifyListeners();
        }
        return alreadyEmpty;
    }

    /**
     * 특정 시약의 Max 테스트 수량 변동
     */
    public synchronized void updateMaxCapacity(TestItem item, ReagentItem.ReagentType type, int newMax) {
        for (ReagentItem r : reagents) {
            if (r.getItem() == item && r.getType() == type) {
                r.setMaxTests(newMax);
                saveToCsv();
                notifyListeners();
                break;
            }
        }
    }

    /**
     * 특정 시약 리셋: 변동된 수량 Max로 리셋
     */
    public synchronized void resetReagent(TestItem item, ReagentItem.ReagentType type) {
        for (ReagentItem r : reagents) {
            if (r.getItem() == item && r.getType() == type) {
                r.resetToMax();
                saveToCsv();
                notifyListeners();
                break;
            }
        }
    }

    /**
     * 전체 34개 시약 일괄 리셋 (각각의 Max 수량으로 복원)
     */
    public synchronized void resetAllReagents() {
        for (ReagentItem r : reagents) {
            r.resetToMax();
        }
        saveToCsv();
        notifyListeners();
    }

    public synchronized boolean hasWarningOrDepleted() {
        return reagents.stream().anyMatch(r -> r.getStatus() != ReagentItem.ReagentStatus.NORMAL);
    }

    public synchronized int getDepletedCount() {
        return (int) reagents.stream().filter(r -> r.getStatus() == ReagentItem.ReagentStatus.DEPLETED).count();
    }

    public synchronized int getLowCount() {
        return (int) reagents.stream().filter(r -> r.getStatus() == ReagentItem.ReagentStatus.LOW).count();
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void notifyListeners() {
        for (Runnable r : changeListeners) {
            r.run();
        }
    }

    private void loadFromCsv(File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // Header
            while ((line = reader.readLine()) != null) {
                ReagentItem item = ReagentItem.fromCsvLine(line);
                if (item != null) {
                    reagents.add(item);
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading reagents.csv: " + e.getMessage());
        }
    }

    private synchronized void saveToCsv() {
        StringBuilder sb = new StringBuilder();
        String nl = System.lineSeparator();
        sb.append("Item,Type,CurrentTests,MaxTests,LastUsedTime").append(nl);
        for (ReagentItem r : reagents) {
            sb.append(r.toCsvLine()).append(nl);
        }
        try {
            DataPaths.atomicWrite(DataPaths.file(REAGENTS_NAME), sb.toString());   // 쓰는 도중 종료되어도 기존 파일 보존
        } catch (Exception e) {
            System.err.println("Error writing reagents.csv: " + e.getMessage());
        }
    }
}
