package com.hitachi3100.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * 데이터 파일 경로 및 안전한 파일 쓰기 유틸리티.
 * - 데이터 폴더는 시스템 프로퍼티 hitachi.data.dir 로 변경 가능 (기본값: data)
 * - atomicWrite: 임시 파일에 쓴 뒤 교체하므로 쓰기 도중 종료되어도 기존 파일이 깨지지 않음
 */
public final class DataPaths {
    private DataPaths() {}

    public static Path dir() {
        return Paths.get(System.getProperty("hitachi.data.dir", "data"));
    }

    public static Path file(String name) {
        return dir().resolve(name);
    }

    public static void atomicWrite(Path target, String content) throws IOException {
        Path abs = target.toAbsolutePath();
        Path parent = abs.getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, abs.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** CSV 한 줄 추가 (파일이 없으면 헤더 먼저 기록) */
    public static void appendLine(Path target, String header, String line) throws IOException {
        Path abs = target.toAbsolutePath();
        Files.createDirectories(abs.getParent());
        boolean exists = Files.exists(abs);
        StringBuilder sb = new StringBuilder();
        if (!exists) sb.append(header).append(System.lineSeparator());
        sb.append(line).append(System.lineSeparator());
        Files.writeString(abs, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
