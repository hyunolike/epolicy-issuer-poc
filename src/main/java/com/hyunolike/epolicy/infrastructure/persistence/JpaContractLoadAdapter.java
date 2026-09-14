package com.hyunolike.epolicy.infrastructure.persistence;

import com.hyunolike.epolicy.application.port.out.ContractLoadPort;
import com.hyunolike.epolicy.domain.contract.Contract;
import com.hyunolike.epolicy.domain.contract.ContractNo;
import com.hyunolike.epolicy.domain.contract.Coverage;
import com.hyunolike.epolicy.domain.contract.InsurancePeriod;
import com.hyunolike.epolicy.domain.contract.Money;
import com.hyunolike.epolicy.domain.contract.Party;
import com.hyunolike.epolicy.domain.contract.ProductCode;
import com.hyunolike.epolicy.domain.contract.RegisteredNo;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** 계약 원장 조회 어댑터. 엔티티 → 도메인 변환이 유일한 책임이다. */
public class JpaContractLoadAdapter implements ContractLoadPort {

    private final ContractRepository repository;

    public JpaContractLoadAdapter(ContractRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Contract> findByContractNo(ContractNo contractNo) {
        return repository.findById(contractNo.value()).map(JpaContractLoadAdapter::toDomain);
    }

    static Contract toDomain(ContractEntity entity) {
        return new Contract(
                ContractNo.of(entity.getContractNo()),
                ProductCode.of(entity.getProductCode(), entity.getProductName()),
                new Party(entity.getPolicyholderName(), RegisteredNo.of(entity.getPolicyholderRrn()),
                        entity.getPolicyholderPhone(), entity.getPolicyholderAddress()),
                new Party(entity.getInsuredName(), RegisteredNo.of(entity.getInsuredRrn()),
                        entity.getInsuredPhone(), entity.getInsuredAddress()),
                new InsurancePeriod(entity.getPeriodStart(), entity.getPeriodEnd()),
                new Money(entity.getPremium()),
                entity.getIssuedOn(),
                toCoverages(entity));
    }

    private static List<Coverage> toCoverages(ContractEntity entity) {
        return entity.getCoverages().stream()
                .map(row -> new Coverage(row.getName(), new Money(row.getAmount()), row.getNote()))
                .toList();
    }
}
