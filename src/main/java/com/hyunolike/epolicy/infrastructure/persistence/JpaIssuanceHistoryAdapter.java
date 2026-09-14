package com.hyunolike.epolicy.infrastructure.persistence;

import com.hyunolike.epolicy.application.port.out.IssuanceHistoryPort;
import com.hyunolike.epolicy.domain.contract.ContractNo;
import com.hyunolike.epolicy.domain.document.ContentHash;
import com.hyunolike.epolicy.domain.document.DocumentId;
import com.hyunolike.epolicy.domain.document.IssuanceStatus;
import com.hyunolike.epolicy.domain.document.PolicyDocument;
import com.hyunolike.epolicy.domain.document.TemplateVersion;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발급 이력 어댑터.
 *
 * <p>{@code recordFailure} 가 {@link Propagation#REQUIRES_NEW} 인 것이 핵심이다. 발급 실패로 바깥
 * 트랜잭션이 롤백되면 실패 기록까지 같이 사라진다. 그러면 DLQ 가 비어 있는 채로 건수만 줄어들어,
 * 무엇이 왜 실패했는지 아무도 모른다. 증적은 실패해도 남아야 증적이다.
 */
public class JpaIssuanceHistoryAdapter implements IssuanceHistoryPort {

    private final PolicyDocumentRepository documentRepository;
    private final IssuanceHistoryRepository historyRepository;
    private final IssuanceDlqRepository dlqRepository;
    private final Clock clock;

    public JpaIssuanceHistoryAdapter(PolicyDocumentRepository documentRepository,
                                     IssuanceHistoryRepository historyRepository,
                                     IssuanceDlqRepository dlqRepository,
                                     Clock clock) {
        this.documentRepository = documentRepository;
        this.historyRepository = historyRepository;
        this.dlqRepository = dlqRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PolicyDocument> findLatestStored(ContractNo contractNo) {
        List<PolicyDocumentEntity> found = documentRepository
                .findByContractNoAndStatusOrderByIssueSequenceDesc(
                        contractNo.value(), IssuanceStatus.STORED, Limit.of(1));
        return found.stream().findFirst().map(JpaIssuanceHistoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PolicyDocument> findById(DocumentId documentId) {
        return documentRepository.findById(documentId.value()).map(JpaIssuanceHistoryAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public int nextIssueSequence(ContractNo contractNo) {
        return documentRepository.findMaxIssueSequence(contractNo.value()) + 1;
    }

    @Override
    @Transactional
    public PolicyDocument save(PolicyDocument document) {
        documentRepository.save(toEntity(document));
        return document;
    }

    @Override
    @Transactional
    public void recordTransition(PolicyDocument document, IssuanceStatus status, String detail) {
        historyRepository.save(new IssuanceHistoryEntity(
                document.id().value(), document.contractNo().value(), status,
                truncate(detail, 512), clock.instant()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(ContractNo contractNo, String stage, String reason) {
        historyRepository.save(new IssuanceHistoryEntity(
                null, contractNo.value(), IssuanceStatus.FAILED, truncate(reason, 512), clock.instant()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueDeadLetter(ContractNo contractNo, String stage, String reason, Long jobExecutionId) {
        dlqRepository.save(new IssuanceDlqEntity(
                contractNo.value(), stage, truncate(reason, 1024), jobExecutionId, clock.instant()));
    }

    private static PolicyDocumentEntity toEntity(PolicyDocument document) {
        return new PolicyDocumentEntity(
                document.id().value(),
                document.contractNo().value(),
                document.issueSequence(),
                document.templateVersion().value(),
                document.contentHash() == null ? null : document.contentHash().hex(),
                document.fileHash() == null ? null : document.fileHash().hex(),
                document.signedAt(),
                document.storagePath(),
                document.fileSize(),
                document.status());
    }

    static PolicyDocument toDomain(PolicyDocumentEntity entity) {
        return new PolicyDocument(
                new DocumentId(entity.getDocumentId()),
                ContractNo.of(entity.getContractNo()),
                entity.getIssueSequence(),
                TemplateVersion.of(entity.getTemplateVersion()),
                entity.getContentHash() == null ? null
                        : new ContentHash(ContentHash.ALGORITHM, entity.getContentHash()),
                entity.getFileHash() == null ? null
                        : new ContentHash(ContentHash.ALGORITHM, entity.getFileHash()),
                entity.getSignedAt(),
                entity.getStoragePath(),
                entity.getFileSize(),
                entity.getStatus());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
