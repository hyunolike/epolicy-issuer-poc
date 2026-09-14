package com.hyunolike.epolicy.domain.document;

import java.util.regex.Pattern;

/**
 * 템플릿 버전.
 *
 * <p>재발급은 "지금 양식"이 아니라 "발급 당시 양식"으로 렌더해야 같은 문서가 나온다. 그래서 버전을
 * 발급 이력에 고정하고, 템플릿 파일은 {@code templates/policy/v1/} 처럼 버전 디렉터리를 둔다.
 * 기존 버전 디렉터리는 수정하지 않는다 — 수정하는 순간 과거 발급분의 재현성이 깨진다.
 */
public record TemplateVersion(String value) {

    private static final Pattern FORMAT = Pattern.compile("^v\\d+$");

    public static final TemplateVersion V1 = new TemplateVersion("v1");

    public TemplateVersion {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("템플릿 버전 형식이 아닙니다(v1, v2 ...): " + value);
        }
    }

    public static TemplateVersion of(String value) {
        return new TemplateVersion(value);
    }

    /** Thymeleaf 템플릿 경로. */
    public String templatePath() {
        return "policy/" + value + "/policy";
    }

    @Override
    public String toString() {
        return value;
    }
}
