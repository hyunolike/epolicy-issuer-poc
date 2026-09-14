package com.hyunolike.epolicy.infrastructure.persistence;

import com.hyunolike.epolicy.domain.document.IssuanceStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocumentEntity, String> {

    List<PolicyDocumentEntity> findByContractNoAndStatusOrderByIssueSequenceDesc(
            String contractNo, IssuanceStatus status, Limit limit);

    @Query("select coalesce(max(d.issueSequence), 0) from PolicyDocumentEntity d where d.contractNo = :contractNo")
    int findMaxIssueSequence(String contractNo);

    Optional<PolicyDocumentEntity> findByContractNoAndIssueSequence(String contractNo, int issueSequence);
}
