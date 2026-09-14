package com.hyunolike.epolicy.infrastructure.pdfa;

import com.hyunolike.epolicy.domain.document.PdfArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * PdfArtifact → PDDocument 로딩 공통 규칙.
 *
 * <p>PDFBox 3 에서 {@code MemoryUsageSetting} 은 사라지고 {@code StreamCacheCreateFunction} 으로
 * 바뀌었다. 설계 문서가 언급한 {@code setupTempFileOnly()} 의 3.x 대응물이
 * {@link IOUtils#createTempFileOnlyStreamCache()} 다.
 *
 * <p>아티팩트가 디스크에 있으면 파일을 직접 랜덤 액세스로 읽는다. {@code readAllBytes()} 로 힙에
 * 올리면 임시파일 전략을 쓰는 의미가 사라진다 — 어차피 PDF 한 벌이 힙에 통째로 올라가기 때문이다.
 */
final class PdfDocuments {

    private PdfDocuments() {
    }

    static PDDocument load(PdfArtifact artifact, boolean tempFileCache) throws IOException {
        Optional<Path> location = artifact.location();
        if (location.isPresent()) {
            return tempFileCache
                    ? Loader.loadPDF(new RandomAccessReadBufferedFile(location.get()), "",
                            null, null, IOUtils.createTempFileOnlyStreamCache())
                    : Loader.loadPDF(new RandomAccessReadBufferedFile(location.get()));
        }
        try (InputStream in = artifact.openStream()) {
            byte[] bytes = in.readAllBytes();
            return tempFileCache
                    ? Loader.loadPDF(bytes, "", null, null, IOUtils.createTempFileOnlyStreamCache())
                    : Loader.loadPDF(bytes);
        }
    }
}
