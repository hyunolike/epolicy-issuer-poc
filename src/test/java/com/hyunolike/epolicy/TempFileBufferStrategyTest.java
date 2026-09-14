package com.hyunolike.epolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueCommand;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueResult;
import com.hyunolike.epolicy.application.port.out.DocumentStoragePort;
import com.hyunolike.epolicy.application.port.out.PdfArtifactFactory;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import com.hyunolike.epolicy.support.IssuanceTestBase;
import com.hyunolike.epolicy.support.TestFixtures;
import com.hyunolike.epolicy.support.VeraPdf;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 임시파일 버퍼 전략.
 *
 * <p>메모리 전략과 같은 결과가 나와야 전략 교체가 성립한다. 더 중요한 것은 <b>임시파일이 남지 않는
 * 것</b>이다. 힙 누수를 디스크 누수로 바꿔 놓고 측정만 좋아 보이면 1만 건 배치에서 디스크가 찬다.
 * close() 를 빠뜨린 경로가 하나라도 있으면 이 테스트가 잡는다.
 */
@SpringBootTest(properties = "epolicy.pdf.buffer-strategy=temp-file")
class TempFileBufferStrategyTest extends IssuanceTestBase {



    @Autowired
    private IssuePolicyUseCase issuePolicyUseCase;

    @Autowired
    private DocumentStoragePort documentStoragePort;

    @Autowired
    private PdfArtifactFactory pdfArtifactFactory;

    @BeforeEach
    void seedContract() {
        contractRepository.save(TestFixtures.contractEntity());
    }

    @Test
    @DisplayName("임시파일 전략이 선택된다")
    void usesTempFileStrategy() {
        assertThat(pdfArtifactFactory.strategyName()).isEqualTo("temp-file");
    }

    @Test
    @DisplayName("임시파일 전략으로 발급해도 PDF/A-1b 를 통과한다")
    void producesCompliantDocument() throws IOException {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));

        try (PdfArtifact stored = documentStoragePort.load(result.document().storagePath()).orElseThrow();
             InputStream in = stored.openStream()) {
            VeraPdf.Report report = VeraPdf.validatePdfA1b(in);
            assertThat(report.compliant()).as(report.describe()).isTrue();
        }
    }

    @Test
    @DisplayName("발급이 끝나면 스풀 디렉터리에 임시파일이 남지 않는다")
    void doesNotLeakTempFiles() throws IOException {
        for (int i = 0; i < 3; i++) {
            issuePolicyUseCase.issue(new IssueCommand(TestFixtures.CONTRACT_NO, null, true));
        }

        java.nio.file.Path spool = java.nio.file.Path.of(properties.getPdf().getSpoolDir());
        try (Stream<java.nio.file.Path> files = Files.list(spool)) {
            assertThat(files.toList())
                    .as("PdfArtifact.close() 를 빠뜨린 경로가 있으면 여기 파일이 쌓인다")
                    .isEmpty();
        }
    }
}
