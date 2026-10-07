package com.hitachi3100.ui.theme;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

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

    // 버튼 색상 (초록 계열, 흰 글씨)
    public static final Color COLOR_BUTTON       = new Color(22, 163, 74);
    public static final Color COLOR_BUTTON_HOVER = new Color(21, 128, 61);
    public static final Color COLOR_BUTTON_ALT       = new Color(5, 150, 105);
    public static final Color COLOR_BUTTON_ALT_HOVER = new Color(4, 120, 87);
    public static final Color COLOR_BUTTON_DISABLED  = new Color(148, 163, 184);

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

    /**
     * 버튼에 초록 배경 + 흰 굵은 글씨를 적용한다.
     * BasicButtonUI 를 사용하므로 Windows 등 시스템 룩앤필이 배경색을 무시해도 색이 그대로 보인다.
     */
    public static void styleButton(JButton btn, Color normal, Color hover) {
        btn.setUI(new BasicButtonUI());
        btn.setFont(FONT_BOLD);
        btn.setForeground(Color.WHITE);
        btn.setBackground(normal);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(hover, 1),
                BorderFactory.createEmptyBorder(6, 14, 6, 14)
        ));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (btn.isEnabled()) btn.setBackground(hover);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (btn.isEnabled()) btn.setBackground(normal);
            }
        });
        btn.addPropertyChangeListener("enabled", e -> btn.setBackground(btn.isEnabled() ? normal : COLOR_BUTTON_DISABLED));
    }

    public static JButton createPrimaryButton(String text) {
        JButton btn = new JButton(text);
        styleButton(btn, COLOR_BUTTON, COLOR_BUTTON_HOVER);
        return btn;
    }

    public static JButton createSecondaryButton(String text) {
        JButton btn = new JButton(text);
        styleButton(btn, COLOR_BUTTON_ALT, COLOR_BUTTON_ALT_HOVER);
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
