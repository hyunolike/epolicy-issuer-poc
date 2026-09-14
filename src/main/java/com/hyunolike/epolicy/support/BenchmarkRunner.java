package com.hyunolike.epolicy.support;

import com.hyunolike.epolicy.application.port.out.PdfArtifactFactory;
import com.hyunolike.epolicy.application.port.out.PdfRenderPort;
import com.hyunolike.epolicy.infrastructure.config.EpolicyProperties;
import com.hyunolike.epolicy.infrastructure.persistence.ContractRepository;
import com.hyunolike.epolicy.infrastructure.persistence.IssuanceDlqRepository;
import com.hyunolike.epolicy.infrastructure.persistence.PolicyDocumentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 측정 러너 (M6).
 *
 * <p>{@code --spring.profiles.active=benchmark} 로 띄우면 합성 데이터를 만들고 발급 배치를 한 번 돌린
 * 뒤, docs/BENCHMARK.md 에 그대로 붙일 수 있는 마크다운 표를 찍고 종료한다.
 *
 * <p>힙 최대치를 0.2초 간격 샘플링으로 잡는다. {@code Runtime.totalMemory()} 를 끝에 한 번 읽는
 * 방식으로는 GC 가 방금 쓸어간 뒤의 값을 보게 되어 항상 낮게 나온다. 정확한 값은 JFR 로 봐야 하지만,
 * "512MB 안에서 완주하는가"를 판단하는 데는 샘플링 최대치로 충분하다.
 */
@Component
@Profile("benchmark")
public class BenchmarkRunner implements ApplicationRunner {

    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(200);

    private final SyntheticContractSeeder seeder;
    private final JobLauncher jobLauncher;
    private final Job policyIssuanceJob;
    private final PolicyDocumentRepository documentRepository;
    private final IssuanceDlqRepository dlqRepository;
    private final ContractRepository contractRepository;
    private final MeterRegistry meterRegistry;
    private final PdfArtifactFactory artifactFactory;
    private final PdfRenderPort renderPort;
    private final EpolicyProperties properties;

    public BenchmarkRunner(SyntheticContractSeeder seeder, JobLauncher jobLauncher,
                           Job policyIssuanceJob, PolicyDocumentRepository documentRepository,
                           IssuanceDlqRepository dlqRepository, ContractRepository contractRepository,
                           MeterRegistry meterRegistry, PdfArtifactFactory artifactFactory,
                           PdfRenderPort renderPort, EpolicyProperties properties) {
        this.seeder = seeder;
        this.jobLauncher = jobLauncher;
        this.policyIssuanceJob = policyIssuanceJob;
        this.documentRepository = documentRepository;
        this.dlqRepository = dlqRepository;
        this.contractRepository = contractRepository;
        this.meterRegistry = meterRegistry;
        this.artifactFactory = artifactFactory;
        this.renderPort = renderPort;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int count = intArg(args, "count", 10_000);

        long seedStart = System.nanoTime();
        seeder.seed(count);
        Duration seedElapsed = Duration.ofNanos(System.nanoTime() - seedStart);

        HeapSampler sampler = new HeapSampler();
        sampler.start();
        long gcBefore = totalGcMillis();
        long start = System.nanoTime();
        JobExecution execution = jobLauncher.run(policyIssuanceJob, new JobParametersBuilder()
                .addLong("runId", System.nanoTime())
                .toJobParameters());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        long gcElapsed = totalGcMillis() - gcBefore;
        sampler.stop();

        report(count, seedElapsed, elapsed, gcElapsed, sampler.peakHeapBytes(), execution);
    }

    private void report(int count, Duration seedElapsed, Duration elapsed, long gcMillis,
                        long peakHeap, JobExecution execution) {
        Timer total = meterRegistry.find("epolicy.issue.total").timer();
        long issued = documentRepository.count();
        long failed = dlqRepository.countByRetriedFalse();
        long totalBytes = documentRepository.findAll().stream()
                .mapToLong(document -> document.getFileSize()).sum();

        StringBuilder out = new StringBuilder("\n");
        out.append("## 측정 결과\n\n");
        out.append("| 항목 | 값 |\n|---|---|\n");
        row(out, "대상 계약", "%,d건".formatted(contractRepository.count()));
        row(out, "발급 성공", "%,d건".formatted(issued));
        row(out, "DLQ 적재", "%,d건".formatted(failed));
        row(out, "Job 상태", execution.getStatus().name());
        row(out, "렌더러", renderPort.rendererName());
        row(out, "PDF 버퍼 전략", artifactFactory.strategyName());
        row(out, "청크 크기", String.valueOf(properties.getBatch().getChunkSize()));
        row(out, "파티션 수", String.valueOf(properties.getBatch().getPartitionCount()));
        row(out, "서명", properties.getSign().isEnabled()
                ? (properties.getSign().getTsa().isEnabled() ? "PAdES-B-T" : "PAdES-B-B") : "없음");
        row(out, "합성 데이터 생성", "%,dms".formatted(seedElapsed.toMillis()));
        row(out, "배치 총 소요", "%,dms".formatted(elapsed.toMillis()));
        row(out, "처리량", issued == 0 ? "-"
                : "%.1f건/초".formatted(issued * 1000.0 / Math.max(1, elapsed.toMillis())));
        if (total != null && total.count() > 0) {
            // Timer.percentile(..) 은 deprecated 다. 스냅샷에서 읽는 쪽이 히스토그램 설정
            // (application.yml 의 management.metrics.distribution.percentiles)과 일관된 값을 준다.
            HistogramSnapshot snapshot = total.takeSnapshot();
            for (ValueAtPercentile point : snapshot.percentileValues()) {
                row(out, "건당 p%.0f".formatted(point.percentile() * 100),
                        "%.0fms".formatted(point.value(TimeUnit.MILLISECONDS)));
            }
            row(out, "건당 최대", "%.0fms".formatted(total.max(TimeUnit.MILLISECONDS)));
        }
        stageRows(out);
        row(out, "평균 파일 크기", issued == 0 ? "-" : "%,dKB".formatted(totalBytes / issued / 1024));
        row(out, "힙 최대(샘플링)", "%,dMB".formatted(peakHeap / 1024 / 1024));
        row(out, "힙 상한(-Xmx)", "%,dMB".formatted(Runtime.getRuntime().maxMemory() / 1024 / 1024));
        row(out, "GC 소요", "%,dms".formatted(gcMillis));
        System.out.println(out);
    }

    private void stageRows(StringBuilder out) {
        for (String stage : List.of("template", "render", "pdfa", "sign", "store")) {
            Timer timer = meterRegistry.find("epolicy.issue.stage").tag("stage", stage).timer();
            if (timer != null && timer.count() > 0) {
                row(out, "  단계 " + stage + " 평균",
                        "%.1fms".formatted(timer.mean(TimeUnit.MILLISECONDS)));
            }
        }
    }

    private static void row(StringBuilder out, String label, String value) {
        out.append("| ").append(label).append(" | ").append(value).append(" |\n");
    }

    private static long totalGcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime)
                .sum();
    }

    private static int intArg(ApplicationArguments args, String name, int fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : Integer.parseInt(values.get(0));
    }

    /** 힙 사용량을 주기적으로 읽어 최대치를 기억한다. */
    private static final class HeapSampler {

        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final AtomicLong peak = new AtomicLong();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private Thread thread;

        void start() {
            thread = new Thread(() -> {
                while (running.get()) {
                    peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
                    try {
                        Thread.sleep(SAMPLE_INTERVAL.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "heap-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running.set(false);
            if (thread != null) {
                thread.interrupt();
            }
        }

        long peakHeapBytes() {
            return peak.get();
        }
    }
}
