package com.hyunolike.epolicy.infrastructure.config;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase;
import com.hyunolike.epolicy.application.port.in.VerifyDocumentUseCase;
import com.hyunolike.epolicy.application.port.out.ContractLoadPort;
import com.hyunolike.epolicy.application.port.out.DocumentSignPort;
import com.hyunolike.epolicy.application.port.out.DocumentStoragePort;
import com.hyunolike.epolicy.application.port.out.IssuanceHistoryPort;
import com.hyunolike.epolicy.application.port.out.PdfArchivePort;
import com.hyunolike.epolicy.application.port.out.PdfArtifactFactory;
import com.hyunolike.epolicy.application.port.out.PdfRenderPort;
import com.hyunolike.epolicy.application.port.out.SignatureVerificationPort;
import com.hyunolike.epolicy.application.port.out.TemplateRenderPort;
import com.hyunolike.epolicy.application.port.out.TimestampPort;
import com.hyunolike.epolicy.application.service.DocumentVerificationService;
import com.hyunolike.epolicy.application.service.PolicyIssuanceService;
import com.hyunolike.epolicy.domain.document.PolicyViewAssembler;
import com.hyunolike.epolicy.domain.masking.DefaultMaskingPolicy;
import com.hyunolike.epolicy.domain.masking.MaskingPolicy;
import com.hyunolike.epolicy.infrastructure.buffer.InMemoryPdfArtifactFactory;
import com.hyunolike.epolicy.infrastructure.buffer.TempFilePdfArtifactFactory;
import com.hyunolike.epolicy.infrastructure.pdfa.PdfBoxArchiveAdapter;
import com.hyunolike.epolicy.infrastructure.persistence.ContractRepository;
import com.hyunolike.epolicy.infrastructure.persistence.IssuanceDlqRepository;
import com.hyunolike.epolicy.infrastructure.persistence.IssuanceHistoryRepository;
import com.hyunolike.epolicy.infrastructure.persistence.JpaContractLoadAdapter;
import com.hyunolike.epolicy.infrastructure.persistence.JpaIssuanceHistoryAdapter;
import com.hyunolike.epolicy.infrastructure.persistence.PolicyDocumentRepository;
import com.hyunolike.epolicy.infrastructure.render.OpenHtmlPdfAdapter;
import com.hyunolike.epolicy.infrastructure.render.ThymeleafTemplateAdapter;
import com.hyunolike.epolicy.infrastructure.sign.DisabledTimestampAdapter;
import com.hyunolike.epolicy.infrastructure.sign.NoSignatureAdapter;
import com.hyunolike.epolicy.infrastructure.sign.PadesSignAdapter;
import com.hyunolike.epolicy.infrastructure.sign.PdfBoxSignatureVerificationAdapter;
import com.hyunolike.epolicy.infrastructure.sign.SelfSignedSignerFactory;
import com.hyunolike.epolicy.infrastructure.sign.SignerMaterial;
import com.hyunolike.epolicy.infrastructure.sign.TsaClientAdapter;
import com.hyunolike.epolicy.infrastructure.storage.LocalFsStorageAdapter;
import com.hyunolike.epolicy.support.SyntheticContractSeeder;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.thymeleaf.ITemplateEngine;

/**
 * 포트 ↔ 어댑터 결선.
 *
 * <p>이 클래스가 헥사고날 구조의 값이 실현되는 지점이다. 렌더러를 PD4ML 로 바꾸거나 보관소를 S3 로
 * 바꾸는 변경은 전부 여기 한 파일의 한 줄이다. 파이프라인 코드는 손대지 않는다.
 */
@Configuration
public class IssuanceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IssuanceConfiguration.class);

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public MaskingPolicy maskingPolicy() {
        return new DefaultMaskingPolicy();
    }

    @Bean
    public PolicyViewAssembler policyViewAssembler(MaskingPolicy maskingPolicy) {
        return new PolicyViewAssembler(maskingPolicy);
    }

    /**
     * PDF 버퍼 전략.
     *
     * <p>{@code epolicy.pdf.buffer-strategy} 하나로 힙 상주 여부가 바뀐다. 1만 건 배치의 메모리
     * 측정은 이 빈을 갈아끼우며 같은 잡을 두 번 돌리는 것으로 한다.
     */
    @Bean
    public PdfArtifactFactory pdfArtifactFactory(EpolicyProperties properties) {
        String strategy = properties.getPdf().getBufferStrategy();
        PdfArtifactFactory factory = "temp-file".equalsIgnoreCase(strategy)
                ? new TempFilePdfArtifactFactory(Path.of(properties.getPdf().getSpoolDir()))
                : new InMemoryPdfArtifactFactory();
        log.info("PDF 버퍼 전략: {}", factory.strategyName());
        return factory;
    }

    @Bean
    public TemplateRenderPort templateRenderPort(ITemplateEngine templateEngine, EpolicyProperties properties) {
        return new ThymeleafTemplateAdapter(templateEngine, properties.getIssuerName());
    }

    @Bean
    public PdfRenderPort pdfRenderPort(
            @Value("classpath:fonts/NanumGothic-Regular.ttf") Resource regularFont,
            @Value("classpath:fonts/NanumGothic-Bold.ttf") Resource boldFont,
            PdfArtifactFactory artifactFactory,
            EpolicyProperties properties) {
        return new OpenHtmlPdfAdapter(regularFont, boldFont, artifactFactory,
                properties.getPdf().isSubsetFonts());
    }

    @Bean
    public PdfArchivePort pdfArchivePort(
            @Value("classpath:icc/sRGB-v2-micro.icc") Resource iccProfile,
            PdfArtifactFactory artifactFactory,
            EpolicyProperties properties) {
        boolean tempFileCache = "temp-file".equalsIgnoreCase(properties.getPdf().getBufferStrategy());
        return new PdfBoxArchiveAdapter(iccProfile, artifactFactory, tempFileCache);
    }

    @Bean
    public TimestampPort timestampPort(EpolicyProperties properties) {
        EpolicyProperties.Tsa tsa = properties.getSign().getTsa();
        if (!tsa.isEnabled()) {
            log.info("TSA 비활성화 — PAdES-B-B 로 서명합니다");
            return new DisabledTimestampAdapter();
        }
        TsaClientAdapter adapter = new TsaClientAdapter(
                tsa.getUrl(), Duration.ofMillis(tsa.getTimeoutMillis()), tsa.isRequestCertificate());
        adapter.logEndpoint();
        return adapter;
    }

    @Bean
    public DocumentSignPort documentSignPort(EpolicyProperties properties, TimestampPort timestampPort,
                                             PdfArtifactFactory artifactFactory) {
        EpolicyProperties.Sign sign = properties.getSign();
        if (!sign.isEnabled()) {
            log.warn("서명이 비활성화되어 있습니다 — 발급 문서에 전자서명이 붙지 않습니다");
            return new NoSignatureAdapter(artifactFactory);
        }
        Path keystore = Path.of(sign.getKeystorePath());
        if (sign.isGenerateIfAbsent()) {
            SelfSignedSignerFactory.generateKeyStore(
                    keystore, sign.getKeyAlias(), sign.getKeystorePassword(), properties.getIssuerName());
        }
        SignerMaterial signer = SelfSignedSignerFactory.load(
                keystore, sign.getKeyAlias(), sign.getKeystorePassword());
        log.info("서명자: {}", signer.signerName());
        return new PadesSignAdapter(signer, timestampPort, artifactFactory);
    }

    @Bean
    public SignatureVerificationPort signatureVerificationPort() {
        return new PdfBoxSignatureVerificationAdapter();
    }

    @Bean
    public DocumentStoragePort documentStoragePort(EpolicyProperties properties) {
        return new LocalFsStorageAdapter(Path.of(properties.getStorage().getRoot()));
    }

    @Bean
    public ContractLoadPort contractLoadPort(ContractRepository repository) {
        return new JpaContractLoadAdapter(repository);
    }

    @Bean
    public IssuanceHistoryPort issuanceHistoryPort(PolicyDocumentRepository documentRepository,
                                                   IssuanceHistoryRepository historyRepository,
                                                   IssuanceDlqRepository dlqRepository,
                                                   Clock clock) {
        return new JpaIssuanceHistoryAdapter(documentRepository, historyRepository, dlqRepository, clock);
    }

    @Bean
    public IssuePolicyUseCase issuePolicyUseCase(ContractLoadPort contractLoadPort,
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
        return new PolicyIssuanceService(contractLoadPort, viewAssembler, templateRenderPort,
                pdfRenderPort, pdfArchivePort, documentSignPort, documentStoragePort,
                issuanceHistoryPort, properties, clock, meterRegistry);
    }

    @Bean
    public SyntheticContractSeeder syntheticContractSeeder(ContractRepository repository) {
        return new SyntheticContractSeeder(repository);
    }

    @Bean
    public VerifyDocumentUseCase verifyDocumentUseCase(IssuanceHistoryPort issuanceHistoryPort,
                                                       DocumentStoragePort documentStoragePort,
                                                       SignatureVerificationPort signatureVerificationPort) {
        return new DocumentVerificationService(
                issuanceHistoryPort, documentStoragePort, signatureVerificationPort);
    }
}
