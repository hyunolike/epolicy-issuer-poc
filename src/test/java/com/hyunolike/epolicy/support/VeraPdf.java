package com.hyunolike.epolicy.support;

import java.io.InputStream;
import java.util.List;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.PDFAParser;
import org.verapdf.pdfa.PDFAValidator;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.pdfa.results.ValidationResult;

/**
 * veraPDF 검증 헬퍼.
 *
 * <p>설계 문서는 veraPDF CLI 를 쓰는 그림이었는데, 라이브러리(`org.verapdf:validation-model`)를
 * 테스트 의존성으로 넣으면 같은 검증 엔진을 JUnit 안에서 돌릴 수 있다. CLI 설치가 필요 없어지므로
 * CI 에서 PDF/A 준수가 <b>회귀 테스트</b>가 된다 — 사람이 가끔 돌려 보는 확인이 아니라.
 */
public final class VeraPdf {

    static {
        VeraGreenfieldFoundryProvider.initialise();
    }

    private VeraPdf() {
    }

    public record Report(boolean compliant, List<String> failures) {

        public String describe() {
            return compliant
                    ? "PDF/A-1b 준수"
                    : "PDF/A-1b 위반 %d건:%n%s".formatted(failures.size(), String.join("\n", failures));
        }
    }

    public static Report validate(InputStream pdf, PDFAFlavour flavour) {
        try (PDFAParser parser = Foundries.defaultInstance().createParser(pdf, flavour)) {
            PDFAValidator validator = Foundries.defaultInstance().createValidator(flavour, false);
            ValidationResult result = validator.validate(parser);
            List<String> failures = result.getTestAssertions().stream()
                    .filter(assertion -> assertion.getStatus() == TestAssertion.Status.FAILED)
                    .map(VeraPdf::describe)
                    .distinct()
                    .toList();
            return new Report(result.isCompliant(), failures);
        } catch (Exception e) {
            return new Report(false, List.of("검증 자체가 실패했습니다: " + e));
        }
    }

    public static Report validatePdfA1b(InputStream pdf) {
        return validate(pdf, PDFAFlavour.PDFA_1_B);
    }

    private static String describe(TestAssertion assertion) {
        return "  - [%s] %s (위치: %s)".formatted(
                assertion.getRuleId().getClause(),
                assertion.getMessage(),
                assertion.getLocation() == null ? "?" : assertion.getLocation().getContext());
    }
}
