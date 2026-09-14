package com.hyunolike.epolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyunolike.epolicy.application.port.out.ArchiveMetadata;
import com.hyunolike.epolicy.application.port.out.PdfArchivePort;
import com.hyunolike.epolicy.application.port.out.PdfRenderPort;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import com.hyunolike.epolicy.support.IssuanceTestBase;
import com.hyunolike.epolicy.support.VeraPdf;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 인라인 SVG 와 PDF/A-1b.
 *
 * <p>"SVG 를 써도 되는가"는 문서에 적어 둘 문장이 아니라 검증할 명제다. 되는 경우와 안 되는 경우를
 * 한 쌍으로 고정한다 — 불투명 단색 SVG 는 통과하고, 투명도를 쓴 SVG 는 <b>반드시 걸려야</b> 한다.
 * 뒤쪽이 없으면 "PDF/A 는 투명도를 금지한다"는 주석은 그냥 믿음이다.
 */
class SvgRenderingTest extends IssuanceTestBase {

    private static final String OPAQUE_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="40" height="40">
              <path fill="#1a1a1a" d="M24 2 L44 9 V25 C44 36.5 35.2 43.4 24 46 C12.8 43.4 4 36.5 4 25 V9 Z"/>
            </svg>
            """;

    /** 딱 한 글자 차이 — fill-opacity. PDF/A-1 이 금지하는 투명도가 이 한 속성에서 생긴다. */
    private static final String TRANSLUCENT_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="40" height="40">
              <path fill="#1a1a1a" fill-opacity="0.45"
                    d="M24 2 L44 9 V25 C44 36.5 35.2 43.4 24 46 C12.8 43.4 4 36.5 4 25 V9 Z"/>
            </svg>
            """;

    @Autowired
    private PdfRenderPort pdfRenderPort;

    @Autowired
    private PdfArchivePort pdfArchivePort;

    @Test
    @DisplayName("불투명 단색 SVG 는 PDF/A-1b 를 통과한다")
    void opaqueSvgIsCompliant() throws IOException {
        VeraPdf.Report report = renderAndValidate(OPAQUE_SVG);

        assertThat(report.compliant()).as(report.describe()).isTrue();
    }

    @Test
    @DisplayName("투명도를 쓴 SVG 는 PDF/A-1b 검증에서 걸린다")
    void translucentSvgIsRejected() throws IOException {
        VeraPdf.Report report = renderAndValidate(TRANSLUCENT_SVG);

        assertThat(report.compliant())
                .as("투명도가 통과해 버리면 '로고는 단색으로'라는 제약이 근거 없는 당부가 된다.%n%s",
                        report.describe())
                .isFalse();
        assertThat(String.join("\n", report.failures()))
                .as("실패 사유가 투명도와 무관하다면 이 테스트는 다른 것을 잡고 있는 것이다")
                .containsAnyOf("transparen", "Transparen", "SMask", "blend", "CA");
    }

    private VeraPdf.Report renderAndValidate(String svg) throws IOException {
        String html = """
                <!DOCTYPE html>
                <html lang="ko"><head><meta charset="UTF-8"/><title>SVG 검증</title>
                <style>@page { size: A4; margin: 20mm; }
                body { font-family: 'NanumGothic', sans-serif; font-size: 10pt; }</style>
                </head><body><p>로고 검증용 문서</p>%s</body></html>
                """.formatted(svg);

        try (PdfArtifact rendered = pdfRenderPort.render(html);
             PdfArtifact archived = pdfArchivePort.toArchivalPdf(rendered, metadata());
             InputStream in = archived.openStream()) {
            return VeraPdf.validatePdfA1b(in);
        }
    }

    private static ArchiveMetadata metadata() {
        return new ArchiveMetadata("SVG 검증", "epolicy-issuer", "SVG", "svg",
                "epolicy-issuer", "epolicy-issuer", Instant.parse("2026-01-01T00:00:00Z"), "svg-test");
    }
}
