package com.hyunolike.epolicy.batch;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueResult;
import com.hyunolike.epolicy.application.port.out.IssuanceHistoryPort;
import com.hyunolike.epolicy.application.service.IssuanceFailedException;
import com.hyunolike.epolicy.domain.contract.ContractNo;
import com.hyunolike.epolicy.infrastructure.config.EpolicyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 1만 건 배치 (M5) + 파티셔닝 (M6).
 *
 * <p>청크 크기를 100 으로 잡은 것은 커밋 간격과 메모리의 절충이다. 청크가 크면 커밋 횟수는 줄지만
 * 처리 중인 PDF 가 그만큼 동시에 살아 있게 된다 — 메모리 전략이 {@code memory} 일 때 chunk 100 ×
 * 500KB = 50MB 가 힙에 상주하고, 그 위에 PDFBox 내부 버퍼가 얹힌다.
 *
 * <p><b>스텝 트랜잭션 밖에서 파일이 만들어진다는 점</b>에 주의가 필요하다. 청크가 롤백되면 DB 행은
 * 사라지지만 보관소의 PDF 는 남는다. 고아 파일이 생기는 것인데, 다음 발급이 같은 경로에 덮어쓰고
 * 이력이 없는 파일은 정리 배치가 지우는 것으로 다룬다. 파일 쓰기를 트랜잭션에 묶는 것은
 * 분산 트랜잭션 없이는 불가능하고, PoC 범위에서 XA 를 끌어올 이유는 없다.
 */
@Configuration
public class PolicyIssuanceJobConfig {

    public static final String JOB_NAME = "policyIssuanceJob";

    /**
     * 파티션 조건.
     *
     * <p>{@code RIGHT}/{@code MOD}/{@code CAST} 만으로 표현해 H2 와 PostgreSQL 양쪽에서 그대로 돈다.
     * 파티션을 쓰지 않을 때는 count=1, index=0 이라 조건이 항상 참이 된다.
     */
    private static final String PARTITION_PREDICATE =
            "where mod(cast(right(contract_no, 4) as integer), :partitionCount) = :partitionIndex";

    @Bean
    public Job policyIssuanceJob(JobRepository jobRepository, Step issuancePartitionStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(issuancePartitionStep)
                .build();
    }

    /**
     * 파티션 수가 1이면 워커 스텝을 그대로 쓰고, 2 이상이면 감싼다.
     *
     * <p>분기를 두는 이유는 측정 때문이다. 파티셔닝을 켜고 끈 두 실행이 같은 워커 스텝을 쓰지 않으면
     * 비교가 성립하지 않는다.
     */
    @Bean
    public Step issuancePartitionStep(JobRepository jobRepository, Step issuePolicyStep,
                                      EpolicyProperties properties) {
        int partitions = Math.max(1, properties.getBatch().getPartitionCount());
        if (partitions == 1) {
            return issuePolicyStep;
        }
        SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor("epolicy-partition-");
        // 동시 실행 수를 제한하지 않으면 파티션 수만큼 스레드가 한꺼번에 뜨고, 각 스레드가 PDF 를
        // 하나씩 들고 있게 된다. 힙 상한이 고정된 배치에서는 이것이 바로 OOM 경로다.
        taskExecutor.setConcurrencyLimit(Math.max(1, properties.getBatch().getGridConcurrency()));

        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(issuePolicyStep);
        handler.setGridSize(partitions);
        handler.setTaskExecutor(taskExecutor);
        return new StepBuilder("issuancePartitionStep", jobRepository)
                .partitioner(issuePolicyStep.getName(), new ContractPartitioner())
                .partitionHandler(handler)
                .build();
    }

    @Bean
    public Step issuePolicyStep(JobRepository jobRepository,
                                PlatformTransactionManager transactionManager,
                                JdbcPagingItemReader<ContractNo> contractItemReader,
                                PolicyDocumentProcessor policyDocumentProcessor,
                                DocumentStoreWriter documentStoreWriter,
                                IssuanceSkipListener issuanceSkipListener,
                                EpolicyProperties properties) {
        return new StepBuilder("issuePolicyStep", jobRepository)
                .<ContractNo, IssueResult>chunk(properties.getBatch().getChunkSize(), transactionManager)
                .reader(contractItemReader)
                .processor(policyDocumentProcessor)
                .writer(documentStoreWriter)
                .faultTolerant()
                // 발급 실패는 건 단위로 건너뛴다. 그 외 예외(설정 오류, DB 단절)는 Job 을 세운다 —
                // 1만 건을 전부 DLQ 에 넣고 "성공"으로 끝내는 것이 최악이다.
                .skip(IssuanceFailedException.class)
                .skipLimit(properties.getBatch().getSkipLimit())
                .listener((SkipListener<ContractNo, IssueResult>) issuanceSkipListener)
                .listener((StepExecutionListener) issuanceSkipListener)
                .build();
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<ContractNo> contractItemReader(
            DataSource dataSource,
            EpolicyProperties properties,
            @Value("#{stepExecutionContext['partitionIndex']}") Integer partitionIndex,
            @Value("#{stepExecutionContext['partitionCount']}") Integer partitionCount) throws Exception {

        SqlPagingQueryProviderFactoryBean queryProvider = new SqlPagingQueryProviderFactoryBean();
        queryProvider.setDataSource(dataSource);
        queryProvider.setSelectClause("select contract_no");
        queryProvider.setFromClause("from contract");
        queryProvider.setWhereClause(PARTITION_PREDICATE);
        queryProvider.setSortKeys(Map.of("contract_no", Order.ASCENDING));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("partitionCount", partitionCount == null ? 1 : partitionCount);
        parameters.put("partitionIndex", partitionIndex == null ? 0 : partitionIndex);

        JdbcPagingItemReader<ContractNo> reader = new JdbcPagingItemReader<>();
        reader.setDataSource(dataSource);
        reader.setQueryProvider(queryProvider.getObject());
        reader.setParameterValues(parameters);
        reader.setPageSize(properties.getBatch().getPageSize());
        // 계약 원장 전체를 커서로 붙들지 않고 페이지 단위로 끊어 읽는다. 1만 건이면 차이가 작지만,
        // 100만 건에서 커서 방식은 커넥션을 잡 실행 내내 점유한다.
        reader.setRowMapper((rs, rowNum) -> ContractNo.of(rs.getString("contract_no")));
        reader.setName("contractItemReader");
        return reader;
    }

    @Bean
    public PolicyDocumentProcessor policyDocumentProcessor(IssuePolicyUseCase issuePolicyUseCase) {
        return new PolicyDocumentProcessor(issuePolicyUseCase);
    }

    @Bean
    public DocumentStoreWriter documentStoreWriter(MeterRegistry meterRegistry) {
        return new DocumentStoreWriter(meterRegistry);
    }

    @Bean
    public IssuanceSkipListener issuanceSkipListener(IssuanceHistoryPort issuanceHistoryPort) {
        return new IssuanceSkipListener(issuanceHistoryPort);
    }
}
