package com.hyunolike.epolicy.domain.contract;

import java.util.regex.Pattern;

/**
 * 증권번호. 발급 파이프라인 전 구간의 자연키이며 멱등성 판단의 축이다.
 *
 * <p>형식은 {@code KB-2026-0001-0042} 처럼 네 자리씩 끊는다. 7자리 이상 연속 숫자를 만들지 않는 것은
 * 의도적인데, {@link com.hyunolike.epolicy.domain.masking.MaskedValue} 가 그 패턴을 "마스킹 안 된 값"
 * 으로 보고 거부하기 때문이다.
 */
public record ContractNo(String value) implements Comparable<ContractNo> {

    private static final Pattern FORMAT = Pattern.compile("^[A-Z]{2}-\\d{4}-\\d{4}-\\d{4}$");

    public ContractNo {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("증권번호 형식이 아닙니다: " + value);
        }
    }

    public static ContractNo of(String value) {
        return new ContractNo(value);
    }

    @Override
    public int compareTo(ContractNo o) {
        return value.compareTo(o.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
