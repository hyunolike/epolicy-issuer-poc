package com.hyunolike.epolicy.domain.contract;

/** 계약 관계자(계약자/피보험자). 개인정보 원본을 보유하므로 렌더 레이어로 넘어가면 안 된다. */
public record Party(
        String name,
        RegisteredNo registeredNo,
        String phone,
        String address
) {
    public Party {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("이름은 비어 있을 수 없습니다");
        }
    }

    /** 개인정보가 로그로 새지 않도록 record 기본 toString 을 덮는다. */
    @Override
    public String toString() {
        return "Party[name=%s, registeredNo=%s]".formatted(maskedNameHint(), registeredNo);
    }

    private String maskedNameHint() {
        return name.charAt(0) + "*".repeat(Math.max(1, name.length() - 1));
    }
}
