package com.hyunolike.epolicy.application.service;

import com.hyunolike.epolicy.application.port.in.VerifyDocumentUseCase;
import com.hyunolike.epolicy.application.port.out.DocumentStoragePort;
import com.hyunolike.epolicy.application.port.out.IssuanceHistoryPort;
import com.hyunolike.epolicy.application.port.out.SignatureVerification;
import com.hyunolike.epolicy.application.port.out.SignatureVerificationPort;
import com.hyunolike.epolicy.domain.document.ContentHash;
import com.hyunolike.epolicy.domain.document.DocumentId;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import com.hyunolike.epolicy.domain.document.PolicyDocument;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 보관 문서 검증.
 *
 * <p>두 가지를 따로 본다. fileHash 비교는 "보관소의 파일이 발급 당시 그 파일인가"를, 서명 검증은
 * "그 파일이 서명 이후 손대어졌는가"를 본다. 앞은 보관 운영의 문제고 뒤는 문서 자체의 문제라 원인이
 * 다르다. 둘을 한 플래그로 합치면 장애 시 어느 쪽인지 못 가른다.
 */
public class DocumentVerificationService implements VerifyDocumentUseCase {

    private final IssuanceHistoryPort issuanceHistoryPort;
    private final DocumentStoragePort documentStoragePort;
    private final SignatureVerificationPort signatureVerificationPort;

    public DocumentVerificationService(
            IssuanceHistoryPort issuanceHistoryPort,
            DocumentStoragePort documentStoragePort,
            SignatureVerificationPort signatureVerificationPort) {
        this.issuanceHistoryPort = issuanceHistoryPort;
        this.documentStoragePort = documentStoragePort;
        this.signatureVerificationPort = signatureVerificationPort;
    }

    @Override
    public VerifyResult verify(DocumentId documentId) {
        PolicyDocument document = issuanceHistoryPort.findById(documentId)
                .orElseThrow(() -> new NoSuchElementException("발급 이력이 없습니다: " + documentId));
        if (document.storagePath() == null) {
            return new VerifyResult(documentId, false,
                    List.of(SignatureVerification.failed("보관 경로가 없는 문서입니다(발급 미완료)")));
        }
        try {
            PdfArtifact artifact = documentStoragePort.load(document.storagePath())
                    .orElse(null);
            if (artifact == null) {
                return new VerifyResult(documentId, false,
                        List.of(SignatureVerification.failed("보관소에서 파일을 찾을 수 없습니다")));
            }
            try (PdfArtifact file = artifact) {
                ContentHash actual;
                try (InputStream in = file.openStream()) {
                    actual = ContentHash.of(in);
                }
                boolean fileHashMatches = document.fileHash() != null
                        && document.fileHash().hex().equals(actual.hex());
                List<SignatureVerification> signatures = signatureVerificationPort.verify(file);
                return new VerifyResult(documentId, fileHashMatches, signatures);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("문서 검증 중 I/O 실패: " + documentId, e);
        }
    }
}
