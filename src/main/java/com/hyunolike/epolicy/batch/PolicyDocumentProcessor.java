package com.hyunolike.epolicy.batch;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase;
import com.hyunolike.epolicy.domain.contract.ContractNo;
import org.springframework.batch.item.ItemProcessor;

/**
 * 청크 프로세서. 건당 발급 파이프라인 전체를 돈다.
 *
 * <p><b>왜 파이프라인을 여기서 다시 쓰지 않고 유스케이스를 호출하는가</b> — 설계 문서는 Processor 가
 * "마스킹 → 렌더 → PDF/A → 해시 → 서명"까지 하고 Writer 가 "저장 + 이력"을 맡는 그림이었다. 그렇게
 * 나누려면 유스케이스를 둘로 쪼개야 하고, 그 순간 API 경로와 배치 경로가 서로 다른 코드로 갈라진다.
 * 멱등성·마스킹·순서 제약 같은 규제 요건이 두 벌 존재하게 되는 것이라, 한쪽만 고쳐지는 사고가
 * 시간 문제다. 같은 파이프라인을 쓰고 Writer 는 청크 단위 집계를 맡는 쪽으로 바꿨다.
 */
public class PolicyDocumentProcessor implements ItemProcessor<ContractNo, IssuePolicyUseCase.IssueResult> {

    private final IssuePolicyUseCase issuePolicyUseCase;

    public PolicyDocumentProcessor(IssuePolicyUseCase issuePolicyUseCase) {
        this.issuePolicyUseCase = issuePolicyUseCase;
    }

    @Override
    public IssuePolicyUseCase.IssueResult process(ContractNo contractNo) {
        return issuePolicyUseCase.issue(IssuePolicyUseCase.IssueCommand.of(contractNo));
    }
}
