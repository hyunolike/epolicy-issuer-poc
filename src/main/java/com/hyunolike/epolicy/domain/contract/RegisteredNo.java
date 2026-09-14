package com.hyunolike.epolicy.domain.contract;

import java.util.regex.Pattern;

/**
 * 주민등록번호. 원본을 들고 다니는 유일한 타입이다.
 *
 * <p>toString() 이 마스킹된 값을 돌려주는 것은 편의가 아니라 방어다. 로그 한 줄, 예외 메시지 한 줄,
 * 디버거 watch 창 하나로 원본이 흘러나가는 경로를 막는다. 원본이 필요하면 {@link #rawValue()} 를
 * 명시적으로 호출해야 하고, 그 호출부는 grep 으로 전수 감사할 수 있다.
 */
public final class RegisteredNo {

    private static final Pattern FORMAT = Pattern.compile("^\\d{6}-\\d{7}$");

    private final String raw;

    private RegisteredNo(String raw) {
        if (raw == null || !FORMAT.matcher(raw).matches()) {
            throw new IllegalArgumentException("주민등록번호 형식이 아닙니다");
        }
        this.raw = raw;
    }

    public static RegisteredNo of(String raw) {
        return new RegisteredNo(raw);
    }

    /** 원본 반환. 마스킹 정책 외의 호출부가 생기면 그 자체가 리뷰 대상이다. */
    public String rawValue() {
        return raw;
    }

    public String birthPart() {
        return raw.substring(0, 6);
    }

    public char genderDigit() {
        return raw.charAt(7);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RegisteredNo other && raw.equals(other.raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /** 항상 마스킹된 형태만 노출한다. */
    @Override
    public String toString() {
        return birthPart() + "-" + genderDigit() + "******";
    }
}
