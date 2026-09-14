package com.hyunolike.epolicy.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepository extends JpaRepository<ContractEntity, String> {
}
