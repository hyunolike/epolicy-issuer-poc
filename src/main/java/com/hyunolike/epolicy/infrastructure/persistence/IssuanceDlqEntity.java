package com.hyunolike.epolicy.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 실패 건 적재 테이블.
 *
 * <p>{@code stage} 를 따로 두는 것이 핵심이다. 렌더에서 죽은 건과 TSA 타임아웃으로 죽은 건은 재처리
 * 전략이 다르다 — 앞은 데이터나 템플릿을 고쳐야 하고 뒤는 그냥 다시 돌리면 된다. 사유 문자열만 남기면
 * 재처리할 때마다 사람이 읽어 분류해야 한다.
 */
@Entity
@Table(name = "issuance_dlq", indexes = {
        @Index(name = "ix_issuance_dlq_contract", columnList = "contract_no"),
        @Index(name = "ix_issuance_dlq_retryable", columnList = "retried, stage")
})
public class IssuanceDlqEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dlq_id")
    private Long dlqId;

    @Column(name = "contract_no", length = 32, nullable = false)
    private String contractNo;

    /** 마지막으로 성공한 단계. 실패한 단계는 그 다음이다. */
    @Column(name = "stage", length = 16, nullable = false)
    private String stage;

    @Column(name = "reason", length = 1024, nullable = false)
    private String reason;

    @Column(name = "job_execution_id")
    private Long jobExecutionId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "retried", nullable = false)
    private boolean retried;

    protected IssuanceDlqEntity() {
    }

    public IssuanceDlqEntity(String contractNo, String stage, String reason,
                             Long jobExecutionId, Instant occurredAt) {
        this.contractNo = contractNo;
        this.stage = stage;
        this.reason = reason;
        this.jobExecutionId = jobExecutionId;
        this.occurredAt = occurredAt;
        this.retried = false;
    }

    public Long getDlqId() {
        return dlqId;
    }

    public String getContractNo() {
        return contractNo;
    }

    public String getStage() {
        return stage;
    }

    public String getReason() {
        return reason;
    }

    public Long getJobExecutionId() {
        return jobExecutionId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public boolean isRetried() {
        return retried;
    }

    public void markRetried() {
        this.retried = true;
    }
}
