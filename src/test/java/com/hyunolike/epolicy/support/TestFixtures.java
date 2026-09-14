package com.hyunolike.epolicy.support;

import com.hyunolike.epolicy.domain.contract.Contract;
import com.hyunolike.epolicy.domain.contract.ContractNo;
import com.hyunolike.epolicy.domain.contract.Coverage;
import com.hyunolike.epolicy.domain.contract.InsurancePeriod;
import com.hyunolike.epolicy.domain.contract.Money;
import com.hyunolike.epolicy.domain.contract.Party;
import com.hyunolike.epolicy.domain.contract.ProductCode;
import com.hyunolike.epolicy.domain.contract.RegisteredNo;
import com.hyunolike.epolicy.infrastructure.persistence.ContractEntity;
import java.time.LocalDate;
import java.util.List;

/**
 * 테스트 고정 데이터.
 *
 * <p>주민등록번호는 검증번호가 일부러 틀린 값이다({@code SyntheticContractSeeder} 와 같은 원칙).
 * 실 개인정보는 어떤 형태로도 레포에 들어오지 않는다.
 */
public final class TestFixtures {

    public static final String RAW_HOLDER_RRN = "900101-1234568";
    public static final String RAW_INSURED_RRN = "920315-2345679";
    public static final ContractNo CONTRACT_NO = ContractNo.of("KB-2026-0001-0042");

    private TestFixtures() {
    }

    public static Contract contract() {
        return contract(CONTRACT_NO);
    }

    public static Contract contract(ContractNo contractNo) {
        return new Contract(
                contractNo,
                ProductCode.of("PA0101", "무배당 일반상해보험"),
                new Party("홍길동", RegisteredNo.of(RAW_HOLDER_RRN), "010-1234-5678",
                        "서울특별시 강남구 테헤란로 152"),
                new Party("남궁길동", RegisteredNo.of(RAW_INSURED_RRN), "010-8765-4321",
                        "경기도 성남시 분당구 판교역로 235"),
                new InsurancePeriod(LocalDate.of(2026, 3, 1), LocalDate.of(2027, 3, 1)),
                Money.won(124_000),
                LocalDate.of(2026, 2, 28),
                List.of(
                        new Coverage("상해사망", Money.won(100_000_000), "보험기간 중 상해의 직접 결과로 사망한 경우"),
                        new Coverage("상해후유장해", Money.won(50_000_000), "장해지급률에 따라 비례 지급"),
                        new Coverage("입원일당", Money.won(50_000), "1일 이상 입원 시 1일당 지급 (180일 한도)")));
    }

    public static ContractEntity contractEntity() {
        return contractEntity(CONTRACT_NO);
    }

    public static ContractEntity contractEntity(ContractNo contractNo) {
        Contract contract = contract(contractNo);
        return new ContractEntity(
                contract.contractNo().value(),
                contract.productCode().value(),
                contract.productCode().displayName(),
                contract.policyholder().name(),
                contract.policyholder().registeredNo().rawValue(),
                contract.policyholder().phone(),
                contract.policyholder().address(),
                contract.insured().name(),
                contract.insured().registeredNo().rawValue(),
                contract.insured().phone(),
                contract.insured().address(),
                contract.period().start(),
                contract.period().end(),
                contract.premium().amount(),
                contract.issuedOn(),
                contract.coverages().stream()
                        .map(coverage -> new ContractEntity.CoverageRow(
                                coverage.name(), coverage.amount().amount(), coverage.note()))
                        .toList());
    }
}
