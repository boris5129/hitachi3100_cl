package com.hitachi3100.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Data Alarm 목록 (매뉴얼 15.1.10).
 * 주의: 알람 문자 'H' 는 "Standard 1 absorbance abnormal", 'L' 은 "ISE level error" 이며
 * 결과값의 High/Low 판정과는 무관하다. High/Low 는 참고치(범위)와 비교하여 호스트가 판정한다.
 */
public final class DataAlarm {
    private DataAlarm() {}

    private static final Map<Character, String> TABLE = new HashMap<>();

    static {
        TABLE.put('A', "ADC 이상");
        TABLE.put('Q', "Cell blank 이상");
        TABLE.put('V', "검체량 부족 (Sample short)");
        TABLE.put('T', "시약량 부족 (Reagent short)");
        TABLE.put('Z', "흡광도 초과 (Absorbance over)");
        TABLE.put('P', "Prozone 오류");
        TABLE.put('I', "전 구간 흡광도 한계 초과");
        TABLE.put('J', "2번째 이후 흡광도 한계 초과");
        TABLE.put('K', "3·4번째 이후 흡광도 한계 초과");
        TABLE.put('W', "직선성 이상 (9점 이상)");
        TABLE.put('F', "직선성 이상 (8점 이하)");
        TABLE.put('H', "Standard 1 흡광도 이상 (Calibration)");
        TABLE.put('U', "Duplicate 오류 (Calibration)");
        TABLE.put('S', "Standard 오류");
        TABLE.put('Y', "감도 오류 (Calibration)");
        TABLE.put('B', "Calibration 오류");
        TABLE.put('G', "SD 오류 (Calibration)");
        TABLE.put('N', "ISE noise 오류");
        TABLE.put('L', "ISE level 오류");
        TABLE.put('E', "ISE slope 오류");
        TABLE.put('R', "ISE slope 경고");
        TABLE.put('D', "ISE 내부표준 농도 이상");
        TABLE.put('&', "검체 값 이상 (Sample value abnormal)");
        TABLE.put('C', "Test-to-test 보정 오류");
        TABLE.put('M', "Test-to-test 보정 비활성");
        TABLE.put('$', "기술적 한계 초과 (Technical limit over)");
        TABLE.put('%', "계산 검사 오류");
        TABLE.put('X', "계산 불가 (Calculation disabled)");
    }

    public static String describe(String code) {
        if (code == null || code.isBlank()) return "";
        char c = code.trim().charAt(0);
        String d = TABLE.get(c);
        return d != null ? d : "알 수 없는 알람 '" + c + "'";
    }
}
