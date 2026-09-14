package com.hyunolike.epolicy.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssuanceDlqRepository extends JpaRepository<IssuanceDlqEntity, Long> {

    List<IssuanceDlqEntity> findByRetriedFalseOrderByDlqIdAsc();

    long countByRetriedFalse();
}
