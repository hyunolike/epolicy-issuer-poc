package com.hyunolike.epolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueCommand;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueResult;
import com.hyunolike.epolicy.application.port.out.DocumentStoragePort;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import com.hyunolike.epolicy.domain.document.TemplateVersion;
import com.hyunolike.epolicy.support.IssuanceTestBase;
import com.hyunolike.epolicy.support.TestFixtures;
import com.hyunolike.epolicy.support.VeraPdf;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 템플릿 버전 고정 (설계 결정 ④).
 *
 * <p>v2 가 생기면서 이 규칙이 처음으로 실제 의미를 갖게 됐다. v1 로 발급된 증권을 재발급하면
 * <b>v1 양식으로</b> 렌더되어야 같은 바이트가 나온다. v1 에 로고를 얹는 식으로 기존 디렉터리를
 * 고쳤다면 과거 발급분 전체의 재현성이 한 번에 깨졌을 것이다 — 그래서 v2 를 새로 만들었다.
 *
 * <p>이 테스트가 지키는 것은 "버전 디렉터리를 건드리지 말라"는 문서상의 당부가 아니라, 건드렸을 때
 * 실제로 깨지는 값(contentHash)이다.
 */
class TemplateVersioningTest extends IssuanceTestBase {

    @Autowired
    private IssuePolicyUseCase issuePolicyUseCase;

    @Autowired
    private DocumentStoragePort documentStoragePort;

    @BeforeEach
    void seedContract() {
        contractRepository.save(TestFixtures.contractEntity());
    }

    @Test
    @DisplayName("v2 가 생겨도 v1 발급분은 같은 바이트로 재현된다")
    void oldVersionStaysReproducible() {
        String firstV1 = issueWith(TemplateVersion.V1).document().contentHash().hex();
        String v2 = issueWith(TemplateVersion.of("v2")).document().contentHash().hex();
        String secondV1 = issueWith(TemplateVersion.V1).document().contentHash().hex();

        assertThat(v2)
                .as("양식이 달라졌으므로 해시도 달라야 한다. 같다면 버전이 적용되지 않은 것이다")
                .isNotEqualTo(firstV1);
        assertThat(secondV1)
                .as("과거 양식으로 재발급하면 당시와 같은 바이트가 나와야 한다")
                .isEqualTo(firstV1);
    }

    @Test
    @DisplayName("버전을 지정하지 않으면 설정된 기본 양식(v2)을 쓴다")
    void usesConfiguredDefaultVersion() {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));

        assertThat(result.document().templateVersion().value())
                .isEqualTo(properties.getDefaultTemplateVersion())
                .isEqualTo("v2");
    }

    @Test
    @DisplayName("로고가 들어간 v2 도 PDF/A-1b 를 통과하고 한 페이지를 유지한다")
    void v2WithSvgLogoStaysCompliant() throws IOException {
        IssueResult result = issueWith(TemplateVersion.of("v2"));

        try (PdfArtifact stored = load(result);
             InputStream in = stored.openStream()) {
            VeraPdf.Report report = VeraPdf.validatePdfA1b(in);
            assertThat(report.compliant()).as(report.describe()).isTrue();
        }
        try (PdfArtifact stored = load(result);
             var document = org.apache.pdfbox.Loader.loadPDF(stored.readAllBytes())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("SVG 로고는 벡터로 들어가고 파일 크기를 거의 늘리지 않는다")
    void svgLogoIsVectorAndCheap() throws IOException {
        long v1Size = issueWith(TemplateVersion.V1).document().fileSize();
        long v2Size = issueWith(TemplateVersion.of("v2")).document().fileSize();

        // 래스터로 들어갔다면 로고 하나에 수십 KB 가 붙는다. 벡터 path 두 개는 1KB 남짓이다.
        assertThat(v2Size - v1Size)
                .as("v1 %dB → v2 %dB".formatted(v1Size, v2Size))
                .isLessThan(8 * 1024);
    }

    private IssueResult issueWith(TemplateVersion version) {
        return issuePolicyUseCase.issue(
                new IssueCommand(TestFixtures.CONTRACT_NO, version, true));
    }

    private PdfArtifact load(IssueResult result) throws IOException {
        return documentStoragePort.load(result.document().storagePath()).orElseThrow();
    }
}
