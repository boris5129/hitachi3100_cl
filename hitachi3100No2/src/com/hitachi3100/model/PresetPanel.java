package com.hitachi3100.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 검사 패널 / 프리셋 모델
 */
public class PresetPanel {
    private final String name;
    private final List<TestItem> items;
    private final boolean isBuiltIn;

    public PresetPanel(String name, List<TestItem> items, boolean isBuiltIn) {
        this.name = name;
        this.items = new ArrayList<>(items != null ? items : Collections.emptyList());
        this.isBuiltIn = isBuiltIn;
    }

    public static PresetPanel liverPanel() {
        return new PresetPanel("간기능 패널", Arrays.asList(
                TestItem.AST, TestItem.ALT, TestItem.GGT,
                TestItem.T_BIL, TestItem.D_BIL,
                TestItem.ALBUMIN, TestItem.TP
        ), true);
    }

    public static PresetPanel kidneyLipidPanel() {
        return new PresetPanel("신장/지질 패널", Arrays.asList(
                TestItem.BUN, TestItem.CREA,
                TestItem.HDL_C, TestItem.LDL_C,
                TestItem.CHOL, TestItem.TG
        ), true);
    }

    public static PresetPanel diabetesSpecialPanel() {
        return new PresetPanel("당뇨/특수 패널", Arrays.asList(
                TestItem.GLUCOSE, TestItem.CA,
                TestItem.SSA, TestItem.IMA
        ), true);
    }

    public String getName() {
        return name;
    }

    public List<TestItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public boolean isBuiltIn() {
        return isBuiltIn;
    }

    @Override
    public String toString() {
        return name;
    }
}
