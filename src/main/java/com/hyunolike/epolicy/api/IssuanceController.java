package com.hyunolike.epolicy.api;

import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueCommand;
import com.hyunolike.epolicy.application.port.in.IssuePolicyUseCase.IssueResult;
import com.hyunolike.epolicy.application.port.out.DocumentStoragePort;
import com.hyunolike.epolicy.application.port.out.IssuanceHistoryPort;
import com.hyunolike.epolicy.domain.contract.ContractNo;
import com.hyunolike.epolicy.domain.document.DocumentId;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import com.hyunolike.epolicy.domain.document.PolicyDocument;
import com.hyunolike.epolicy.domain.document.TemplateVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 발급 API.
 *
 * <p>인증/인가는 스코프 아웃이다(README 참고). 실제 시스템이라면 증권 다운로드는 본인 확인을 거쳐야
 * 하고, 여기서 증권번호만 알면 남의 증권을 받을 수 있다는 점은 의도적으로 남겨 둔 구멍이다.
 */
@RestController
@RequestMapping("/api/policies")
public class IssuanceController {

    private final IssuePolicyUseCase issuePolicyUseCase;
    private final IssuanceHistoryPort issuanceHistoryPort;
    private final DocumentStoragePort documentStoragePort;

    public IssuanceController(IssuePolicyUseCase issuePolicyUseCase,
                              IssuanceHistoryPort issuanceHistoryPort,
                              DocumentStoragePort documentStoragePort) {
        this.issuePolicyUseCase = issuePolicyUseCase;
        this.issuanceHistoryPort = issuanceHistoryPort;
        this.documentStoragePort = documentStoragePort;
    }

    @PostMapping("/{contractNo}/issue")
    public IssuanceResponse issue(@PathVariable String contractNo,
                                  @RequestParam(required = false) String templateVersion,
                                  @RequestParam(defaultValue = "false") boolean force) {
        IssueResult result = issuePolicyUseCase.issue(new IssueCommand(
                ContractNo.of(contractNo),
                templateVersion == null ? null : TemplateVersion.of(templateVersion),
                force));
        return IssuanceResponse.from(result);
    }

    /** 최근 발급분 다운로드. */
    @GetMapping("/{contractNo}/document")
    public ResponseEntity<InputStreamResource> download(@PathVariable String contractNo) throws IOException {
        PolicyDocument document = issuanceHistoryPort.findLatestStored(ContractNo.of(contractNo))
                .orElseThrow(() -> new NoSuchElementException("발급된 증권이 없습니다: " + contractNo));
        return streamOf(document);
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<InputStreamResource> downloadById(@PathVariable String documentId) throws IOException {
        PolicyDocument document = issuanceHistoryPort.findById(new DocumentId(documentId))
                .orElseThrow(() -> new NoSuchElementException("발급 이력이 없습니다: " + documentId));
        return streamOf(document);
    }

    private ResponseEntity<InputStreamResource> streamOf(PolicyDocument document) throws IOException {
        PdfArtifact artifact = documentStoragePort.load(document.storagePath())
                .orElseThrow(() -> new NoSuchElementException(
                        "보관소에서 파일을 찾을 수 없습니다: " + document.storagePath()));
        // 파일 전체를 힙에 올리지 않고 그대로 흘려보낸다. 응답이 끝나면 스트림이 닫힌다.
        InputStream in = artifact.openStream();
        String filename = "%s-r%d.pdf".formatted(
                document.contractNo().value(), document.issueSequence());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(artifact.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Content-Hash", document.contentHash() == null ? "" : document.contentHash().hex())
                .header("X-File-Hash", document.fileHash() == null ? "" : document.fileHash().hex())
                .body(new InputStreamResource(in));
    }
}
