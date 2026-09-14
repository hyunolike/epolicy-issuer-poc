package com.hyunolike.epolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueCommand;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueResult;
import com.hyunolike.epolicy.application.port.out.DocumentStoragePort;
import com.hyunolike.epolicy.domain.document.IssuanceStatus;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import com.hyunolike.epolicy.support.IssuanceTestBase;
import com.hyunolike.epolicy.support.TestFixtures;
import com.hyunolike.epolicy.support.VeraPdf;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 파이프라인 전체를 한 번 돌리고 결과 PDF 를 검증한다.
 *
 * <p>이 테스트가 PoC 의 성공 기준 중 두 개를 직접 확인한다 — veraPDF PDF/A-1b 통과, 그리고 PDF
 * 텍스트 레이어에 원본 개인정보가 없을 것. 뒤쪽이 특히 중요하다. 템플릿에서 CSS 로 가리는 방식은
 * 화면상으로는 마스킹처럼 보이지만 텍스트를 추출하면 원본이 그대로 나온다. 눈으로 보는 확인으로는
 * 절대 잡히지 않고, 이렇게 추출해서 문자열을 찾아야 잡힌다.
 */
class PolicyIssuancePipelineTest extends IssuanceTestBase {



    @Autowired
    private IssuePolicyUseCase issuePolicyUseCase;

    @Autowired
    private DocumentStoragePort documentStoragePort;

    @BeforeEach
    void seedContract() {
        contractRepository.save(TestFixtures.contractEntity());
    }

    @Test
    @DisplayName("발급 파이프라인이 끝까지 돌고 STORED 상태로 끝난다")
    void issuesEndToEnd() {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));

        assertThat(result.document().status()).isEqualTo(IssuanceStatus.STORED);
        assertThat(result.document().issueSequence()).isEqualTo(1);
        assertThat(result.document().contentHash()).isNotNull();
        assertThat(result.document().fileHash()).isNotNull();
        assertThat(result.document().fileSize()).isPositive();
        // 서명 전/후 해시는 서로 달라야 한다. 같다면 서명이 붙지 않은 것이다.
        assertThat(result.document().contentHash().hex())
                .isNotEqualTo(result.document().fileHash().hex());
    }

    @Test
    @DisplayName("발급된 PDF 의 텍스트 레이어에 원본 주민번호가 남지 않는다")
    void doesNotLeakPersonalDataIntoTextLayer() throws IOException {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));

        String text = extractText(result);

        assertThat(text)
                .as("원본 주민번호가 PDF 텍스트로 추출되면 안 된다")
                .doesNotContain(TestFixtures.RAW_HOLDER_RRN)
                .doesNotContain(TestFixtures.RAW_INSURED_RRN)
                .doesNotContain("1234568")
                .doesNotContain("2345679");
        assertThat(text)
                .as("마스킹된 형태는 그대로 보여야 한다")
                .contains("900101-1******")
                .contains("920315-2******");
        assertThat(text)
                .as("이름도 마스킹된다 (홍길동 → 홍*동, 남궁길동 → 남**동)")
                .contains("홍*동")
                .contains("남**동")
                .doesNotContain("홍길동")
                .doesNotContain("남궁길동");
    }

    @Test
    @DisplayName("담보가 가장 많은 계약도 한 페이지에 담긴다")
    void fitsOnASinglePage() throws IOException {
        // 담보 6건 + 긴 비고. 표준 계약(3건)으로 확인하면 실제 발급분의 절반 이상이 두 장으로
        // 넘어가는 것을 놓친다 — 넘어간 둘째 장에는 서명 블록만 남고, 1만 건이면 그 페이지 수가
        // 그대로 렌더 시간과 파일 크기에 곱해진다.
        contractRepository.save(TestFixtures.maxCoverageContractEntity());

        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));

        try (PdfArtifact stored = load(result);
             PDDocument document = Loader.loadPDF(stored.readAllBytes())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("한글이 깨지지 않고 서브셋 폰트로 임베딩된다")
    void embedsKoreanFontAsSubset() throws IOException {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));

        // 제목은 letter-spacing 이 걸려 있어 추출하면 "전 자 보 험 증 권" 으로 나온다. 글리프가
        // 제대로 임베딩됐는지만 보면 되므로 공백을 지우고 비교한다.
        String text = extractText(result);
        String squashed = text.replaceAll("\\s+", "");
        assertThat(squashed).contains("전자보험증권").contains("무배당일반상해보험").contains("상해사망");
        // 폰트를 통째로 넣으면 건당 4MB 를 넘는다. 서브셋이 실제로 동작했는지 크기로 확인한다.
        assertThat(result.document().fileSize())
                .as("서브셋 임베딩 시 증권 1장은 300KB 미만이어야 한다")
                .isLessThan(300 * 1024);
    }

    @Test
    @DisplayName("임베딩된 폰트가 나눔고딕 서브셋뿐이다")
    void embedsOnlyBundledFonts() throws IOException {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));

        List<String> baseFonts;
        try (PdfArtifact stored = load(result)) {
            baseFonts = baseFontNames(stored.readAllBytes());
        }

        // 레포가 들고 있지 않은 폰트가 파일에 들어갔다면 PDFBox 가 시스템 폰트로 대체했다는 뜻이고,
        // 그 순간 산출물이 실행 머신의 설치 폰트에 의존하게 된다. 폰트 없는 최소 컨테이너에서는
        // 아예 실패한다. 서명이 AcroForm 기본 리소스에 Helv/ZaDb 를 끌어들이는 경로가 대표적이다.
        assertThat(baseFonts).isNotEmpty();
        assertThat(baseFonts).allSatisfy(font ->
                assertThat(font).as("예상 밖 폰트: " + font).contains("NanumGothic"));
    }

    /** 파일 전체에서 /BaseFont 항목을 긁는다. 페이지 리소스 밖(AcroForm /DR 등)까지 보기 위해서다. */
    private static List<String> baseFontNames(byte[] pdf) {
        Matcher matcher = Pattern.compile("/BaseFont\\s*/([A-Za-z0-9+\\-,._#]+)")
                .matcher(new String(pdf, StandardCharsets.ISO_8859_1));
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    @Test
    @DisplayName("발급 문서가 veraPDF PDF/A-1b 검증을 통과한다")
    void isPdfA1bCompliant() throws IOException {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));

        try (PdfArtifact stored = load(result);
             InputStream in = stored.openStream()) {
            VeraPdf.Report report = VeraPdf.validatePdfA1b(in);
            assertThat(report.compliant()).as(report.describe()).isTrue();
        }
    }

    private String extractText(IssueResult result) throws IOException {
        try (PdfArtifact stored = load(result);
             PDDocument document = Loader.loadPDF(stored.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private PdfArtifact load(IssueResult result) throws IOException {
        return documentStoragePort.load(result.document().storagePath())
                .orElseThrow(() -> new AssertionError("보관 파일을 찾을 수 없습니다"));
    }
}
