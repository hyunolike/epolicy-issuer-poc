package com.hyunolike.epolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyunolike.epolicy.domain.contract.ContractNo;
import com.hyunolike.epolicy.domain.document.IssuanceStatus;
import com.hyunolike.epolicy.infrastructure.persistence.ContractEntity;
import com.hyunolike.epolicy.infrastructure.persistence.IssuanceDlqEntity;
import com.hyunolike.epolicy.infrastructure.persistence.PolicyDocumentEntity;
import com.hyunolike.epolicy.support.IssuanceTestBase;
import com.hyunolike.epolicy.support.SyntheticContractSeeder;
import com.hyunolike.epolicy.support.TestFixtures;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 배치 발급 (M5).
 *
 * <p>1만 건 대신 300건을 돌린다. 검증하려는 것은 처리량이 아니라 <b>동작</b>이기 때문이다 — 청크
 * 커밋, 멱등 재실행, DLQ 경로. 1만 건 측정은 별도 실행({@code /api/admin/batch/run})으로 하고
 * 결과는 docs/BENCHMARK.md 에 남긴다. 테스트에 수 분짜리 잡을 넣으면 아무도 테스트를 안 돌린다.
 */
@SpringBatchTest
class PolicyIssuanceBatchTest extends IssuanceTestBase {

    private static final int CONTRACT_COUNT = 300;

    /** 시더가 만드는 번호 대역(KB-2026-0001-xxxx)과 겹치지 않게 다른 대역을 쓴다. */
    private static final ContractNo LEAKY_CONTRACT_NO = ContractNo.of("KB-2026-0009-0001");

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private SyntheticContractSeeder seeder;

    @Test
    @DisplayName("전 건이 발급되고 DLQ 가 비어 있다")
    void issuesEveryContract() throws Exception {
        seeder.seed(CONTRACT_COUNT);

        JobExecution execution = jobLauncherTestUtils.launchJob(runParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(policyDocumentRepository.count()).isEqualTo(CONTRACT_COUNT);
        assertThat(issuanceDlqRepository.countByRetriedFalse())
                .as("실패 건이 있으면 DLQ 에 사유가 남는다")
                .isZero();

        List<PolicyDocumentEntity> documents = policyDocumentRepository.findAll();
        assertThat(documents).allMatch(document -> document.getStatus() == IssuanceStatus.STORED);
        assertThat(documents).allMatch(document -> document.getFileSize() > 0);
        assertThat(documents).allMatch(document -> document.getFileHash() != null);
    }

    @Test
    @DisplayName("같은 배치를 두 번 돌려도 발급 건수가 늘지 않는다")
    void isIdempotentAcrossRuns() throws Exception {
        seeder.seed(CONTRACT_COUNT);

        jobLauncherTestUtils.launchJob(runParameters());
        Map<String, String> firstRunHashes = hashesByContract();

        JobExecution second = jobLauncherTestUtils.launchJob(runParameters());

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(policyDocumentRepository.count())
                .as("멱등성이 깨지면 두 번째 실행이 회차 2를 통째로 찍어 낸다")
                .isEqualTo(CONTRACT_COUNT);
        assertThat(hashesByContract()).isEqualTo(firstRunHashes);
    }

    @Test
    @DisplayName("마스킹 정책을 위반하는 계약은 DLQ 로 가고 나머지는 계속 발급된다")
    void routesFailuresToDeadLetterQueueWithoutStoppingTheJob() throws Exception {
        seeder.seed(20);
        // 담보 비고란에 8자리 연속 숫자가 들어온 계약. MaskedValue 가 "마스킹 안 된 값"으로 보고
        // 렌더 전에 거부한다. 현실에서 원문 데이터에 계좌번호나 증서번호가 섞여 들어오는 경우다.
        contractRepository.save(contractWithLeakyCoverageNote());

        JobExecution execution = jobLauncherTestUtils.launchJob(runParameters());

        assertThat(execution.getStatus())
                .as("한 건 실패로 Job 전체를 세우면 나머지 20건이 발급되지 않는다")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(policyDocumentRepository.count()).isEqualTo(20);
        assertThat(issuanceDlqRepository.countByRetriedFalse()).isEqualTo(1);

        IssuanceDlqEntity deadLetter = issuanceDlqRepository.findByRetriedFalseOrderByDlqIdAsc().get(0);
        assertThat(deadLetter.getContractNo()).isEqualTo(LEAKY_CONTRACT_NO.value());
        assertThat(deadLetter.getStage())
                .as("어느 단계에서 죽었는지가 남아야 재처리 전략을 고를 수 있다")
                .isEqualTo(IssuanceStatus.REQUESTED.name());
        assertThat(deadLetter.getReason()).contains("마스킹");
        assertThat(deadLetter.getJobExecutionId())
                .as("어느 실행에서 나온 실패인지 없으면 재처리 대상을 고를 수 없다")
                .isEqualTo(execution.getId());
    }

    private ContractEntity contractWithLeakyCoverageNote() {
        ContractEntity clean = TestFixtures.contractEntity(LEAKY_CONTRACT_NO);
        List<ContractEntity.CoverageRow> leaky = List.of(new ContractEntity.CoverageRow(
                "상해사망", clean.getCoverages().get(0).getAmount(), "지급계좌 12345678 로 이체"));
        return new ContractEntity(
                clean.getContractNo(), clean.getProductCode(), clean.getProductName(),
                clean.getPolicyholderName(), clean.getPolicyholderRrn(), clean.getPolicyholderPhone(),
                clean.getPolicyholderAddress(), clean.getInsuredName(), clean.getInsuredRrn(),
                clean.getInsuredPhone(), clean.getInsuredAddress(), clean.getPeriodStart(),
                clean.getPeriodEnd(), clean.getPremium(), clean.getIssuedOn(), leaky);
    }

    @Test
    @DisplayName("계약이 하나도 없어도 잡은 정상 종료한다")
    void completesOnEmptyInput() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(runParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(policyDocumentRepository.count()).isZero();
    }

    private Map<String, String> hashesByContract() {
        return policyDocumentRepository.findAll().stream()
                .collect(Collectors.toMap(
                        PolicyDocumentEntity::getContractNo, PolicyDocumentEntity::getContentHash));
    }

    /** Spring Batch 는 같은 파라미터의 Job 을 두 번 실행하지 않는다. 실행마다 다른 값을 준다. */
    private JobParameters runParameters() {
        return new JobParametersBuilder()
                .addLong("runId", System.nanoTime())
                .toJobParameters();
    }
}
