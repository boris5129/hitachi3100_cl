package com.hitachi3100.protocol;

/**
 * Hitachi 3100 Host 통신 규격 상수 (Chapter 15 Host Communication)
 */
public final class Hitachi3100Constants {
    private Hitachi3100Constants() {}

    // 제어 문자
    public static final byte STX = 0x02;
    public static final byte ETX = 0x03;
    public static final byte CR  = 0x0D;
    public static final byte LF  = 0x0A;

    // 프레임 문자 (Table 15.1.3-1)
    public static final char FRAME_FR1 = '1';  // 결과 데이터 첫 텍스트
    public static final char FRAME_FR2 = '2';  // 결과 데이터 두 번째 텍스트
    public static final char FRAME_END = ':';  // 결과 데이터 마지막(또는 단일) 텍스트
    public static final char FRAME_SPE = ';';  // Specific Sample (TS 문의 / TS 지시)
    public static final char FRAME_RES = '<';  // Host -> AU 특정 검체 결과 요청
    public static final char FRAME_ANY = '>';  // AU -> Host 긍정 응답(ACK 상당)
    public static final char FRAME_MOR = ' ';  // Host -> AU 긍정 응답 ($20)
    public static final char FRAME_REP = '?';  // 재전송 요청(NAK 상당)

    // Function Character (Table 15.1.5-11, 2바이트: 문자 + 공백)
    // TS 문의/지시 (대문자)
    public static final String FU_ROUTINE_ID     = "A ";  // Routine, ID 있음(바코드 리더 사용)
    public static final String FU_STAT_ID        = "D ";  // Stat,    ID 있음
    public static final String FU_ROUTINE_NO_ID  = "N ";  // Routine, ID 없음(Sample No. 모드)
    public static final String FU_STAT_NO_ID     = "Q ";  // Stat,    ID 없음
    // 결과 데이터 (소문자)
    public static final String FU_RESULT_ROUTINE_ID    = "a ";
    public static final String FU_RESULT_STAT_ID       = "d ";
    public static final String FU_RESULT_ROUTINE_NO_ID = "n ";
    public static final String FU_RESULT_STAT_NO_ID    = "q ";
    public static final String FU_RESULT_CONTROL       = "f ";  // Control sample

    // 텍스트 구성 길이
    public static final int SAMPLE_INFO_LENGTH = 37;
    public static final int CHANNEL_COUNT_LENGTH = 3;
    public static final int TEST_SELECTION_LENGTH = 37;
    public static final int ZERO_FIELD_LENGTH = 5;
    public static final int RESULT_RECORD_LENGTH = 10;   // Ch(3) + Value(6) + Alarm(1)
    public static final int MAX_FRAME_LENGTH = 1400;     // 1280 byte 모드 + 여유

    // 입력 범위 (Table 15.1.5-12/13, 15.1.6)
    public static final int MAX_POSITION = 35;           // Position No. 1~35
    public static final int MAX_ID_LENGTH = 13;
    public static final int MAX_ROUTINE_SAMPLE_NO = 1000;
    public static final int MAX_STAT_SAMPLE_NO = 400;

    // 통신 파라미터 기본값 (Table 15.1.1-1)
    public static final int DEFAULT_BAUD_RATE = 9600;
    public static final int DEFAULT_DATA_BITS = 7;
    public static final int DEFAULT_STOP_BITS = 2;
    public static final int DEFAULT_PARITY = 2; // Even

    // 타이밍 (15.1.4, 15.1.8 (3))
    public static final long MIN_HOST_RESPONSE_DELAY_MS = 100;   // AU 수신 후 최소 100ms 대기 후 응답
    public static final long DEFAULT_HOST_RESPONSE_DELAY_MS = 150;
    public static final int  DEFAULT_RETRY_COUNT = 2;
    public static final long DEFAULT_LINK_TIMEOUT_MS = 30_000;    // Communication cycle 최대 10초 x 3
    public static final int  MAX_CONSECUTIVE_REP = 3;             // 15.1.6: REP 3회 연속 시 AU 통신 정지
}
