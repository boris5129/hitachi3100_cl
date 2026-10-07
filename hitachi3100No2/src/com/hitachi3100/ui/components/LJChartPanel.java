package com.hitachi3100.ui.components;

import com.hitachi3100.model.QCRecord;
import com.hitachi3100.model.TestItem;
import com.hitachi3100.ui.theme.UIStyle;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Levey-Jennings (L-J) 정도관리 관리도 그래픽 렌더링 컴포넌트
 */
public class LJChartPanel extends JPanel {

    private TestItem currentItem = TestItem.AST;
    private double target = 30.0;
    private double min = 20.0;
    private double max = 40.0;
    private final List<QCRecord> records = new ArrayList<>();

    // 툴팁 및 마우스 인터랙션용 포인트 캐시
    private final List<PointInfo> pointCache = new ArrayList<>();

    private static class PointInfo {
        Ellipse2D shape;
        QCRecord record;
        double x, y;
        PointInfo(Ellipse2D shape, QCRecord record, double x, double y) {
            this.shape = shape;
            this.record = record;
            this.x = x;
            this.y = y;
        }
    }

    public LJChartPanel() {
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createLineBorder(UIStyle.COLOR_BORDER, 1));
        setToolTipText("");

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                PointInfo hovered = null;
                for (PointInfo pi : pointCache) {
                    if (pi.shape.contains(e.getPoint())) {
                        hovered = pi;
                        break;
                    }
                }
                if (hovered != null) {
                    QCRecord r = hovered.record;
                    setToolTipText(String.format(java.util.Locale.ROOT, "<html><b>[%s] %s</b><br>측정치: <b>%.2f %s</b><br>기준 Target: %.2f<br>차이: %+.2f<br>상태: %s</html>",
                            r.getQcLevel(), r.getFormattedTimestamp(), r.getValue(), r.getItem().getUnit(), r.getTarget(), r.getDiff(), r.getStatus()));
                } else {
                    setToolTipText(null);
                }
            }
        });
    }

    public void updateData(TestItem item, double target, double min, double max, List<QCRecord> records) {
        this.currentItem = item;
        this.target = target;
        this.min = min;
        this.max = max;
        this.records.clear();
        if (records != null) {
            this.records.addAll(records);
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        pointCache.clear();

        int w = getWidth();
        int h = getHeight();

        int paddingLeft = 70;
        int paddingRight = 30;
        int paddingTop = 40;
        int paddingBottom = 40;

        int plotW = w - paddingLeft - paddingRight;
        int plotH = h - paddingTop - paddingBottom;

        if (plotW <= 20 || plotH <= 20) {
            g2.dispose();
            return;
        }

        // 헤더 타이틀
        g2.setFont(UIStyle.FONT_HEADER);
        g2.setColor(new Color(30, 41, 59));
        String title = currentItem.getCode() + " (" + currentItem.getFullName() + ") - Levey-Jennings Control Chart";
        g2.drawString(title, paddingLeft, paddingTop - 15);

        // SD 계산: (Max - Min) / 6
        double sd = (max - min) / 6.0;
        if (sd <= 0.0001) sd = 1.0;

        double plus3s = target + 3 * sd;
        double plus2s = target + 2 * sd;
        double plus1s = target + 1 * sd;
        double mean   = target;
        double minus1s = target - 1 * sd;
        double minus2s = target - 2 * sd;
        double minus3s = target - 3 * sd;

        double yMax = plus3s + sd * 0.5;
        double yMin = minus3s - sd * 0.5;

        // 플롯 배경
        g2.setColor(new Color(250, 250, 252));
        g2.fillRect(paddingLeft, paddingTop, plotW, plotH);
        g2.setColor(UIStyle.COLOR_BORDER);
        g2.drawRect(paddingLeft, paddingTop, plotW, plotH);

        // 가이드 라인 그리기
        drawGuideline(g2, paddingLeft, plotW, paddingTop, plotH, yMin, yMax, plus3s, "+3s", new Color(220, 38, 38), true);
        drawGuideline(g2, paddingLeft, plotW, paddingTop, plotH, yMin, yMax, plus2s, "+2s", new Color(217, 119, 6), true);
        drawGuideline(g2, paddingLeft, plotW, paddingTop, plotH, yMin, yMax, plus1s, "+1s", new Color(148, 163, 184), true);
        drawGuideline(g2, paddingLeft, plotW, paddingTop, plotH, yMin, yMax, mean,   "Target", new Color(22, 163, 74), false);
        drawGuideline(g2, paddingLeft, plotW, paddingTop, plotH, yMin, yMax, minus1s, "-1s", new Color(148, 163, 184), true);
        drawGuideline(g2, paddingLeft, plotW, paddingTop, plotH, yMin, yMax, minus2s, "-2s", new Color(217, 119, 6), true);
        drawGuideline(g2, paddingLeft, plotW, paddingTop, plotH, yMin, yMax, minus3s, "-3s", new Color(220, 38, 38), true);

        // 데이터가 없는 경우 안내 문구
        if (records.isEmpty()) {
            g2.setFont(UIStyle.FONT_BOLD);
            g2.setColor(Color.GRAY);
            String emptyMsg = "수신된 QC 데이터가 없습니다. [탭 1]에서 QC1/QC2/CAL 오더를 실행하면 자동 표시됩니다.";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(emptyMsg, paddingLeft + (plotW - fm.stringWidth(emptyMsg)) / 2, paddingTop + plotH / 2);
            g2.dispose();
            return;
        }

        // 포인트 플롯 및 선 연결
        int n = records.size();
        double xStep = (n > 1) ? (double) plotW / (n - 1) : plotW / 2.0;

        List<Point2DDouble> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            QCRecord r = records.get(i);
            double x = (n > 1) ? paddingLeft + (i * xStep) : paddingLeft + plotW / 2.0;
            double normY = (r.getValue() - yMin) / (yMax - yMin);
            double y = paddingTop + plotH - (normY * plotH);
            points.add(new Point2DDouble(x, y, r));
        }

        // 연결선 그리기
        g2.setStroke(new BasicStroke(2.0f));
        g2.setColor(new Color(37, 99, 235));
        for (int i = 0; i < points.size() - 1; i++) {
            Point2DDouble p1 = points.get(i);
            Point2DDouble p2 = points.get(i + 1);
            g2.draw(new Line2D.Double(p1.x, p1.y, p2.x, p2.y));
        }

        // 포인트 마커 그리기
        for (int i = 0; i < points.size(); i++) {
            Point2DDouble pt = points.get(i);
            QCRecord r = pt.record;

            Color dotColor = new Color(22, 163, 74); // In control (Green)
            if ("Warning(2SD)".equalsIgnoreCase(r.getStatus())) {
                dotColor = new Color(217, 119, 6); // Warning (Orange)
            } else if ("Out-of-Control".equalsIgnoreCase(r.getStatus())) {
                dotColor = new Color(220, 38, 38); // Out of control (Red)
            }

            double radius = 5.0;
            Ellipse2D circle = new Ellipse2D.Double(pt.x - radius, pt.y - radius, radius * 2, radius * 2);
            g2.setColor(dotColor);
            g2.fill(circle);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(circle);

            // 캐시에 등록 (툴팁용)
            pointCache.add(new PointInfo(circle, r, pt.x, pt.y));

            // X축 번호 라벨
            g2.setFont(UIStyle.FONT_SMALL);
            g2.setColor(Color.DARK_GRAY);
            String idxStr = String.valueOf(i + 1);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(idxStr, (float)(pt.x - fm.stringWidth(idxStr) / 2.0), paddingTop + plotH + 15);
        }

        g2.dispose();
    }

    private void drawGuideline(Graphics2D g2, int left, int width, int top, int height,
                               double yMin, double yMax, double value, String label, Color color, boolean dashed) {
        if (value < yMin || value > yMax) return;

        double normY = (value - yMin) / (yMax - yMin);
        int y = (int) (top + height - (normY * height));

        Stroke oldStroke = g2.getStroke();
        if (dashed) {
            float[] dash = {4.0f, 4.0f};
            g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dash, 0.0f));
        } else {
            g2.setStroke(new BasicStroke(1.8f));
        }

        g2.setColor(color);
        g2.drawLine(left, y, left + width, y);
        g2.setStroke(oldStroke);

        // Y축 라벨
        g2.setFont(UIStyle.FONT_SMALL);
        String labelText = String.format(java.util.Locale.ROOT, "%s (%.1f)", label, value);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(labelText, left - fm.stringWidth(labelText) - 6, y + 4);
    }

    private static class Point2DDouble {
        double x, y;
        QCRecord record;
        Point2DDouble(double x, double y, QCRecord record) {
            this.x = x;
            this.y = y;
            this.record = record;
        }
    }
}
