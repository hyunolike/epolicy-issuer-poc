package com.hyunolike.epolicy.domain.document;

import com.hyunolike.epolicy.domain.contract.ContractNo;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 문서 식별자.
 *
 * <p>랜덤 UUID 가 아니라 (증권번호, 발급회차)에서 결정적으로 파생한다. 발급을 두 번 돌려도 같은 회차면
 * 같은 id 가 나와야 재처리/DLQ 재시도가 중복 행을 만들지 않는다.
 */
public record DocumentId(String value) {

    public DocumentId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("문서 식별자는 비어 있을 수 없습니다");
        }
    }

    public static DocumentId of(ContractNo contractNo, int issueSequence) {
        String seed = contractNo.value() + "#" + issueSequence;
        return new DocumentId(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
