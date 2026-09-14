package com.hyunolike.epolicy.domain.document;

import com.hyunolike.epolicy.domain.contract.ContractNo;
import java.time.Instant;

/** 발급된 증권 문서의 메타데이터. 파일 자체는 보관소에, 이 레코드는 DB 에 남는다. */
public record PolicyDocument(
        DocumentId id,
        ContractNo contractNo,
        int issueSequence,
        TemplateVersion templateVersion,
        ContentHash contentHash,
        ContentHash fileHash,
        Instant signedAt,
        String storagePath,
        long fileSize,
        IssuanceStatus status
) {
    public PolicyDocument {
        if (issueSequence < 1) {
            throw new IllegalArgumentException("발급회차는 1부터 시작합니다: " + issueSequence);
        }
    }

    public static PolicyDocument requested(ContractNo contractNo, int issueSequence, TemplateVersion templateVersion) {
        return new PolicyDocument(
                DocumentId.of(contractNo, issueSequence), contractNo, issueSequence, templateVersion,
                null, null, null, null, 0L, IssuanceStatus.REQUESTED);
    }

    public PolicyDocument archived(ContentHash contentHash) {
        return new PolicyDocument(id, contractNo, issueSequence, templateVersion, contentHash,
                fileHash, signedAt, storagePath, fileSize, IssuanceStatus.ARCHIVED);
    }

    public PolicyDocument signed(Instant signedAt) {
        return new PolicyDocument(id, contractNo, issueSequence, templateVersion, contentHash,
                fileHash, signedAt, storagePath, fileSize, IssuanceStatus.SIGNED);
    }

    public PolicyDocument stored(String storagePath, ContentHash fileHash, long fileSize) {
        return new PolicyDocument(id, contractNo, issueSequence, templateVersion, contentHash,
                fileHash, signedAt, storagePath, fileSize, IssuanceStatus.STORED);
    }

    /** 재발급 판단: 같은 내용이면 이전 발급분을 그대로 쓴다. */
    public boolean hasSameContentAs(ContentHash other) {
        return contentHash != null && other != null && contentHash.hex().equals(other.hex());
    }
}
