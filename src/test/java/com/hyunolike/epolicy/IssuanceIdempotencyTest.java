package com.hyunolike.epolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueCommand;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueResult;
import com.hyunolike.epolicy.domain.document.TemplateVersion;
import com.hyunolike.epolicy.support.IssuanceTestBase;
import com.hyunolike.epolicy.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 재발급 멱등성.
 *
 * <p>이 테스트가 걸러 내는 실수는 하나다 — 멱등성을 <b>파일 해시</b>로 판단하는 것. 서명에는 시각이
 * 들어가므로 같은 내용이라도 서명 파일은 매번 다른 바이트가 나온다. fileHash 로 비교하면 영원히
 * "다른 문서"로 판정되어 재발급 요청마다 새 파일이 쌓인다. 그래서 서명 <b>전</b> 바이트인
 * contentHash 를 기준으로 잡았고, 그 기준이 실제로 성립하는지를 여기서 확인한다.
 */
class IssuanceIdempotencyTest extends IssuanceTestBase {



    @Autowired
    private IssuePolicyUseCase issuePolicyUseCase;

    @BeforeEach
    void seedContract() {
        contractRepository.save(TestFixtures.contractEntity());
    }

    @Test
    @DisplayName("같은 계약을 두 번 발급하면 기존 문서를 재사용한다")
    void reusesExistingDocument() {
        IssueResult first = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));
        IssueResult second = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));

        assertThat(first.reusedExisting()).isFalse();
        assertThat(second.reusedExisting()).isTrue();
        assertThat(second.document().id()).isEqualTo(first.document().id());
        assertThat(second.document().issueSequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("강제 재발급해도 서명 전 바이트(contentHash)는 동일하다")
    void producesIdenticalContentOnForcedReissue() {
        IssueResult first = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));
        IssueResult second = issuePolicyUseCase.issue(
                new IssueCommand(TestFixtures.CONTRACT_NO, null, true));

        assertThat(second.reusedExisting()).isFalse();
        assertThat(second.document().issueSequence()).isEqualTo(2);
        assertThat(second.document().contentHash().hex())
                .as("렌더가 결정적이지 않으면 여기서 깨진다 — 생성시각, /ID, 로케일이 흔한 원인이다")
                .isEqualTo(first.document().contentHash().hex());
        assertThat(second.document().fileHash().hex())
                .as("서명 시각이 들어가므로 최종 파일은 달라야 정상이다")
                .isNotEqualTo(first.document().fileHash().hex());
    }

    @Test
    @DisplayName("발급 회차가 늘어도 문서 식별자는 회차별로 결정적이다")
    void documentIdIsDerivedFromContractAndSequence() {
        IssueResult first = issuePolicyUseCase.issue(IssueCommand.of(TestFixtures.CONTRACT_NO));
        IssueResult second = issuePolicyUseCase.issue(
                new IssueCommand(TestFixtures.CONTRACT_NO, TemplateVersion.V1, true));

        assertThat(second.document().id()).isNotEqualTo(first.document().id());
        assertThat(second.document().id())
                .isEqualTo(com.hyunolike.epolicy.domain.document.DocumentId
                        .of(TestFixtures.CONTRACT_NO, 2));
    }
}
