package com.hyunolike.epolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueCommand;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueResult;
import com.hyunolike.epolicy.application.port.out.DocumentStoragePort;
import com.hyunolike.epolicy.application.port.out.SignatureVerification;
import com.hyunolike.epolicy.application.port.out.SignatureVerificationPort;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import com.hyunolike.epolicy.support.EmbeddedTsaServer;
import com.hyunolike.epolicy.support.IssuanceTestBase;
import com.hyunolike.epolicy.support.TestFixtures;
import com.hyunolike.epolicy.support.VeraPdf;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * PAdES-B-T (M4 후반).
 *
 * <p>운영 기본값은 TSA 꺼짐이지만, 꺼 두기만 하고 켜 본 적이 없으면 그 코드는 검증되지 않은 것이다.
 * 프로세스 안에 TSA 를 띄워 B-T 경로를 실제로 통과시킨다.
 */
@SpringBootTest(properties = "epolicy.sign.tsa.enabled=true")
class TimestampedSignatureTest extends IssuanceTestBase {

    private static final EmbeddedTsaServer TSA = new EmbeddedTsaServer();

    @DynamicPropertySource
    static void tsaEndpoint(DynamicPropertyRegistry registry) {
        registry.add("epolicy.sign.tsa.url", TSA::url);
    }

    @AfterAll
    static void shutdownTsa() {
        TSA.close();
    }

    @Autowired
    private IssuePolicyUseCase issuePolicyUseCase;

    @Autowired
    private SignatureVerificationPort signatureVerificationPort;

    @Autowired
    private DocumentStoragePort documentStoragePort;

    @BeforeEach
    void seedContract() {
        contractRepository.save(TestFixtures.contractEntity());
    }

    @Test
    @DisplayName("TSA 를 켜면 서명에 타임스탬프 토큰이 붙는다")
    void attachesTimestampToken() throws IOException {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));

        try (PdfArtifact stored = load(result)) {
            List<SignatureVerification> verifications = signatureVerificationPort.verify(stored);

            assertThat(verifications).hasSize(1);
            SignatureVerification signature = verifications.get(0);
            assertThat(signature.valid()).as(String.valueOf(signature.problems())).isTrue();
            assertThat(signature.coversWholeDocument()).isTrue();
            assertThat(signature.timestamped())
                    .as("unsigned attribute 에 signatureTimeStampToken 이 있어야 B-T 다")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("타임스탬프가 붙어도 PDF/A-1b 를 유지한다")
    void staysPdfACompliantWithTimestamp() throws IOException {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));

        try (PdfArtifact stored = load(result);
             InputStream in = stored.openStream()) {
            VeraPdf.Report report = VeraPdf.validatePdfA1b(in);
            assertThat(report.compliant()).as(report.describe()).isTrue();
        }
    }

    @Test
    @DisplayName("타임스탬프가 붙어도 서명 전 해시는 그대로다")
    void timestampDoesNotAffectContentHash() {
        IssueResult first = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));
        IssueResult second = issuePolicyUseCase.issue(
                new IssueCommand(TestFixtures.CONTRACT_NO, null, true));

        // 타임스탬프는 서명 단계에서 붙으므로 contentHash(서명 전)에는 영향이 없어야 한다.
        assertThat(second.document().contentHash().hex())
                .isEqualTo(first.document().contentHash().hex());
    }

    private PdfArtifact load(IssueResult result) throws IOException {
        return documentStoragePort.load(result.document().storagePath()).orElseThrow();
    }
}
