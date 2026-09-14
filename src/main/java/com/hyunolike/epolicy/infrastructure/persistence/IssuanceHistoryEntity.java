package com.hyunolike.epolicy.infrastructure.persistence;

import com.hyunolike.epolicy.domain.document.IssuanceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 상태 전이 이력. 교부 증적의 실체다.
 *
 * <p>append-only 로만 쓴다. 상태를 갱신하는 대신 전이를 쌓아야 "언제 어떤 단계를 지났는가"가 남는다.
 * 감사 대응에서 필요한 것은 현재 상태가 아니라 경로다.
 */
@Entity
@Table(name = "issuance_history", indexes = {
        @Index(name = "ix_issuance_history_document", columnList = "document_id"),
        @Index(name = "ix_issuance_history_contract", columnList = "contract_no")
})
public class IssuanceHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @Column(name = "document_id", length = 36)
    private String documentId;

    @Column(name = "contract_no", length = 32, nullable = false)
    private String contractNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private IssuanceStatus status;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected IssuanceHistoryEntity() {
    }

    public IssuanceHistoryEntity(String documentId, String contractNo, IssuanceStatus status,
                                 String detail, Instant occurredAt) {
        this.documentId = documentId;
        this.contractNo = contractNo;
        this.status = status;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public Long getHistoryId() {
        return historyId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getContractNo() {
        return contractNo;
    }

    public IssuanceStatus getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
