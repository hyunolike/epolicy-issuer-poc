package com.hyunolike.epolicy.infrastructure.storage;

import com.hyunolike.epolicy.domain.document.PdfArtifact;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 보관소에 실제로 저장된 파일을 가리키는 아티팩트.
 *
 * <p>임시파일 아티팩트와 달리 close() 가 파일을 지우지 않는다. 같은 인터페이스지만 소유권이 다르다 —
 * 이쪽은 보관소가 주인이고 읽기만 빌려준다. 둘을 한 클래스로 합치면 언젠가 보관 파일을 지운다.
 */
public final class FileSystemPdfArtifact implements PdfArtifact {

    private final Path file;
    private final long size;

    public FileSystemPdfArtifact(Path file) {
        this.file = file;
        try {
            this.size = Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException("보관 파일 크기를 읽을 수 없습니다: " + file, e);
        }
    }

    @Override
    public InputStream openStream() throws IOException {
        return new BufferedInputStream(Files.newInputStream(file), 8192);
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public Optional<Path> location() {
        return Optional.of(file);
    }

    @Override
    public void close() {
        // 보관 파일은 지우지 않는다.
    }
}
