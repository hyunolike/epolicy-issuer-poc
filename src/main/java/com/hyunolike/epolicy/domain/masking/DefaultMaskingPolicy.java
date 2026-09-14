package com.hyunolike.epolicy.domain.masking;

import com.hyunolike.epolicy.domain.contract.RegisteredNo;

/**
 * 금융권에서 통용되는 표기 수준의 마스킹 규칙.
 *
 * <ul>
 *   <li>이름: 가운데 글자 전부 — 홍길동 → 홍*동, 남궁길동 → 남**동, 김구 → 김*</li>
 *   <li>주민번호: 생년월일 + 성별자리까지만 — 900101-1******</li>
 *   <li>전화번호: 국번만 — 010-****-5678</li>
 *   <li>주소: 시/군/구 까지만 — 서울특별시 강남구 ***</li>
 * </ul>
 */
public class DefaultMaskingPolicy implements MaskingPolicy {

    private static final String ADDRESS_TAIL = "***";

    @Override
    public MaskedValue maskName(String name) {
        if (name == null || name.isBlank()) {
            return MaskedValue.of("");
        }
        String trimmed = name.trim();
        if (trimmed.length() == 1) {
            return MaskedValue.of(trimmed);
        }
        if (trimmed.length() == 2) {
            return MaskedValue.of(trimmed.charAt(0) + "*");
        }
        String middle = "*".repeat(trimmed.length() - 2);
        return MaskedValue.of(trimmed.charAt(0) + middle + trimmed.charAt(trimmed.length() - 1));
    }

    @Override
    public MaskedValue maskRegisteredNo(RegisteredNo registeredNo) {
        if (registeredNo == null) {
            return MaskedValue.of("");
        }
        // RegisteredNo.toString() 자체가 마스킹 형태지만, 정책은 toString 구현에 기대지 않는다.
        return MaskedValue.of(registeredNo.birthPart() + "-" + registeredNo.genderDigit() + "******");
    }

    @Override
    public MaskedValue maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return MaskedValue.of("");
        }
        String[] parts = phone.trim().split("-");
        if (parts.length != 3) {
            // 형식을 모르면 끝 4자리만 남긴다. 모르는 값은 더 많이 가린다.
            String digits = phone.replaceAll("\\D", "");
            String tail = digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
            return MaskedValue.of("***-****-" + tail);
        }
        return MaskedValue.of(parts[0] + "-****-" + parts[2]);
    }

    @Override
    public MaskedValue maskAddress(String address) {
        if (address == null || address.isBlank()) {
            return MaskedValue.of("");
        }
        String[] tokens = address.trim().split("\\s+");
        if (tokens.length <= 2) {
            return MaskedValue.of(tokens[0] + " " + ADDRESS_TAIL);
        }
        return MaskedValue.of(tokens[0] + " " + tokens[1] + " " + ADDRESS_TAIL);
    }
}
