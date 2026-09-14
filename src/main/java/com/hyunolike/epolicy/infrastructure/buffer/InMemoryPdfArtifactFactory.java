package com.hyunolike.epolicy.infrastructure.buffer;

import com.hyunolike.epolicy.application.port.out.PdfArtifactFactory;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 힙에 통째로 올리는 구현. 단건 API 경로의 기본값이고, 배치에서는 비교군이다. */
public class InMemoryPdfArtifactFactory implements PdfArtifactFactory {

    @Override
    public PdfArtifact create(PdfWriter writer) throws IOException {
        // 초기 용량을 넉넉히 잡아 증권 1장 크기(≈200KB)에서 배열 재할당이 반복되지 않게 한다.
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        writer.writeTo(out);
        return new InMemoryPdfArtifact(out.toByteArray());
    }

    @Override
    public String strategyName() {
        return "memory";
    }

    private record InMemoryPdfArtifact(byte[] bytes) implements PdfArtifact {

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public long size() {
            return bytes.length;
        }

        @Override
        public byte[] readAllBytes() {
            return bytes.clone();
        }

        @Override
        public void close() {
            // 힙 버퍼라 해제할 자원이 없다. GC 에 맡긴다.
        }
    }
}
