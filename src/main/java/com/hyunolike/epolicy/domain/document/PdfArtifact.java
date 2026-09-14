package com.hyunolike.epolicy.domain.document;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 파이프라인 단계 사이를 오가는 PDF 한 벌.
 *
 * <p>{@code byte[]} 를 그대로 주고받지 않는 이유는 이것이 PoC 의 측정 대상이기 때문이다. chunk 100 ×
 * 500KB = 50MB 가 힙에 상주하고 그 위에 PDFBox 내부 버퍼가 얹힌다. 같은 인터페이스 뒤에 메모리 구현과
 * 임시파일 구현을 두면 {@code epolicy.pdf.buffer-strategy} 설정 하나로 두 전략의 힙 곡선을 비교할 수 있다.
 *
 * <p>임시파일 구현은 {@link #close()} 시점에 파일을 지운다. try-with-resources 를 빠뜨리면 디스크가 찬다.
 */
public interface PdfArtifact extends Closeable {

    /** 매 호출마다 처음부터 읽을 수 있는 새 스트림을 연다. */
    InputStream openStream() throws IOException;

    long size();

    /** 전체를 힙으로 올린다. 서명처럼 랜덤 액세스가 필요한 단계에서만 쓴다. */
    default byte[] readAllBytes() throws IOException {
        try (InputStream in = openStream()) {
            return in.readAllBytes();
        }
    }

    /**
     * 디스크에 실체가 있으면 그 경로.
     *
     * <p>PDFBox 는 파일 경로를 주면 랜덤 액세스로 읽어 힙을 거의 쓰지 않는다. 이 값이 있는 구현
     * (임시파일 전략)에서는 PDF 전체를 힙에 올리는 경로를 건너뛸 수 있다.
     */
    default Optional<Path> location() {
        return Optional.empty();
    }

    @Override
    void close();
}
