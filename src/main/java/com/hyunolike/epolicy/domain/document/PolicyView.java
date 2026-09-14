package com.hyunolike.epolicy.domain.document;

import com.hyunolike.epolicy.domain.masking.MaskedValue;
import java.util.List;

/**
 * 템플릿이 받는 유일한 타입.
 *
 * <p>모든 문자열 필드가 {@link MaskedValue} 다. 이 타입은 생성자에서 "마스킹 안 된 것처럼 생긴 값"을
 * 거부하므로, 렌더 레이어는 구조적으로 원본에 접근할 수 없다. 템플릿에 원본을 넘기고 CSS 로 가리는
 * 방식이 왜 안 되는지는 docs/DESIGN.md 설계 결정 ① 참고 — PDF 텍스트 레이어에 원본이 그대로 남는다.
 *
 * <p>발급회차(재발급 2회차 …)는 일부러 담지 않는다. 본문에 회차를 찍으면 같은 계약이라도 회차마다
 * 바이트가 달라져 contentHash 로 멱등성을 볼 수 없게 된다. 회차는 문서 내용이 아니라 교부 이력의
 * 속성으로 본다. docs/DESIGN.md 설계 결정 ③ 참고.
 *
 * <p>record 접근자와 별도로 JavaBean getter 를 둔 것은 Thymeleaf/SpEL 프로퍼티 접근 때문이다.
 * 표현식에서 {@code ${view.policyholderName}} 이 바로 읽히도록 하고, 동시에 값은 String 으로
 * 풀어 템플릿이 MaskedValue.toString() 에 의존하지 않게 한다.
 */
public record PolicyView(
        MaskedValue contractNo,
        MaskedValue productName,
        MaskedValue policyholderName,
        MaskedValue policyholderRegisteredNo,
        MaskedValue policyholderPhone,
        MaskedValue policyholderAddress,
        MaskedValue insuredName,
        MaskedValue insuredRegisteredNo,
        MaskedValue periodText,
        MaskedValue premiumText,
        MaskedValue totalCoverageText,
        MaskedValue issuedOnText,
        List<CoverageView> coverages,
        String templateVersion
) {
    public PolicyView {
        coverages = List.copyOf(coverages);
    }

    public String getContractNo() {
        return contractNo.value();
    }

    public String getProductName() {
        return productName.value();
    }

    public String getPolicyholderName() {
        return policyholderName.value();
    }

    public String getPolicyholderRegisteredNo() {
        return policyholderRegisteredNo.value();
    }

    public String getPolicyholderPhone() {
        return policyholderPhone.value();
    }

    public String getPolicyholderAddress() {
        return policyholderAddress.value();
    }

    public String getInsuredName() {
        return insuredName.value();
    }

    public String getInsuredRegisteredNo() {
        return insuredRegisteredNo.value();
    }

    public String getPeriodText() {
        return periodText.value();
    }

    public String getPremiumText() {
        return premiumText.value();
    }

    public String getTotalCoverageText() {
        return totalCoverageText.value();
    }

    public String getIssuedOnText() {
        return issuedOnText.value();
    }

    public List<CoverageView> getCoverages() {
        return coverages;
    }

    public String getTemplateVersion() {
        return templateVersion;
    }
}
