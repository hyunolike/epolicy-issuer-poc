package com.hyunolike.epolicy.domain.document;

import com.hyunolike.epolicy.domain.contract.Contract;
import com.hyunolike.epolicy.domain.contract.Coverage;
import com.hyunolike.epolicy.domain.masking.MaskedValue;
import com.hyunolike.epolicy.domain.masking.MaskingPolicy;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 파이프라인 [1]단계. 계약 → 마스킹된 뷰.
 *
 * <p>이 클래스가 {@link Contract} 와 {@link PolicyView} 사이의 유일한 통로다. 여기를 지나면 원본
 * 개인정보는 더 이상 접근 가능한 곳에 없다.
 *
 * <p>발급회차는 인자로 받지 않는다. 뷰가 회차를 모르면 회차가 바이트에 섞일 수 없고, 그래야
 * contentHash 가 멱등성 기준으로 성립한다.
 *
 * <p>날짜 포맷에 {@link Locale#KOREA} 를 명시한 것은 장식이 아니다. 로케일을 안 주면 JVM 기본
 * 로케일에 따라 같은 계약이 다른 문자열로 렌더되고, 그러면 contentHash 가 환경마다 달라져 멱등성
 * 테스트가 로컬에서만 통과한다.
 */
public class PolicyViewAssembler {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy년 MM월 dd일", Locale.KOREA);

    private final MaskingPolicy maskingPolicy;

    public PolicyViewAssembler(MaskingPolicy maskingPolicy) {
        this.maskingPolicy = maskingPolicy;
    }

    public PolicyView assemble(Contract contract, TemplateVersion templateVersion) {
        return new PolicyView(
                MaskedValue.asIs(contract.contractNo().value()),
                MaskedValue.asIs(contract.productCode().displayName()),
                maskingPolicy.maskName(contract.policyholder().name()),
                maskingPolicy.maskRegisteredNo(contract.policyholder().registeredNo()),
                maskingPolicy.maskPhone(contract.policyholder().phone()),
                maskingPolicy.maskAddress(contract.policyholder().address()),
                maskingPolicy.maskName(contract.insured().name()),
                maskingPolicy.maskRegisteredNo(contract.insured().registeredNo()),
                MaskedValue.asIs(periodText(contract)),
                MaskedValue.asIs(contract.premium().formatKrw()),
                MaskedValue.asIs(contract.totalCoverageAmount().formatKrw()),
                MaskedValue.asIs(format(contract.issuedOn())),
                contract.coverages().stream().map(this::toCoverageView).toList(),
                templateVersion.value());
    }

    private CoverageView toCoverageView(Coverage coverage) {
        return new CoverageView(
                MaskedValue.asIs(coverage.name()),
                MaskedValue.asIs(coverage.amount().formatKrw()),
                MaskedValue.asIs(coverage.note()));
    }

    private String periodText(Contract contract) {
        return format(contract.period().start()) + " ~ " + format(contract.period().end())
                + " (" + contract.period().months() + "개월)";
    }

    private static String format(LocalDate date) {
        return DATE.format(date);
    }

    /** 테스트에서 뷰 전체를 한 줄로 훑을 때 쓴다. */
    public static String flatten(PolicyView view) {
        List<String> parts = List.of(
                view.getContractNo(), view.getProductName(), view.getPolicyholderName(),
                view.getPolicyholderRegisteredNo(), view.getPolicyholderPhone(),
                view.getPolicyholderAddress(), view.getInsuredName(), view.getInsuredRegisteredNo(),
                view.getPeriodText(), view.getPremiumText(), view.getTotalCoverageText());
        return String.join(" ", parts);
    }
}
