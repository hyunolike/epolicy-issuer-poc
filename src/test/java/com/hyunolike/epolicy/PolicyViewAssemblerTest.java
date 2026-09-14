package com.hyunolike.epolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyunolike.epolicy.domain.contract.Contract;
import com.hyunolike.epolicy.domain.document.PolicyView;
import com.hyunolike.epolicy.domain.document.PolicyViewAssembler;
import com.hyunolike.epolicy.domain.document.TemplateVersion;
import com.hyunolike.epolicy.domain.masking.DefaultMaskingPolicy;
import com.hyunolike.epolicy.support.TestFixtures;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 렌더 경계 검사.
 *
 * <p>PDF 를 만들지 않고 뷰 단계에서 확인한다. 여기서 잡히면 렌더·서명·저장을 다 돌리지 않고도
 * 문제를 알 수 있고, 무엇보다 "PDF 텍스트 추출로는 못 잡는" 필드(예: 표시되지 않는 값)까지 본다.
 */
class PolicyViewAssemblerTest {

    private final PolicyViewAssembler assembler = new PolicyViewAssembler(new DefaultMaskingPolicy());

    @Test
    @DisplayName("뷰의 어떤 필드에도 원본 개인정보가 남지 않는다")
    void carriesNoRawPersonalData() {
        Contract contract = TestFixtures.contract();

        PolicyView view = assembler.assemble(contract, TemplateVersion.V1);

        String flattened = PolicyViewAssembler.flatten(view);
        assertThat(flattened)
                .doesNotContain(TestFixtures.RAW_HOLDER_RRN)
                .doesNotContain(TestFixtures.RAW_INSURED_RRN)
                .doesNotContain("홍길동")
                .doesNotContain("남궁길동")
                .doesNotContain("테헤란로 152")
                .doesNotContain("판교역로 235");
        assertThat(flattened).contains("홍*동", "남**동", "900101-1******");
    }

    @Test
    @DisplayName("담보 목록도 순서와 포맷이 유지된다")
    void keepsCoverageOrderAndFormat() {
        PolicyView view = assembler.assemble(TestFixtures.contract(), TemplateVersion.V1);

        assertThat(view.getCoverages()).hasSize(3);
        assertThat(view.getCoverages().get(0).getName()).isEqualTo("상해사망");
        assertThat(view.getCoverages().get(0).getAmount()).isEqualTo("100,000,000원");
        assertThat(view.getTotalCoverageText()).isEqualTo("150,050,000원");
    }

    @Test
    @DisplayName("JVM 기본 로케일이 바뀌어도 같은 문자열이 나온다")
    void isLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            String underUs = PolicyViewAssembler.flatten(
                    assembler.assemble(TestFixtures.contract(), TemplateVersion.V1));

            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            String underArabic = PolicyViewAssembler.flatten(
                    assembler.assemble(TestFixtures.contract(), TemplateVersion.V1));

            // 로케일이 새어 들어오면 날짜나 숫자 표기가 달라지고, 그러면 contentHash 멱등성이
            // 서버 설정에 의존하게 된다. 로컬에서만 통과하는 테스트의 전형적인 원인이다.
            assertThat(underArabic).isEqualTo(underUs);
            assertThat(underUs).contains("2026년 03월 01일").contains("124,000원");
        } finally {
            Locale.setDefault(original);
        }
    }
}
