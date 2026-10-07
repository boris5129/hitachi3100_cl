package com.hitachi3100;

import com.hitachi3100.ui.MainFrame;

import javax.swing.*;
import java.awt.*;

/**
 * Hitachi 3100 자동 분석기 제어 소프트웨어 메인 진입점
 */
public class Main {
    public static void main(String[] args) {
        // High DPI 및 텍스트 앤티앨리어싱 설정
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        SwingUtilities.invokeLater(() -> {
            try {
                // Windows 시스템 Look and Feel 적용
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
