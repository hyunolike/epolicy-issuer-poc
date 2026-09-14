package com.hyunolike.epolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueCommand;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueResult;
import com.hyunolike.epolicy.application.port.in.VerifyDocumentUseCase;
import com.hyunolike.epolicy.application.port.in.VerifyDocumentUseCase.VerifyResult;
import com.hyunolike.epolicy.application.port.out.SignatureVerification;
import com.hyunolike.epolicy.application.port.out.SignatureVerificationPort;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import com.hyunolike.epolicy.support.IssuanceTestBase;
import com.hyunolike.epolicy.support.TestFixtures;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 변조 탐지 (M4 의 핵심).
 *
 * <p>"서명 검증 통과"는 절반짜리 확인이다. 서명이 붙어 있고 검증기가 OK 를 돌려주는 것만으로는
 * 아무것도 증명되지 않는다 — 바이트를 바꿨을 때 <b>반드시 실패해야</b> 검증이 의미를 가진다.
 * 그래서 이 테스트는 "검증 성공"과 "변조 시 검증 실패"를 한 쌍으로 확인한다.
 */
class DocumentIntegrityTest extends IssuanceTestBase {



    @Autowired
    private IssuePolicyUseCase issuePolicyUseCase;

    @Autowired
    private VerifyDocumentUseCase verifyDocumentUseCase;

    @Autowired
    private SignatureVerificationPort signatureVerificationPort;

    @Autowired
    private com.hyunolike.epolicy.application.port.out.DocumentStoragePort documentStoragePort;

    @BeforeEach
    void seedContract() {
        contractRepository.save(TestFixtures.contractEntity());
    }

    @Test
    @DisplayName("정상 발급 문서는 보관 해시와 서명이 모두 통과한다")
    void verifiesIntactDocument() {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));

        VerifyResult verification = verifyDocumentUseCase.verify(result.document().id());

        assertThat(verification.fileHashMatches()).isTrue();
        assertThat(verification.signatures()).hasSize(1);
        SignatureVerification signature = verification.signatures().get(0);
        assertThat(signature.valid()).as(String.valueOf(signature.problems())).isTrue();
        assertThat(signature.coversWholeDocument()).isTrue();
        assertThat(signature.signerSubject()).contains("TEST");
        assertThat(verification.isTrustworthy()).isTrue();
    }

    @Test
    @DisplayName("본문 바이트 1개를 바꾸면 서명 검증이 실패한다")
    void detectsSingleByteTampering() throws IOException {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));
        byte[] original = read(result.document().storagePath());

        // 본문 한가운데를 1바이트 뒤집는다. 파일 크기도, 구조도 그대로다.
        byte[] tampered = original.clone();
        int offset = tampered.length / 3;
        tampered[offset] = (byte) (tampered[offset] ^ 0x01);

        List<SignatureVerification> verifications =
                signatureVerificationPort.verify(artifactOf(tampered));

        assertThat(verifications).hasSize(1);
        SignatureVerification signature = verifications.get(0);
        assertThat(signature.valid())
                .as("1바이트 변조를 잡지 못하면 전자서명이 아무 의미가 없다")
                .isFalse();
        assertThat(signature.isIntact()).isFalse();
        assertThat(signature.problems()).isNotEmpty();
    }

    @Test
    @DisplayName("파일 끝에 내용을 덧붙이면 서명 범위 밖이라는 것이 드러난다")
    void detectsAppendedContent() throws IOException {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));
        byte[] original = read(result.document().storagePath());

        // 서명 자체는 건드리지 않고 뒤에 덧붙인다. 암호학적 검증만 보면 통과할 수 있는 공격이다.
        byte[] appended = new byte[original.length + 32];
        System.arraycopy(original, 0, appended, 0, original.length);

        List<SignatureVerification> verifications =
                signatureVerificationPort.verify(artifactOf(appended));

        SignatureVerification signature = verifications.get(0);
        assertThat(signature.coversWholeDocument())
                .as("ByteRange 밖에 덧붙은 바이트를 잡아야 한다")
                .isFalse();
        assertThat(signature.isIntact()).isFalse();
        assertThat(signature.problems()).anyMatch(problem -> problem.contains("서명 범위 밖"));
    }

    @Test
    @DisplayName("보관 파일이 바뀌면 fileHash 불일치로 드러난다")
    void detectsStorageTampering() throws IOException {
        IssueResult result = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));
        java.nio.file.Path stored = storedFile(result.document().storagePath());

        byte[] bytes = Files.readAllBytes(stored);
        bytes[bytes.length / 2] = (byte) (bytes[bytes.length / 2] ^ 0x02);
        Files.write(stored, bytes);

        VerifyResult verification = verifyDocumentUseCase.verify(result.document().id());

        assertThat(verification.fileHashMatches()).isFalse();
        assertThat(verification.isTrustworthy()).isFalse();
    }

    private byte[] read(String storagePath) throws IOException {
        Optional<PdfArtifact> artifact = documentStoragePort.load(storagePath);
        assertThat(artifact).isPresent();
        try (PdfArtifact file = artifact.get()) {
            return file.readAllBytes();
        }
    }

    private static PdfArtifact artifactOf(byte[] bytes) {
        return new PdfArtifact() {
            @Override
            public InputStream openStream() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public long size() {
                return bytes.length;
            }

            @Override
            public void close() {
                // 테스트용 메모리 아티팩트
            }
        };
    }
}
