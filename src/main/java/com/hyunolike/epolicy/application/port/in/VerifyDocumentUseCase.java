package com.hyunolike.epolicy.application.port.in;

import com.hyunolike.epolicy.application.port.out.SignatureVerification;
import com.hyunolike.epolicy.domain.document.DocumentId;
import java.util.List;

/** 보관된 문서의 무결성 검증. */
public interface VerifyDocumentUseCase {

    VerifyResult verify(DocumentId documentId);

    /**
     * @param fileHashMatches 보관소의 파일 해시가 발급 당시 기록과 같은가 (보관 무결성)
     * @param signatures      서명 검증 결과. 하나라도 깨지면 문서를 신뢰할 수 없다.
     */
    record VerifyResult(
            DocumentId documentId,
            boolean fileHashMatches,
            List<SignatureVerification> signatures
    ) {
        public VerifyResult {
            signatures = signatures == null ? List.of() : List.copyOf(signatures);
        }

        public boolean isTrustworthy() {
            return fileHashMatches
                    && !signatures.isEmpty()
                    && signatures.stream().allMatch(SignatureVerification::isIntact);
        }
    }
}
