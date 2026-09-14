package com.hyunolike.epolicy.domain.contract;

/** 상품코드. 어떤 템플릿을 쓸지 고르는 입력이 된다. */
public record ProductCode(String value, String displayName) {

    public ProductCode {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("상품코드는 비어 있을 수 없습니다");
        }
    }

    public static ProductCode of(String value, String displayName) {
        return new ProductCode(value, displayName);
    }
}
