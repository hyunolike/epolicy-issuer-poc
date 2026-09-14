package com.hyunolike.epolicy.support;

import com.hyunolike.epolicy.infrastructure.config.EpolicyProperties;
import com.hyunolike.epolicy.infrastructure.persistence.ContractRepository;
import com.hyunolike.epolicy.infrastructure.persistence.IssuanceDlqRepository;
import com.hyunolike.epolicy.infrastructure.persistence.IssuanceHistoryRepository;
import com.hyunolike.epolicy.infrastructure.persistence.PolicyDocumentRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 통합 테스트 공통 준비.
 *
 * <p>인메모리 H2 는 {@code DB_CLOSE_DELAY=-1} 이라 Spring 컨텍스트가 살아 있는 동안 유지된다.
 * 컨텍스트를 공유하면 부팅이 한 번으로 끝나는 대신, 앞 테스트가 만든 발급 이력이 뒤 테스트로 흘러
 * "두 번째 발급인데 첫 발급으로 보이는" 종류의 유령 실패가 생긴다. 매 테스트 앞에서 발급 상태를
 * 전부 지우는 것으로 해결한다 — 계약 원장은 남기지 않고 테스트가 직접 넣는다.
 */
@SpringBootTest
public abstract class IssuanceTestBase {

    @Autowired
    protected ContractRepository contractRepository;

    @Autowired
    protected PolicyDocumentRepository policyDocumentRepository;

    @Autowired
    protected IssuanceHistoryRepository issuanceHistoryRepository;

    @Autowired
    protected IssuanceDlqRepository issuanceDlqRepository;

    @Autowired
    protected EpolicyProperties properties;

    @BeforeEach
    void resetIssuanceState() {
        policyDocumentRepository.deleteAll();
        issuanceHistoryRepository.deleteAll();
        issuanceDlqRepository.deleteAll();
        contractRepository.deleteAll();
        clearContents(storageRoot());
        clearContents(Path.of(properties.getPdf().getSpoolDir()));
    }

    protected Path storageRoot() {
        return Path.of(properties.getStorage().getRoot());
    }

    protected Path storedFile(String relativePath) {
        return storageRoot().resolve(relativePath);
    }

    /** 루트 디렉터리 자체는 남기고 안쪽만 비운다. 루트를 지우면 이미 기동된 어댑터가 그 자리를 잃는다. */
    private static void clearContents(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("테스트 작업 디렉터리를 비울 수 없습니다: " + root, e);
        }
    }
}
