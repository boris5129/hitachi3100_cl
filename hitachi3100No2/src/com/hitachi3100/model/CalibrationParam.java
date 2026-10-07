package com.hitachi3100.model;

/**
 * 항목별 기준값 (Target, Min, Max) 및 Calibration 설정값 (K-Factor, Conc)
 */
public class CalibrationParam {
    private final TestItem item;
    private double target;
    private double min;
    private double max;
    private double kFactor;
    private double stdConc;

    public CalibrationParam(TestItem item, double target, double min, double max, double kFactor, double stdConc) {
        this.item = item;
        this.target = target;
        this.min = min;
        this.max = max;
        this.kFactor = kFactor;
        this.stdConc = stdConc;
    }

    public static CalibrationParam createDefault(TestItem item) {
        double tgt = item.getDefaultTarget();
        double span = item.getRefHigh() - item.getRefLow();
        double mn = Math.max(0, tgt - span * 0.25);
        double mx = tgt + span * 0.25;
        return new CalibrationParam(item, tgt, mn, mx, 1.0, tgt);
    }

    public TestItem getItem() {
        return item;
    }

    public double getTarget() {
        return target;
    }

    public void setTarget(double target) {
        this.target = target;
    }

    public double getMin() {
        return min;
    }

    public void setMin(double min) {
        this.min = min;
    }

    public double getMax() {
        return max;
    }

    public void setMax(double max) {
        this.max = max;
    }

    public double getkFactor() {
        return kFactor;
    }

    public void setkFactor(double kFactor) {
        this.kFactor = kFactor;
    }

    public double getStdConc() {
        return stdConc;
    }

    public void setStdConc(double stdConc) {
        this.stdConc = stdConc;
    }
}
