package com.hitachi3100.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static com.hitachi3100.protocol.Hitachi3100Constants.*;

/**
 * 시리얼/TCP 바이트 스트림을 프레임(STX ... ETX BCC) 단위로 잘라 주는 조립기.
 * - 프레임이 여러 번에 나뉘어 도착하거나, 여러 프레임이 한 번에 붙어 도착해도 처리한다.
 * - STX 이전의 잡음 바이트는 버리고, 프레임 도중 STX 가 다시 나타나면 새 프레임으로 재동기화한다.
 * - BCC 검증은 하지 않고(호스트가 REP 로 응답할 수 있도록) 구조만 맞으면 그대로 반환한다.
 */
public class FrameAssembler {
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private boolean inFrame = false;
    private boolean gotEtx = false;
    private long lastByteNanos = 0;
    private long idleTimeoutNanos = 3_000_000_000L; // 프레임 도중 3초 이상 입력이 없으면 폐기
    private int discardedBytes = 0;
    private int resyncCount = 0;

    public synchronized List<byte[]> feed(byte[] data, int len) {
        List<byte[]> out = new ArrayList<>();
        long now = System.nanoTime();
        if (inFrame && lastByteNanos != 0 && now - lastByteNanos > idleTimeoutNanos) {
            discardedBytes += buf.size();
            reset();
        }
        if (len > 0) lastByteNanos = now;

        for (int i = 0; i < len; i++) {
            int b = data[i] & 0x7F;   // 7bit 통신: 패리티 비트 제거
            if (!inFrame) {
                if (b == STX) {
                    buf.reset();
                    buf.write(b);
                    inFrame = true;
                    gotEtx = false;
                } else {
                    discardedBytes++;
                }
                continue;
            }
            if (gotEtx) {
                buf.write(data[i] & 0xFF);   // BCC 는 원본 바이트 그대로
                out.add(buf.toByteArray());
                reset();
                continue;
            }
            if (b == STX) {               // 프레임 도중 STX: 앞부분 폐기 후 재동기화
                discardedBytes += buf.size();
                resyncCount++;
                buf.reset();
                buf.write(b);
                continue;
            }
            buf.write(b);
            if (b == ETX) gotEtx = true;
            if (buf.size() > MAX_FRAME_LENGTH) {
                discardedBytes += buf.size();
                reset();
            }
        }
        return out;
    }

    public synchronized void reset() {
        buf.reset();
        inFrame = false;
        gotEtx = false;
    }

    public synchronized int getDiscardedBytes() {
        return discardedBytes;
    }

    public synchronized int getResyncCount() {
        return resyncCount;
    }
}
