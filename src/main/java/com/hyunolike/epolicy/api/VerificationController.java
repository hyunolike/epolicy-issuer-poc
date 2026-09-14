package com.hyunolike.epolicy.api;

import com.hyunolike.epolicy.application.port.in.VerifyDocumentUseCase;
import com.hyunolike.epolicy.application.port.in.VerifyDocumentUseCase.VerifyResult;
import com.hyunolike.epolicy.application.port.out.IssuanceHistoryPort;
import com.hyunolike.epolicy.application.port.out.SignatureVerification;
import com.hyunolike.epolicy.domain.contract.ContractNo;
import com.hyunolike.epolicy.domain.document.DocumentId;
import com.hyunolike.epolicy.domain.document.PolicyDocument;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 무결성 검증 API. */
@RestController
@RequestMapping("/api/verification")
public class VerificationController {

    private final VerifyDocumentUseCase verifyDocumentUseCase;
    private final IssuanceHistoryPort issuanceHistoryPort;

    public VerificationController(VerifyDocumentUseCase verifyDocumentUseCase,
                                  IssuanceHistoryPort issuanceHistoryPort) {
        this.verifyDocumentUseCase = verifyDocumentUseCase;
        this.issuanceHistoryPort = issuanceHistoryPort;
    }

    @GetMapping("/documents/{documentId}")
    public VerificationResponse verifyDocument(@PathVariable String documentId) {
        return VerificationResponse.from(verifyDocumentUseCase.verify(new DocumentId(documentId)));
    }

    @GetMapping("/policies/{contractNo}")
    public VerificationResponse verifyLatest(@PathVariable String contractNo) {
        PolicyDocument document = issuanceHistoryPort.findLatestStored(ContractNo.of(contractNo))
                .orElseThrow(() -> new NoSuchElementException("발급된 증권이 없습니다: " + contractNo));
        return VerificationResponse.from(verifyDocumentUseCase.verify(document.id()));
    }

    /**
     * @param trustworthy 보관 무결성과 서명 무결성이 모두 통과했을 때만 true.
     *                    둘 중 하나라도 깨지면 문서를 교부 증적으로 쓸 수 없다.
     */
    public record VerificationResponse(
            String documentId,
            boolean trustworthy,
            boolean fileHashMatches,
            List<SignatureView> signatures
    ) {
        static VerificationResponse from(VerifyResult result) {
            return new VerificationResponse(
                    result.documentId().value(),
                    result.isTrustworthy(),
                    result.fileHashMatches(),
                    result.signatures().stream().map(SignatureView::from).toList());
        }
    }

    public record SignatureView(
            boolean valid,
            boolean coversWholeDocument,
            String signer,
            String signedAt,
            boolean timestamped,
            List<String> problems
    ) {
        static SignatureView from(SignatureVerification verification) {
            return new SignatureView(
                    verification.valid(),
                    verification.coversWholeDocument(),
                    verification.signerSubject(),
                    verification.signedAt() == null ? null : verification.signedAt().toString(),
                    verification.timestamped(),
                    verification.problems());
        }
    }
}
