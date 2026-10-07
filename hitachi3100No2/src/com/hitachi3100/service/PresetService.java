package com.hitachi3100.service;

import com.hitachi3100.model.PresetPanel;
import com.hitachi3100.model.TestItem;

import com.hitachi3100.util.DataPaths;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 검사 패널/세트 (Preset) 관리 서비스
 */
public class PresetService {
    private static final String PRESETS_NAME = "presets.properties";
    private final List<PresetPanel> presets = new ArrayList<>();

    public PresetService() {
        initPresets();
    }

    private void initPresets() {
        // 기본 3개 프리셋
        presets.add(PresetPanel.liverPanel());
        presets.add(PresetPanel.kidneyLipidPanel());
        presets.add(PresetPanel.diabetesSpecialPanel());

        loadUserPresets();
    }

    public synchronized List<PresetPanel> getAllPresets() {
        return Collections.unmodifiableList(new ArrayList<>(presets));
    }

    public synchronized void saveUserPreset(String name, List<TestItem> items) {
        if (name == null || name.isBlank() || items == null || items.isEmpty()) return;

        // 기존 동일 이름 제거
        presets.removeIf(p -> !p.isBuiltIn() && p.getName().equalsIgnoreCase(name.trim()));

        PresetPanel custom = new PresetPanel(name.trim(), items, false);
        presets.add(custom);
        persistUserPresets();
    }

    public synchronized void deleteUserPreset(String name) {
        presets.removeIf(p -> !p.isBuiltIn() && p.getName().equalsIgnoreCase(name.trim()));
        persistUserPresets();
    }

    private void loadUserPresets() {
        File file = DataPaths.file(PRESETS_NAME).toFile();
        if (!file.exists()) return;

        Properties props = new Properties();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            props.load(reader);
            for (String key : new java.util.TreeSet<>(props.stringPropertyNames())) {   // 항상 같은 순서로 표시
                String itemsStr = props.getProperty(key);
                List<TestItem> list = new ArrayList<>();
                for (String code : itemsStr.split(",")) {
                    TestItem.fromCode(code.trim()).ifPresent(list::add);
                }
                if (!list.isEmpty()) {
                    presets.add(new PresetPanel(key, list, false));
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading presets: " + e.getMessage());
        }
    }

    private void persistUserPresets() {
        Properties props = new Properties();
        for (PresetPanel p : presets) {
            if (!p.isBuiltIn()) {
                StringJoiner sj = new StringJoiner(",");
                for (TestItem ti : p.getItems()) {
                    sj.add(ti.getCode());
                }
                props.setProperty(p.getName(), sj.toString());
            }
        }

        try {
            StringWriter w = new StringWriter();
            props.store(w, "Hitachi 3100 User Custom Presets");
            DataPaths.atomicWrite(DataPaths.file(PRESETS_NAME), w.toString());
        } catch (Exception e) {
            System.err.println("Error saving presets: " + e.getMessage());
        }
    }
}
