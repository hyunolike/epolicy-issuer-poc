package com.hyunolike.epolicy.infrastructure.buffer;

import com.hyunolike.epolicy.application.port.out.PdfArtifactFactory;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 임시파일로 흘리는 구현. 힙에는 8KB 버퍼만 남는다.
 *
 * <p>대신 close() 를 빠뜨리면 파일이 쌓인다. 힙 누수를 디스크 누수로 바꾼 것이고, 배치에서 어느 쪽이
 * 나은지는 측정으로 답한다.
 */
public class TempFilePdfArtifactFactory implements PdfArtifactFactory {

    private static final Logger log = LoggerFactory.getLogger(TempFilePdfArtifactFactory.class);

    private final Path spoolDirectory;

    public TempFilePdfArtifactFactory(Path spoolDirectory) {
        this.spoolDirectory = spoolDirectory;
        try {
            Files.createDirectories(spoolDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("PDF 임시 디렉터리를 만들 수 없습니다: " + spoolDirectory, e);
        }
    }

    @Override
    public PdfArtifact create(PdfWriter writer) throws IOException {
        // 생성자에서 한 번 만들어 두는 것으로 끝내지 않는다. 스풀은 tmp 정리 스크립트나 운영자의
        // 손에 언제든 지워질 수 있는 자리고, 그때 발급 전체가 NoSuchFileException 으로 죽는다.
        // stat 한 번의 비용으로 그 장애를 없앤다.
        Files.createDirectories(spoolDirectory);
        Path file = Files.createTempFile(spoolDirectory, "epolicy-", ".pdf");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 8192)) {
            writer.writeTo(out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        return new TempFilePdfArtifact(file, Files.size(file));
    }

    @Override
    public String strategyName() {
        return "temp-file";
    }

    private record TempFilePdfArtifact(Path file, long size) implements PdfArtifact {

        @Override
        public InputStream openStream() throws IOException {
            return new BufferedInputStream(Files.newInputStream(file), 8192);
        }

        @Override
        public java.util.Optional<Path> location() {
            return java.util.Optional.of(file);
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // 삭제 실패가 발급 실패로 번지면 안 된다. 경고만 남기고 스풀 청소에 맡긴다.
                log.warn("PDF 임시파일 삭제 실패: {}", file, e);
            }
        }
    }
}
