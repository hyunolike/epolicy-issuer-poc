package com.hyunolike.epolicy.domain.contract;

import java.time.LocalDate;
import java.util.List;

/**
 * 계약. 발급 파이프라인의 입력이다.
 *
 * <p>{@code issuedOn}(청약 체결일)은 단순한 표시값이 아니라 결정성(determinism)의 기준점이다.
 * PDF 의 CreationDate/ModDate 를 벽시계가 아니라 이 날짜에서 끌어오기 때문에, 같은 계약을 6개월 뒤
 * 재발급해도 서명 전 바이트가 동일하게 나온다. 자세한 근거는 docs/DESIGN.md 의 설계 결정 ③ 참고.
 */
public record Contract(
        ContractNo contractNo,
        ProductCode productCode,
        Party policyholder,
        Party insured,
        InsurancePeriod period,
        Money premium,
        LocalDate issuedOn,
        List<Coverage> coverages
) {
    public Contract {
        if (coverages == null || coverages.isEmpty()) {
            throw new IllegalArgumentException("담보가 하나도 없는 계약은 증권을 낼 수 없습니다: " + contractNo);
        }
        if (issuedOn == null) {
            throw new IllegalArgumentException("계약체결일은 비어 있을 수 없습니다: " + contractNo);
        }
        coverages = List.copyOf(coverages);
    }

    /** 담보 가입금액 합계. */
    public Money totalCoverageAmount() {
        return coverages.stream().map(Coverage::amount).reduce(Money.won(0), Money::plus);
    }
}
