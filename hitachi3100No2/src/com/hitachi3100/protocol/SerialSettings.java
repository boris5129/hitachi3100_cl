package com.hitachi3100.protocol;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * RS-232C 통신 파라미터 (Table 15.1.1-1). 장비의
 * [Menu]→[Parameters]→[System]→[Com. Parameters] 설정과 반드시 일치해야 한다.
 * data/serial.properties 로 변경 가능:  baud=9600  dataBits=7  stopBits=2  parity=E  rtsCts=false
 */
public class SerialSettings {
    public int baudRate = Hitachi3100Constants.DEFAULT_BAUD_RATE;   // 4800 / 9600
    public int dataBits = Hitachi3100Constants.DEFAULT_DATA_BITS;   // 7 / 8
    public int stopBits = Hitachi3100Constants.DEFAULT_STOP_BITS;   // 1 / 2
    public char parity = 'E';                                        // N / E / O
    public boolean rtsCts = false;                                   // 케이블이 RTS/CTS 를 결선한 경우만 true

    public static SerialSettings load(File file) {
        SerialSettings s = new SerialSettings();
        if (file == null || !file.exists()) return s;
        Properties p = new Properties();
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            p.load(r);
            s.baudRate = Integer.parseInt(p.getProperty("baud", String.valueOf(s.baudRate)).trim());
            s.dataBits = Integer.parseInt(p.getProperty("dataBits", String.valueOf(s.dataBits)).trim());
            s.stopBits = Integer.parseInt(p.getProperty("stopBits", String.valueOf(s.stopBits)).trim());
            String par = p.getProperty("parity", String.valueOf(s.parity)).trim().toUpperCase();
            if (!par.isEmpty() && "NEO".indexOf(par.charAt(0)) >= 0) s.parity = par.charAt(0);
            s.rtsCts = Boolean.parseBoolean(p.getProperty("rtsCts", "false").trim());
        } catch (Exception e) {
            System.err.println("serial.properties 로드 실패, 기본값 사용: " + e.getMessage());
            return new SerialSettings();
        }
        return s;
    }

    @Override
    public String toString() {
        return baudRate + "bps " + dataBits + parity + stopBits;
    }
}
