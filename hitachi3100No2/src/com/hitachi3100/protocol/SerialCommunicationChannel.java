package com.hitachi3100.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RS-232C 시리얼 / 직렬-이더넷 브리지 통신 채널.
 *
 *  - "COM3" 같은 포트 이름: jSerialComm 라이브러리(lib/jSerialComm-*.jar)가 클래스패스에 있을 때 실제 포트를 연다.
 *    (리플렉션으로 호출하므로 jar 가 없어도 컴파일은 되며, 없으면 연결 시 명확한 오류를 낸다. 연결된 척 하지 않는다.)
 *  - "192.168.0.10:4001" 같은 host:port: TCP 직렬 디바이스 서버/COM 브리지에 연결한다.
 *  - 수신 바이트는 FrameAssembler 로 프레임 단위로 잘라 리스너에 전달한다.
 */
public class SerialCommunicationChannel implements IHitachiChannel {

    private final List<HitachiChannelListener> listeners = new CopyOnWriteArrayList<>();
    private volatile String portName;
    private final SerialSettings settings;
    private volatile boolean connected = false;
    private Thread readThread;
    private Socket tcpSocket;
    private Object serialPort;          // com.fazecast.jSerialComm.SerialPort (리플렉션)
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean tcpMode;
    private final FrameAssembler assembler = new FrameAssembler();
    private final Object writeLock = new Object();

    public SerialCommunicationChannel(String portName, SerialSettings settings) {
        this.portName = portName;
        this.settings = settings != null ? settings : new SerialSettings();
    }

    public SerialCommunicationChannel(String portName, int baudRate) {
        this(portName, settingsWithBaud(baudRate));
    }

    private static SerialSettings settingsWithBaud(int baud) {
        SerialSettings s = new SerialSettings();
        s.baudRate = baud;
        return s;
    }

    public void setPortName(String portName) {
        this.portName = portName;
    }

    public String getPortName() {
        return portName;
    }

    @Override
    public synchronized void connect() throws Exception {
        if (connected) return;
        assembler.reset();

        if (portName.contains(":")) {
            String[] parts = portName.split(":");
            if (parts.length != 2) throw new IOException("TCP 브리지 주소 형식은 host:port 입니다: " + portName);
            tcpSocket = new Socket(parts[0], Integer.parseInt(parts[1].trim()));
            inputStream = tcpSocket.getInputStream();
            outputStream = tcpSocket.getOutputStream();
            tcpMode = true;
        } else {
            openSerialPortViaJSerialComm();
            tcpMode = false;
        }
        connected = true;
        startReaderThread();
        notifyStatus(true, "RS-232C (" + portName + (tcpMode ? " TCP 브리지" : " @ " + settings) + ") 연결됨");
    }

    private void openSerialPortViaJSerialComm() throws Exception {
        Class<?> cls;
        try {
            cls = Class.forName("com.fazecast.jSerialComm.SerialPort");
        } catch (ClassNotFoundException e) {
            throw new IOException("시리얼 포트 라이브러리를 찾을 수 없습니다. jSerialComm jar 를 lib 폴더에 넣고 "
                    + "클래스패스에 추가하세요 (java -cp \"bin;lib/*\" ...). 또는 'host:port' 형식의 TCP 브리지를 사용하세요.");
        }
        Method getCommPort = cls.getMethod("getCommPort", String.class);
        Object port = getCommPort.invoke(null, portName);

        int stop = cls.getField(settings.stopBits == 1 ? "ONE_STOP_BIT" : "TWO_STOP_BITS").getInt(null);
        int par = cls.getField(settings.parity == 'E' ? "EVEN_PARITY" : settings.parity == 'O' ? "ODD_PARITY" : "NO_PARITY").getInt(null);
        cls.getMethod("setComPortParameters", int.class, int.class, int.class, int.class)
                .invoke(port, settings.baudRate, settings.dataBits, stop, par);

        int semiBlocking = cls.getField("TIMEOUT_READ_SEMI_BLOCKING").getInt(null);
        cls.getMethod("setComPortTimeouts", int.class, int.class, int.class).invoke(port, semiBlocking, 200, 0);

        if (settings.rtsCts) {
            int flow = cls.getField("FLOW_CONTROL_RTS_ENABLED").getInt(null) | cls.getField("FLOW_CONTROL_CTS_ENABLED").getInt(null);
            cls.getMethod("setFlowControl", int.class).invoke(port, flow);
        }

        boolean opened = (Boolean) cls.getMethod("openPort").invoke(port);
        if (!opened) {
            throw new IOException("포트를 열 수 없습니다: " + portName + " (다른 프로그램이 사용 중이거나 포트 이름이 잘못되었습니다)");
        }
        try {
            cls.getMethod("setRTS").invoke(port);
            cls.getMethod("setDTR").invoke(port);
        } catch (Exception ignored) {
        }
        serialPort = port;
        inputStream = (InputStream) cls.getMethod("getInputStream").invoke(port);
        outputStream = (OutputStream) cls.getMethod("getOutputStream").invoke(port);
    }

    private void startReaderThread() {
        final InputStream in = inputStream;
        readThread = new Thread(() -> {
            byte[] buffer = new byte[512];
            while (connected) {
                try {
                    int len = in.read(buffer);
                    if (len < 0) {
                        if (tcpMode) throw new IOException("원격 연결이 종료되었습니다");
                        Thread.sleep(10);
                        continue;
                    }
                    if (len == 0) continue;
                    for (byte[] frame : assembler.feed(buffer, len)) {
                        log("RECV [AU->HOST]", frame, Hitachi3100Frame.summarize(frame));
                        for (HitachiChannelListener l : listeners) {
                            try {
                                l.onFrameReceived(frame);
                            } catch (RuntimeException e) {
                                com.hitachi3100.util.AppLog.error("Channel listener error: " + e);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (connected) {
                        notifyStatus(false, "통신 수신 중 오류 발생: " + e.getMessage());
                        disconnect();
                    }
                    break;
                }
            }
        }, "HitachiSerialReader");
        readThread.setDaemon(true);
        readThread.start();
    }

    @Override
    public synchronized void disconnect() {
        boolean was = connected;
        connected = false;
        try {
            if (serialPort != null) {
                serialPort.getClass().getMethod("closePort").invoke(serialPort);
            }
        } catch (Exception ignored) {
        }
        try {
            if (tcpSocket != null) tcpSocket.close();
        } catch (Exception ignored) {
        }
        serialPort = null;
        tcpSocket = null;
        inputStream = null;
        outputStream = null;
        assembler.reset();
        if (was) notifyStatus(false, "RS-232C (" + portName + ") 연결 해제됨");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean sendFrame(byte[] frame) {
        OutputStream out = outputStream;
        if (!connected || out == null) {
            notifyStatus(false, "전송 실패: 포트가 연결되어 있지 않습니다.");
            return false;
        }
        try {
            synchronized (writeLock) {
                out.write(frame);
                out.flush();
            }
            log("SEND [HOST->AU]", frame, Hitachi3100Frame.summarize(frame));
            return true;
        } catch (Exception e) {
            notifyStatus(false, "데이터 전송 실패: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void addListener(HitachiChannelListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(HitachiChannelListener listener) {
        listeners.remove(listener);
    }

    @Override
    public String getChannelName() {
        return "RS-232C (" + portName + ")";
    }

    private void notifyStatus(boolean conn, String msg) {
        for (HitachiChannelListener l : listeners) {
            l.onStatusChanged(conn, msg);
        }
    }

    private void log(String dir, byte[] raw, String summary) {
        for (HitachiChannelListener l : listeners) {
            l.onRawLog(dir, raw, summary);
        }
    }
}
