package com.hyunolike.epolicy.domain.contract;

import java.math.BigDecimal;
import java.text.DecimalFormat;

/** 금액. PoC 범위에서 통화는 KRW 고정이다. */
public record Money(BigDecimal amount) implements Comparable<Money> {

    private static final DecimalFormat KRW = new DecimalFormat("#,##0");

    public Money {
        if (amount == null) {
            throw new IllegalArgumentException("금액은 null 일 수 없습니다");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다: " + amount);
        }
    }

    public static Money won(long amount) {
        return new Money(BigDecimal.valueOf(amount));
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    /** 증권 본문 표기. 렌더 시점 로케일에 흔들리지 않도록 도메인에서 포맷을 고정한다. */
    public String formatKrw() {
        return KRW.format(amount) + "원";
    }

    @Override
    public int compareTo(Money o) {
        return amount.compareTo(o.amount);
    }
}
