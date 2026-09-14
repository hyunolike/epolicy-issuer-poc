package com.hyunolike.epolicy.domain.masking;

import java.util.regex.Pattern;

/**
 * 마스킹이 끝난 문자열.
 *
 * <p>렌더 레이어가 받는 유일한 문자열 타입이다. 생성자에서 "원본처럼 생긴 값"을 거부하는 것이 핵심으로,
 * 마스킹 정책에 구멍이 나더라도 템플릿 바인딩 전에 터진다. 방어가 아니라 조기 실패 장치다.
 */
public record MaskedValue(String value) implements CharSequence {

    /** 주민번호 전체 형태, 또는 7자리 이상 연속 숫자. 마스킹 후라면 나올 수 없는 모양이다. */
    private static final Pattern LOOKS_RAW = Pattern.compile("\\d{6}-\\d{7}|\\d{7,}");

    public MaskedValue {
        if (value == null) {
            throw new IllegalArgumentException("마스킹 값은 null 일 수 없습니다");
        }
        if (LOOKS_RAW.matcher(value).find()) {
            throw new PersonalDataLeakException(
                    "마스킹되지 않은 값이 렌더 레이어로 전달되려 했습니다 (패턴 일치)");
        }
    }

    public static MaskedValue of(String value) {
        return new MaskedValue(value);
    }

    /** 마스킹이 필요 없는 비식별 값(증권번호, 상품명 등)을 같은 타입으로 흘려보낼 때 쓴다. */
    public static MaskedValue asIs(String value) {
        return new MaskedValue(value);
    }

    @Override
    public int length() {
        return value.length();
    }

    @Override
    public char charAt(int index) {
        return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return value.subSequence(start, end);
    }

    @Override
    public String toString() {
        return value;
    }
}
