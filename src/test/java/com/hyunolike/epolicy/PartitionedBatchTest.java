package com.hyunolike.epolicy;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyunolike.epolicy.infrastructure.persistence.PolicyDocumentEntity;
import com.hyunolike.epolicy.support.IssuanceTestBase;
import com.hyunolike.epolicy.support.SyntheticContractSeeder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 파티셔닝 (M6).
 *
 * <p>파티셔닝에서 제일 먼저 깨지는 것은 속도가 아니라 <b>배타성</b>이다. 파티션 조건이 어긋나면 같은
 * 계약을 두 워커가 동시에 집어 같은 경로에 파일을 쓰고, 발급 회차가 2까지 올라간다. 여기서는
 * "전 건이 정확히 한 번씩 처리되었는가"를 본다 — 속도 측정은 BENCHMARK.md 의 몫이다.
 */
@SpringBatchTest
@SpringBootTest(properties = {
        "epolicy.batch.partition-count=4",
        "epolicy.batch.chunk-size=25"
})
class PartitionedBatchTest extends IssuanceTestBase {

    private static final int CONTRACT_COUNT = 200;

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private SyntheticContractSeeder seeder;

    @Test
    @DisplayName("4분할 실행에서 모든 계약이 정확히 한 번씩 발급된다")
    void partitionsCoverEveryContractExactlyOnce() throws Exception {
        seeder.seed(CONTRACT_COUNT);

        JobExecution execution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder().addLong("runId", System.nanoTime()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<PolicyDocumentEntity> documents = policyDocumentRepository.findAll();
        assertThat(documents).hasSize(CONTRACT_COUNT);
        assertThat(documents)
                .as("회차가 2 이상이면 같은 계약을 두 파티션이 집었다는 뜻이다")
                .allMatch(document -> document.getIssueSequence() == 1);
        assertThat(documents.stream().map(PolicyDocumentEntity::getContractNo).distinct().count())
                .isEqualTo(CONTRACT_COUNT);
        assertThat(issuanceDlqRepository.countByRetriedFalse()).isZero();
    }
}
