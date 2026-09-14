package com.hyunolike.epolicy.infrastructure.render;

import com.hyunolike.epolicy.application.port.out.TemplateRenderPort;
import com.hyunolike.epolicy.domain.document.PolicyView;
import com.hyunolike.epolicy.domain.document.TemplateVersion;
import java.util.Locale;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * 파이프라인 [2]단계 어댑터.
 *
 * <p>컨텍스트에 들어가는 것은 {@link PolicyView} 뿐이다. {@code Contract} 를 넘기지 않는 것이 핵심으로,
 * 템플릿 작성자가 실수로 {@code ${contract.policyholder.registeredNo}} 를 쓸 수 있는 경로 자체를 없앤다.
 *
 * <p>로케일을 {@link Locale#KOREA} 로 고정한 이유는 결정성이다. 기본 로케일에 맡기면 서버 설정에 따라
 * 같은 계약이 다른 바이트로 렌더되어 contentHash 멱등성이 환경 의존적이 된다.
 */
public class ThymeleafTemplateAdapter implements TemplateRenderPort {

    private final ITemplateEngine templateEngine;
    private final String issuerName;

    public ThymeleafTemplateAdapter(ITemplateEngine templateEngine, String issuerName) {
        this.templateEngine = templateEngine;
        this.issuerName = issuerName;
    }

    @Override
    public String render(PolicyView view, TemplateVersion templateVersion) {
        Context context = new Context(Locale.KOREA);
        context.setVariable("view", view);
        context.setVariable("issuerName", issuerName);
        return templateEngine.process(templateVersion.templatePath(), context);
    }
}
