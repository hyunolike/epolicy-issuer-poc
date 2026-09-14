package com.hyunolike.epolicy.application.service;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase;
import com.hyunolike.epolicy.application.port.out.ArchiveMetadata;
import com.hyunolike.epolicy.application.port.out.ContractLoadPort;
import com.hyunolike.epolicy.application.port.out.DocumentSignPort;
import com.hyunolike.epolicy.application.port.out.DocumentStoragePort;
import com.hyunolike.epolicy.application.port.out.IssuanceHistoryPort;
import com.hyunolike.epolicy.application.port.out.PdfArchivePort;
import com.hyunolike.epolicy.application.port.out.PdfRenderPort;
import com.hyunolike.epolicy.application.port.out.SignRequest;
import com.hyunolike.epolicy.application.port.out.StoredLocation;
import com.hyunolike.epolicy.application.port.out.TemplateRenderPort;
import com.hyunolike.epolicy.domain.contract.Contract;
import com.hyunolike.epolicy.domain.contract.ContractNo;
import com.hyunolike.epolicy.domain.document.ContentHash;
import com.hyunolike.epolicy.domain.document.IssuanceStatus;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import com.hyunolike.epolicy.domain.document.PolicyDocument;
import com.hyunolike.epolicy.domain.document.PolicyView;
import com.hyunolike.epolicy.domain.document.PolicyViewAssembler;
import com.hyunolike.epolicy.domain.document.TemplateVersion;
import com.hyunolike.epolicy.infrastructure.config.EpolicyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 발급 파이프라인 오케스트레이션.
 *
 * <p>단계 순서 자체가 규제 요건이다. 마스킹은 템플릿 진입 전에, PDF/A 변환은 서명 전에, contentHash 는
 * 서명 전에 — 셋 다 뒤집을 수 없다. 근거는 docs/DESIGN.md 3.2.
 *
 * <p>{@link PdfArtifact} 는 단계마다 새로 생기고 이전 것은 즉시 닫는다. 임시파일 전략에서 close 를
 * 빠뜨리면 1만 건 배치가 디스크를 채운다.
 */
public class PolicyIssuanceService implements IssuePolicyUseCase {

    private static final Logger log = LoggerFactory.getLogger(PolicyIssuanceService.class);

    /** PDF 문서 시각의 기준 타임존. 벽시계가 아니라 계약 체결일을 이 타임존의 자정으로 해석한다. */
    private static final ZoneId DOCUMENT_ZONE = ZoneId.of("Asia/Seoul");

    private final ContractLoadPort contractLoadPort;
    private final PolicyViewAssembler viewAssembler;
    private final TemplateRenderPort templateRenderPort;
    private final PdfRenderPort pdfRenderPort;
    private final PdfArchivePort pdfArchivePort;
    private final DocumentSignPort documentSignPort;
    private final DocumentStoragePort documentStoragePort;
    private final IssuanceHistoryPort issuanceHistoryPort;
    private final EpolicyProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public PolicyIssuanceService(
            ContractLoadPort contractLoadPort,
            PolicyViewAssembler viewAssembler,
            TemplateRenderPort templateRenderPort,
            PdfRenderPort pdfRenderPort,
            PdfArchivePort pdfArchivePort,
            DocumentSignPort documentSignPort,
            DocumentStoragePort documentStoragePort,
            IssuanceHistoryPort issuanceHistoryPort,
            EpolicyProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.contractLoadPort = contractLoadPort;
        this.viewAssembler = viewAssembler;
        this.templateRenderPort = templateRenderPort;
        this.pdfRenderPort = pdfRenderPort;
        this.pdfArchivePort = pdfArchivePort;
        this.documentSignPort = documentSignPort;
        this.documentStoragePort = documentStoragePort;
        this.issuanceHistoryPort = issuanceHistoryPort;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public IssueResult issue(IssueCommand command) {
        Timer.Sample whole = Timer.start(meterRegistry);
        ContractNo contractNo = command.contractNo();
        TemplateVersion templateVersion = command.templateVersion() != null
                ? command.templateVersion()
                : TemplateVersion.of(properties.getDefaultTemplateVersion());

        Contract contract = contractLoadPort.findByContractNo(contractNo)
                .orElseThrow(() -> new IssuanceFailedException(
                        contractNo, IssuanceStatus.REQUESTED, "계약을 찾을 수 없습니다", null));

        IssuanceStatus reached = IssuanceStatus.REQUESTED;
        PdfArtifact rendered = null;
        PdfArtifact archived = null;
        PdfArtifact signed = null;
        try {
            // [1][2] 마스킹 → 템플릿 바인딩. 이 지점을 지나면 원본 개인정보는 접근 경로가 없다.
            PolicyView view = viewAssembler.assemble(contract, templateVersion);
            String html = timed("template", () -> templateRenderPort.render(view, templateVersion));

            // [3] HTML → PDF
            rendered = timedIo("render", () -> pdfRenderPort.render(html));
            reached = IssuanceStatus.RENDERED;

            // [4] PDF/A-1b. 반드시 서명 전.
            ArchiveMetadata metadata = archiveMetadata(contract, templateVersion);
            PdfArtifact source = rendered;
            archived = timedIo("pdfa", () -> pdfArchivePort.toArchivalPdf(source, metadata));
            reached = IssuanceStatus.ARCHIVED;
            rendered.close();
            rendered = null;

            // [5] contentHash — 서명 전 바이트. 멱등성 판단 기준.
            ContentHash contentHash;
            try (InputStream in = archived.openStream()) {
                contentHash = ContentHash.of(in);
            }

            Optional<PolicyDocument> reusable = findReusable(contractNo, contentHash, command.forceReissue());
            if (reusable.isPresent()) {
                log.info("[{}] 내용 동일 — 기존 발급분 재사용 (contentHash={})",
                        contractNo, contentHash.shortHex());
                meterRegistry.counter("epolicy.issue.reused").increment();
                return new IssueResult(reusable.get(), true, stop(whole));
            }

            int issueSequence = issuanceHistoryPort.nextIssueSequence(contractNo);
            PolicyDocument document = PolicyDocument.requested(contractNo, issueSequence, templateVersion)
                    .archived(contentHash);
            issuanceHistoryPort.recordTransition(document, IssuanceStatus.ARCHIVED,
                    "contentHash=" + contentHash.shortHex());

            // [6] PAdES 서명
            SignRequest signRequest = signRequest();
            PdfArtifact toSign = archived;
            signed = timedIo("sign", () -> documentSignPort.sign(toSign, signRequest));
            reached = IssuanceStatus.SIGNED;
            archived.close();
            archived = null;
            document = document.signed(signRequest.signedAt());
            issuanceHistoryPort.recordTransition(document, IssuanceStatus.SIGNED,
                    signRequest.withTimestamp() ? "PAdES-B-T" : "PAdES-B-B");

            // [7] 보관소 저장 + 이력 기록
            PdfArtifact toStore = signed;
            PolicyDocument stored = document;
            StoredLocation location = timedIo("store", () -> documentStoragePort.store(stored, toStore));
            document = document.stored(location.path(), location.fileHash(), location.size());
            document = issuanceHistoryPort.save(document);
            issuanceHistoryPort.recordTransition(document, IssuanceStatus.STORED,
                    "path=" + location.path() + ", size=" + location.size());
            reached = IssuanceStatus.STORED;

            Duration elapsed = stop(whole);
            log.info("[{}] 발급 완료 seq={} content={} file={} {}bytes {}ms",
                    contractNo, document.issueSequence(), contentHash.shortHex(),
                    location.fileHash().shortHex(), location.size(), elapsed.toMillis());
            return new IssueResult(document, false, elapsed);

        } catch (IssuanceFailedException e) {
            issuanceHistoryPort.recordFailure(contractNo, e.stage(), rootMessage(e));
            throw e;
        } catch (Exception e) {
            issuanceHistoryPort.recordFailure(contractNo, reached.name(), rootMessage(e));
            throw new IssuanceFailedException(contractNo, reached, rootMessage(e), e);
        } finally {
            closeQuietly(signed);
            closeQuietly(archived);
            closeQuietly(rendered);
        }
    }

    /**
     * 재사용 가능한 직전 발급분을 찾는다.
     *
     * <p>contentHash 가 같다는 것만으로는 부족하다. 보관소에서 파일이 사라졌다면 이력만 남고 교부할
     * 실체가 없으므로, 실제 파일 존재까지 확인한 뒤에만 재사용한다.
     */
    private Optional<PolicyDocument> findReusable(ContractNo contractNo, ContentHash contentHash, boolean force) {
        if (force) {
            return Optional.empty();
        }
        Optional<PolicyDocument> latest = issuanceHistoryPort.findLatestStored(contractNo);
        if (latest.isEmpty() || !latest.get().hasSameContentAs(contentHash)) {
            return Optional.empty();
        }
        try {
            Optional<PdfArtifact> file = documentStoragePort.load(latest.get().storagePath());
            if (file.isEmpty()) {
                log.warn("[{}] 이력은 있으나 보관 파일이 없어 재발급합니다: {}",
                        contractNo, latest.get().storagePath());
                return Optional.empty();
            }
            file.get().close();
            return latest;
        } catch (IOException e) {
            log.warn("[{}] 보관 파일 확인 실패 — 재발급으로 처리합니다", contractNo, e);
            return Optional.empty();
        }
    }

    private ArchiveMetadata archiveMetadata(Contract contract, TemplateVersion templateVersion) {
        return new ArchiveMetadata(
                "전자보험증권 " + contract.contractNo().value(),
                properties.getIssuerName(),
                contract.productCode().displayName(),
                "전자보험증권, " + contract.productCode().value(),
                properties.getPdf().getCreatorTool(),
                properties.getPdf().getProducer(),
                // 벽시계가 아니라 계약 체결일. 재발급해도 같은 바이트가 나오게 하는 핵심이다.
                contract.issuedOn().atStartOfDay(DOCUMENT_ZONE).toInstant(),
                contract.contractNo().value() + "|" + templateVersion.value());
    }

    private SignRequest signRequest() {
        EpolicyProperties.Sign sign = properties.getSign();
        return new SignRequest(
                sign.getReason(), sign.getLocation(), sign.getContactInfo(),
                clock.instant(), sign.getTsa().isEnabled());
    }

    private <T> T timed(String stage, java.util.function.Supplier<T> action) {
        return meterRegistry.timer("epolicy.issue.stage", "stage", stage).record(action);
    }

    private <T> T timedIo(String stage, IoSupplier<T> action) throws IOException {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return action.get();
        } finally {
            sample.stop(meterRegistry.timer("epolicy.issue.stage", "stage", stage));
        }
    }

    private Duration stop(Timer.Sample sample) {
        long nanos = sample.stop(meterRegistry.timer("epolicy.issue.total"));
        return Duration.ofNanos(nanos);
    }

    private static void closeQuietly(PdfArtifact artifact) {
        if (artifact != null) {
            artifact.close();
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cursor = t;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
