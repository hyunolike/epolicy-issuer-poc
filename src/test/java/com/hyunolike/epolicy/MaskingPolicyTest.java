package com.hyunolike.epolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyunolike.epolicy.domain.contract.RegisteredNo;
import com.hyunolike.epolicy.domain.masking.DefaultMaskingPolicy;
import com.hyunolike.epolicy.domain.masking.MaskedValue;
import com.hyunolike.epolicy.domain.masking.MaskingPolicy;
import com.hyunolike.epolicy.domain.masking.PersonalDataLeakException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MaskingPolicyTest {

    private final MaskingPolicy policy = new DefaultMaskingPolicy();

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "홍길동, 홍*동",
            "남궁길동, 남**동",
            "김구, 김*",
            "선우, 선*",
            "황보영식, 황**식"
    })
    @DisplayName("이름은 첫 글자와 끝 글자만 남는다")
    void masksName(String raw, String expected) {
        assertThat(policy.maskName(raw).value()).isEqualTo(expected);
    }

    @Test
    @DisplayName("한 글자 이름은 가릴 곳이 없으므로 그대로 둔다")
    void keepsSingleCharacterName() {
        assertThat(policy.maskName("이").value()).isEqualTo("이");
    }

    @Test
    @DisplayName("주민번호는 생년월일과 성별자리까지만 남는다")
    void masksRegisteredNo() {
        RegisteredNo registeredNo = RegisteredNo.of("900101-1234568");
        assertThat(policy.maskRegisteredNo(registeredNo).value()).isEqualTo("900101-1******");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "010-1234-5678, 010-****-5678",
            "02-123-4567, 02-****-4567"
    })
    @DisplayName("전화번호는 국번만 가린다")
    void masksPhone(String raw, String expected) {
        assertThat(policy.maskPhone(raw).value()).isEqualTo(expected);
    }

    @Test
    @DisplayName("형식을 알 수 없는 전화번호는 끝 4자리만 남긴다")
    void masksUnknownPhoneFormatConservatively() {
        // 모르는 형식일수록 더 많이 가린다. 덜 가리는 쪽으로 실수하면 그게 사고다.
        assertThat(policy.maskPhone("01012345678").value()).isEqualTo("***-****-5678");
    }

    @Test
    @DisplayName("주소는 시/군/구까지만 남는다")
    void masksAddress() {
        assertThat(policy.maskAddress("서울특별시 강남구 테헤란로 152").value())
                .isEqualTo("서울특별시 강남구 ***");
        assertThat(policy.maskAddress("세종특별자치시 한누리대로").value())
                .isEqualTo("세종특별자치시 ***");
    }

    @Nested
    @DisplayName("MaskedValue 의 조기 실패 장치")
    class LeakGuard {

        @Test
        @DisplayName("주민번호 전체 형태는 MaskedValue 로 만들 수 없다")
        void rejectsFullRegisteredNo() {
            assertThatThrownBy(() -> MaskedValue.of("900101-1234568"))
                    .isInstanceOf(PersonalDataLeakException.class);
        }

        @Test
        @DisplayName("7자리 이상 연속 숫자도 거부한다")
        void rejectsLongDigitRun() {
            assertThatThrownBy(() -> MaskedValue.of("계좌 12345678"))
                    .isInstanceOf(PersonalDataLeakException.class);
        }

        @Test
        @DisplayName("마스킹된 값과 쉼표가 들어간 금액은 통과한다")
        void allowsMaskedAndFormattedValues() {
            assertThat(MaskedValue.of("900101-1******").value()).isEqualTo("900101-1******");
            assertThat(MaskedValue.of("150,050,000원").value()).isEqualTo("150,050,000원");
        }
    }

    @Test
    @DisplayName("RegisteredNo 는 toString 으로 원본을 흘리지 않는다")
    void registeredNoNeverPrintsRawValue() {
        RegisteredNo registeredNo = RegisteredNo.of("900101-1234568");

        // 로그 포맷 문자열에 그대로 넣어도 안전해야 한다. 이 한 줄이 실제 유출 경로의 대부분이다.
        assertThat("주민번호=%s".formatted(registeredNo)).isEqualTo("주민번호=900101-1******");
        assertThat(registeredNo.rawValue()).isEqualTo("900101-1234568");
    }
}
