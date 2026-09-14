package com.hyunolike.epolicy.batch;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueResult;
import com.hyunolike.epolicy.application.port.out.IssuanceHistoryPort;
import com.hyunolike.epolicy.application.service.IssuanceFailedException;
import com.hyunolike.epolicy.domain.contract.ContractNo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

/**
 * 건당 실패를 DLQ 로 보낸다.
 *
 * <p>1만 건 중 한 건이 깨졌다고 Job 을 세우면 나머지 9,999건이 발급되지 않는다. 반대로 조용히 넘기면
 * 무엇이 빠졌는지 아무도 모른다. 실패 건을 사유·단계와 함께 적재하고 Job 은 계속 가는 것이 답이다.
 *
 * <p>단계 정보를 {@link IssuanceFailedException} 에서 꺼내 기록한다. "렌더에서 죽었다"와 "TSA
 * 타임아웃으로 죽었다"는 재처리 전략이 다르기 때문이다.
 */
public class IssuanceSkipListener implements SkipListener<ContractNo, IssueResult>, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(IssuanceSkipListener.class);

    private final IssuanceHistoryPort issuanceHistoryPort;

    /** 파티션 워커마다 스텝이 따로 돌므로 스레드 간 가시성이 필요하다. */
    private volatile Long jobExecutionId;

    public IssuanceSkipListener(IssuanceHistoryPort issuanceHistoryPort) {
        this.issuanceHistoryPort = issuanceHistoryPort;
    }

    /**
     * Job 실행 id 를 붙잡아 둔다.
     *
     * <p>{@code @BeforeStep} 애노테이션으로 하지 않는 이유가 있다. StepBuilder 의
     * {@code listener(SkipListener)} 오버로드는 애노테이션을 훑지 않아서, 애노테이션만 달아 두면
     * 조용히 호출되지 않고 jobExecutionId 가 계속 null 로 남는다. 컬럼이 nullable 이라 실패도 아니고
     * DLQ 행이 그냥 "어느 실행에서 나온 건지 모르는" 상태가 된다. 인터페이스로 구현하고
     * StepExecutionListener 로도 등록한다.
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        this.jobExecutionId = stepExecution.getJobExecutionId();
    }

    @Override
    public void onSkipInProcess(ContractNo item, Throwable t) {
        String stage = t instanceof IssuanceFailedException failure ? failure.stage() : "UNKNOWN";
        String reason = t.getMessage() == null ? t.getClass().getName() : t.getMessage();
        log.warn("[{}] 발급 실패 — DLQ 적재 (stage={})", item, stage);
        issuanceHistoryPort.enqueueDeadLetter(item, stage, reason, jobExecutionId);
    }

    @Override
    public void onSkipInRead(Throwable t) {
        log.error("계약 조회 중 오류로 건너뜁니다", t);
    }

    @Override
    public void onSkipInWrite(IssueResult item, Throwable t) {
        ContractNo contractNo = item.document().contractNo();
        log.warn("[{}] 청크 기록 실패 — DLQ 적재", contractNo);
        issuanceHistoryPort.enqueueDeadLetter(contractNo, "STORED",
                t.getMessage() == null ? t.getClass().getName() : t.getMessage(), jobExecutionId);
    }
}
