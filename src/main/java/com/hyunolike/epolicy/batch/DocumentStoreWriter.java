package com.hyunolike.epolicy.batch;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/**
 * 청크 단위 집계.
 *
 * <p>보관소 저장과 이력 기록은 파이프라인 안에서 이미 끝났다(위 {@link PolicyDocumentProcessor} 주석
 * 참고). 여기서는 청크가 실제로 무엇을 했는지를 남긴다 — 새로 만든 건, 내용이 같아 재사용한 건,
 * 그리고 청크가 만들어 낸 바이트 총량.
 *
 * <p>재사용 건수를 따로 세는 것이 쓸모 있다. 배치를 두 번 돌렸을 때 이 값이 전체 건수와 같아야
 * 멱등성이 실제로 동작한 것이고, 0 이면 매번 새 파일을 찍어내고 있다는 뜻이다.
 */
public class DocumentStoreWriter implements ItemWriter<IssueResult> {

    private static final Logger log = LoggerFactory.getLogger(DocumentStoreWriter.class);

    private final Counter issuedCounter;
    private final Counter reusedCounter;

    public DocumentStoreWriter(MeterRegistry meterRegistry) {
        this.issuedCounter = meterRegistry.counter("epolicy.batch.documents", "outcome", "issued");
        this.reusedCounter = meterRegistry.counter("epolicy.batch.documents", "outcome", "reused");
    }

    @Override
    public void write(Chunk<? extends IssueResult> chunk) {
        List<? extends IssueResult> items = chunk.getItems();
        long reused = items.stream().filter(IssueResult::reusedExisting).count();
        long issued = items.size() - reused;
        long bytes = items.stream().mapToLong(result -> result.document().fileSize()).sum();

        issuedCounter.increment(issued);
        reusedCounter.increment(reused);
        log.debug("청크 완료: 신규 {}건, 재사용 {}건, {}KB", issued, reused, bytes / 1024);
    }
}
