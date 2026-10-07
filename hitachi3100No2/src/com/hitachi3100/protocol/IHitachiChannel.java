package com.hitachi3100.protocol;

import java.util.EventListener;

/**
 * Hitachi 3100 통신 채널 추상 인터페이스 (RS-232C 실제 포트 / TCP 브리지 / 내장 시뮬레이터 공통).
 * 채널은 "완성된 프레임 단위"로만 상위에 전달한다 (BCC 검증은 상위 프로토콜 계층에서 수행).
 */
public interface IHitachiChannel {

    interface HitachiChannelListener extends EventListener {
        /** STX ... ETX BCC 로 완성된 원본 프레임 (채널 reader 스레드에서 호출됨) */
        void onFrameReceived(byte[] rawFrame);
        void onStatusChanged(boolean connected, String message);
        void onRawLog(String direction, byte[] rawData, String summary);
    }

    void connect() throws Exception;
    void disconnect();
    boolean isConnected();

    /** @return 전송 성공 여부 (연결 안 됨/쓰기 실패 시 false) */
    boolean sendFrame(byte[] frame);

    void addListener(HitachiChannelListener listener);
    void removeListener(HitachiChannelListener listener);
    String getChannelName();
}
