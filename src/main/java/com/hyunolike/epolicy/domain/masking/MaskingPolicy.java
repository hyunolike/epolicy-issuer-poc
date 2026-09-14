package com.hyunolike.epolicy.domain.masking;

import com.hyunolike.epolicy.domain.contract.RegisteredNo;

/**
 * 마스킹 규칙.
 *
 * <p>파이프라인 [1]단계에 위치한다. 템플릿이나 CSS 가 아니라 여기서 끝나야 PDF 텍스트 레이어에
 * 원본이 남지 않는다. 근거는 docs/DESIGN.md 설계 결정 ①.
 */
public interface MaskingPolicy {

    MaskedValue maskName(String name);

    MaskedValue maskRegisteredNo(RegisteredNo registeredNo);

    MaskedValue maskPhone(String phone);

    MaskedValue maskAddress(String address);
}
