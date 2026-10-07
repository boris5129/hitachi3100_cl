package com.hitachi3100.protocol;

/** 수신 프레임이 매뉴얼 규격(15.1.5, 15.1.6)에 맞지 않을 때 발생 */
public class FrameFormatException extends RuntimeException {
    public FrameFormatException(String message) {
        super(message);
    }
}
