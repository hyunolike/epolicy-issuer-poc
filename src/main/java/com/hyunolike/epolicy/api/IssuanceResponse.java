package com.hyunolike.epolicy.api;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueResult;
import com.hyunolike.epolicy.domain.document.PolicyDocument;

/**
 * 발급 응답.
 *
 * <p>{@code reusedExisting} 을 응답에 노출하는 것이 포인트다. 호출자는 "새 파일이 만들어졌는가"를
 * 알아야 교부 통지를 다시 보낼지 판단할 수 있다. 200 만 돌려주면 재발급 요청이 실제로 무엇을 했는지
 * 알 수 없다.
 */
public record IssuanceResponse(
        String documentId,
        String contractNo,
        int issueSequence,
        String templateVersion,
        String status,
        String contentHash,
        String fileHash,
        long fileSize,
        String storagePath,
        boolean reusedExisting,
        long elapsedMillis
) {
    public static IssuanceResponse from(IssueResult result) {
        PolicyDocument document = result.document();
        return new IssuanceResponse(
                document.id().value(),
                document.contractNo().value(),
                document.issueSequence(),
                document.templateVersion().value(),
                document.status().name(),
                document.contentHash() == null ? null : document.contentHash().hex(),
                document.fileHash() == null ? null : document.fileHash().hex(),
                document.fileSize(),
                document.storagePath(),
                result.reusedExisting(),
                result.elapsed().toMillis());
    }
}
