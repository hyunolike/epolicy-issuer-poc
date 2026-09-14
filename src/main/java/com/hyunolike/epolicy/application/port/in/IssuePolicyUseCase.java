package com.hyunolike.epolicy.application.port.in;

import com.hyunolike.epolicy.domain.contract.ContractNo;
import com.hyunolike.epolicy.domain.document.PolicyDocument;
import com.hyunolike.epolicy.domain.document.TemplateVersion;
import java.time.Duration;

/** 증권 발급. API 경로와 배치 경로가 같은 유스케이스를 쓴다. */
public interface IssuePolicyUseCase {

    IssueResult issue(IssueCommand command);

    /**
     * @param templateVersion null 이면 설정의 기본 버전을 쓴다. 재발급이라면 최초 발급 당시 버전을
     *                        명시해야 같은 문서가 나온다.
     * @param forceReissue    contentHash 가 같아도 새로 발급한다. 파일 유실 복구용 탈출구다.
     */
    record IssueCommand(ContractNo contractNo, TemplateVersion templateVersion, boolean forceReissue) {

        public IssueCommand {
            if (contractNo == null) {
                throw new IllegalArgumentException("증권번호는 비어 있을 수 없습니다");
            }
        }

        public static IssueCommand of(ContractNo contractNo) {
            return new IssueCommand(contractNo, null, false);
        }
    }

    /**
     * @param reusedExisting contentHash 가 직전 발급분과 같아 기존 문서를 그대로 돌려준 경우 true.
     *                       재발급 요청이 실제로 새 파일을 만들었는지 구분하는 값이다.
     */
    record IssueResult(PolicyDocument document, boolean reusedExisting, Duration elapsed) {
    }
}
