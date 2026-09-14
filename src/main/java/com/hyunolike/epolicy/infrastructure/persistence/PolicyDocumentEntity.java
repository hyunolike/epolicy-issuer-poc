package com.hyunolike.epolicy.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** 발급 문서 메타. 파일 자체는 보관소에 있고 여기에는 좌표와 해시만 남는다. */
@Entity
@Table(name = "policy_document", indexes = {
        @Index(name = "ix_policy_document_contract", columnList = "contract_no, issue_sequence"),
        @Index(name = "ix_policy_document_content_hash", columnList = "content_hash")
})
public class PolicyDocumentEntity {

    @Id
    @Column(name = "document_id", length = 36, nullable = false)
    private String documentId;

    @Column(name = "contract_no", length = 32, nullable = false)
    private String contractNo;

    @Column(name = "issue_sequence", nullable = false)
    private int issueSequence;

    @Column(name = "template_version", length = 8, nullable = false)
    private String templateVersion;

    /** 서명 전 해시. 멱등성 판단 기준. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** 서명 후 해시. 보관 무결성 기준. */
    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "storage_path", length = 256)
    private String storagePath;

    @Column(name = "file_size")
    private long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private com.hyunolike.epolicy.domain.document.IssuanceStatus status;

    protected PolicyDocumentEntity() {
    }

    public PolicyDocumentEntity(String documentId, String contractNo, int issueSequence,
                                String templateVersion, String contentHash, String fileHash,
                                Instant signedAt, String storagePath, long fileSize,
                                com.hyunolike.epolicy.domain.document.IssuanceStatus status) {
        this.documentId = documentId;
        this.contractNo = contractNo;
        this.issueSequence = issueSequence;
        this.templateVersion = templateVersion;
        this.contentHash = contentHash;
        this.fileHash = fileHash;
        this.signedAt = signedAt;
        this.storagePath = storagePath;
        this.fileSize = fileSize;
        this.status = status;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getContractNo() {
        return contractNo;
    }

    public int getIssueSequence() {
        return issueSequence;
    }

    public String getTemplateVersion() {
        return templateVersion;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getFileHash() {
        return fileHash;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public long getFileSize() {
        return fileSize;
    }

    public com.hyunolike.epolicy.domain.document.IssuanceStatus getStatus() {
        return status;
    }
}
