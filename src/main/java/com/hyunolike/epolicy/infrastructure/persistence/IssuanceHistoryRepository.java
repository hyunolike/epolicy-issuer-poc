package com.hyunolike.epolicy.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssuanceHistoryRepository extends JpaRepository<IssuanceHistoryEntity, Long> {

    List<IssuanceHistoryEntity> findByContractNoOrderByHistoryIdAsc(String contractNo);
}
