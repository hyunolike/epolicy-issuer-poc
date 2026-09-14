package com.hyunolike.epolicy.api;

import com.hyunolike.epolicy.batch.PolicyIssuanceJobConfig;
import com.hyunolike.epolicy.infrastructure.persistence.IssuanceDlqEntity;
import com.hyunolike.epolicy.infrastructure.persistence.IssuanceDlqRepository;
import com.hyunolike.epolicy.support.SyntheticContractSeeder;
import java.util.List;
import java.util.Map;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PoC 운영 도구.
 *
 * <p>합성 데이터 생성, 배치 기동, DLQ 조회를 HTTP 로 뚫어 둔다. 운영 시스템이라면 절대 인증 없이
 * 노출하지 않을 엔드포인트다 — 여기서는 측정과 재현을 손쉽게 하려는 PoC 용 도구다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final SyntheticContractSeeder seeder;
    private final JobLauncher jobLauncher;
    private final Job policyIssuanceJob;
    private final IssuanceDlqRepository dlqRepository;

    public AdminController(SyntheticContractSeeder seeder, JobLauncher jobLauncher,
                           Job policyIssuanceJob, IssuanceDlqRepository dlqRepository) {
        this.seeder = seeder;
        this.jobLauncher = jobLauncher;
        this.policyIssuanceJob = policyIssuanceJob;
        this.dlqRepository = dlqRepository;
    }

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "10000") int count,
                                    @RequestParam(required = false) Long randomSeed) {
        int inserted = randomSeed == null ? seeder.seed(count) : seeder.seed(count, randomSeed);
        return Map.of("inserted", inserted);
    }

    /**
     * 발급 배치 기동.
     *
     * <p>{@code runId} 를 파라미터로 넣는 이유 — Spring Batch 는 같은 파라미터의 Job 을 두 번 실행하지
     * 않는다. 측정을 위해 같은 잡을 반복해서 돌려야 하므로 매번 다른 값을 준다.
     */
    @PostMapping("/batch/run")
    public Map<String, Object> runBatch() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addLong("runId", System.currentTimeMillis())
                .toJobParameters();
        var execution = jobLauncher.run(policyIssuanceJob, parameters);
        return Map.of(
                "job", PolicyIssuanceJobConfig.JOB_NAME,
                "executionId", execution.getId(),
                "status", execution.getStatus().name());
    }

    @GetMapping("/dlq")
    public Map<String, Object> deadLetters() {
        List<IssuanceDlqEntity> pending = dlqRepository.findByRetriedFalseOrderByDlqIdAsc();
        return Map.of(
                "pending", pending.size(),
                "items", pending.stream()
                        .limit(100)
                        .map(entry -> Map.of(
                                "contractNo", entry.getContractNo(),
                                "stage", entry.getStage(),
                                "reason", entry.getReason(),
                                "occurredAt", entry.getOccurredAt().toString()))
                        .toList());
    }
}
