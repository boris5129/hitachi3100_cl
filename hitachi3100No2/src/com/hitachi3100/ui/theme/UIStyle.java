package com.hitachi3100.ui.theme;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * 모던 클리니컬 UI 스타일, 색상 및 폰트 정의
 */
public class UIStyle {
    // 배경 및 패널 색상
    public static final Color COLOR_BG_APP     = new Color(245, 247, 250);
    public static final Color COLOR_SURFACE    = Color.WHITE;
    public static final Color COLOR_HEADER_BG  = new Color(30, 41, 59); // Deep Slate Navy
    public static final Color COLOR_HEADER_TXT = Color.WHITE;
    public static final Color COLOR_BORDER     = new Color(226, 232, 240);

    // 브랜드 & 악센트
    public static final Color COLOR_PRIMARY    = new Color(37, 99, 235); // Royal Blue
    public static final Color COLOR_PRIMARY_HOVER = new Color(29, 78, 216);

    // 임상 판정 및 상태 색상
    public static final Color COLOR_NORMAL_TEXT = new Color(22, 101, 52);
    public static final Color COLOR_NORMAL_BG   = new Color(220, 252, 231);

    public static final Color COLOR_HIGH_TEXT   = new Color(185, 28, 28);
    public static final Color COLOR_HIGH_BG     = new Color(254, 226, 226);

    public static final Color COLOR_LOW_TEXT    = new Color(30, 64, 175);
    public static final Color COLOR_LOW_BG      = new Color(219, 234, 254);

    public static final Color COLOR_WARN_TEXT   = new Color(194, 65, 12);
    public static final Color COLOR_WARN_BG     = new Color(254, 237, 213);

    public static final Color COLOR_DEPLETED_TEXT = new Color(153, 27, 27);
    public static final Color COLOR_DEPLETED_BG   = new Color(254, 202, 202);

    // 폰트
    public static final Font FONT_TITLE = new Font("Malgun Gothic", Font.BOLD, 16);
    public static final Font FONT_HEADER = new Font("Malgun Gothic", Font.BOLD, 14);
    public static final Font FONT_BOLD = new Font("Malgun Gothic", Font.BOLD, 12);
    public static final Font FONT_REGULAR = new Font("Malgun Gothic", Font.PLAIN, 12);
    public static final Font FONT_SMALL = new Font("Malgun Gothic", Font.PLAIN, 11);
    public static final Font FONT_MONO = new Font("Consolas", Font.BOLD, 12);

    public static JButton createPrimaryButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BOLD);
        btn.setBackground(COLOR_PRIMARY);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_PRIMARY_HOVER, 1),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)
        ));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    public static JButton createSecondaryButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BOLD);
        btn.setBackground(COLOR_SURFACE);
        btn.setForeground(new Color(51, 65, 85));
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_BORDER, 1),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)
        ));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    public static Border createCardBorder(String title) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(COLOR_BORDER, 1, true),
                        title,
                        0,
                        0,
                        FONT_HEADER,
                        new Color(30, 41, 59)
                ),
                new EmptyBorder(8, 10, 8, 10)
        );
    }
}
