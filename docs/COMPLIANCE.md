# 규제 요건 ↔ 구현 매핑

> 이 문서는 **엔지니어가 읽은 요건**을 구현과 연결한 것이다. 법률 자문이 아니고, 실제 적용에는
> 준법감시 부서의 검토가 필요하다. 조문은 "왜 이 구조인가"를 설명하기 위한 참조이지 준거 판단이 아니다.
>
> 그리고 이 PoC 는 **합성 데이터만** 다룬다. 실 개인정보를 넣는 순간 아래 표의 절반은 부족해진다
> (특히 저장 암호화와 접근통제 — 둘 다 스코프 아웃이다).

---

## 1. 요건별 매핑

### 전자문서의 효력 · 장기보존

| 요건 해석 | 아키텍처 결정 | 구현 | 검증 |
|---|---|---|---|
| 전자문서가 서면을 갈음하려면 **내용을 열람할 수 있고 그 형태로 보존**될 수 있어야 한다 (전자문서법 §4~§5) | 표시 재현성을 표준으로 보장 → PDF/A-1b | `PdfBoxArchiveAdapter` | `PolicyIssuancePipelineTest.isPdfA1bCompliant` (veraPDF) |
| 장기보존 시 **외부 의존 없이** 동일하게 렌더되어야 한다 | 폰트 임베딩 강제, 외부 리소스 참조 금지 | 템플릿 CSS 인라인, 폰트는 코드로 주입, 이미지 없음 | 같은 테스트 (임베딩 누락 시 veraPDF 가 잡는다) |
| 색 재현 기준이 문서에 있어야 한다 | OutputIntent + sRGB ICC | `PdfBoxArchiveAdapter.applyOutputIntent` | 같은 테스트 |
| 메타데이터가 일관되어야 한다 | 문서정보 딕셔너리와 XMP 를 같은 값으로 | `applyDocumentInformation` + `applyXmpMetadata` | 같은 테스트 (불일치는 PDF/A-1b 위반) |

### 문서 무결성 · 전자서명

| 요건 해석 | 아키텍처 결정 | 구현 | 검증 |
|---|---|---|---|
| 전자서명은 **서명 이후 변경 여부를 확인**할 수 있어야 한다 (전자서명법 §3, §2) | PAdES 증분 서명 — ByteRange 가 파일 전체를 덮는다 | `PadesSignAdapter` | `DocumentIntegrityTest.detectsSingleByteTampering` |
| 서명 범위 밖에 내용을 덧붙이는 공격을 막아야 한다 | 검증 시 ByteRange 가 파일 끝까지 덮는지 별도 확인 | `PdfBoxSignatureVerificationAdapter.coversWholeDocument` | `detectsAppendedContent` |
| 서명자와 서명 대상의 결합이 증명되어야 한다 | ESS `signing-certificate-v2` 를 서명 속성에 포함 | `PadesSignAdapter.signingCertificateV2Attribute` | 인증서 바꿔치기 시 검증 실패 |
| 서명 시점의 증명이 필요하다 | RFC 3161 타임스탬프 (PAdES-B-T) | `TsaClientAdapter` | 기본 비활성 — **아래 한계 참고** |
| 서명 대상 문서가 확정된 뒤에만 서명할 수 있다 | PDF/A 변환을 서명 앞 단계로 고정 | 파이프라인 [4] → [6] 순서 | 순서를 바꾸면 서명이 깨진다 |

### 증권 교부 증적

| 요건 해석 | 아키텍처 결정 | 구현 | 검증 |
|---|---|---|---|
| 보험자는 계약 성립 시 **보험증권을 교부**해야 한다 (상법 §640) | 발급 이력을 append-only 로 보존 | `issuance_history` | `PolicyIssuanceBatchTest` |
| 무엇을 언제 교부했는지 사후에 특정할 수 있어야 한다 | 발급 문서의 해시를 이력에 남긴다 | `policy_document.content_hash` / `file_hash` | `DocumentVerificationService` |
| 재발급이 "같은 문서"임을 증명할 수 있어야 한다 | 서명 전 바이트 해시로 멱등성 판단 | `ContentHash` + 결정적 렌더 | `IssuanceIdempotencyTest` |
| 과거 발급분을 **당시 양식**으로 재현할 수 있어야 한다 | 템플릿 버전을 이력에 고정, 버전 디렉터리 불변 | `TemplateVersion`, `templates/policy/v1/` | 설계 결정 ④ |
| 실패한 교부도 남아야 한다 | 실패 기록을 `REQUIRES_NEW` 로 분리 커밋 | `JpaIssuanceHistoryAdapter.recordFailure` | `routesFailuresToDeadLetterQueue...` |

### 개인정보 보호

| 요건 해석 | 아키텍처 결정 | 구현 | 검증 |
|---|---|---|---|
| 주민등록번호는 **처리 근거가 있는 최소 범위**로만 다뤄야 한다 (개인정보보호법 §24-2) | 증권 본문에는 마스킹된 값만 | `DefaultMaskingPolicy` | `MaskingPolicyTest` |
| 마스킹이 **표시**가 아니라 **데이터**에서 이뤄져야 한다 | 마스킹을 렌더 이전 도메인 단계로 | `PolicyViewAssembler` (파이프라인 [1]) | `doesNotLeakPersonalDataIntoTextLayer` — PDF 텍스트 추출 후 원본 문자열 검색 |
| 원본이 흘러 나가는 경로를 통제해야 한다 | 원본 접근을 `RegisteredNo.rawValue()` 한 곳으로 좁히고 toString 은 마스킹 | `RegisteredNo` | `registeredNoNeverPrintsRawValue` |
| 정책에 구멍이 나면 조용히 넘어가면 안 된다 | 렌더 경계에서 "원본처럼 생긴 값"을 거부 | `MaskedValue` → `PersonalDataLeakException` | `MaskingPolicyTest.LeakGuard` |
| 로그·오류 응답으로 2차 유출이 없어야 한다 | 예외 메시지에 문제의 값을 담지 않는다 | `ApiExceptionHandler.handleLeak` | — |

### 대량 발급 운영

| 요건 해석 | 아키텍처 결정 | 구현 | 검증 |
|---|---|---|---|
| 일부 실패가 전체 교부를 막으면 안 된다 | 건 단위 skip + DLQ | `IssuanceSkipListener` | `routesFailuresToDeadLetterQueue...` |
| 무엇이 왜 실패했는지 특정 가능해야 한다 | 실패 단계를 예외 타입에 담아 DLQ 에 기록 | `IssuanceFailedException.failedAfter` | 같은 테스트 (stage 검증) |
| 재실행이 중복 교부를 만들면 안 된다 | contentHash 기반 재사용 | `PolicyIssuanceService.findReusable` | `isIdempotentAcrossRuns` |
| 설정 오류로 전 건이 실패하는데 "성공"으로 끝나면 안 된다 | skip 대상을 발급 실패 예외로 한정 | `PolicyIssuanceJobConfig` | — |

---

## 2. 알고 남긴 한계

PoC 범위 밖이지만 **실제 적용 전에 반드시 메워야 하는 것들**이다. 빠뜨린 것이 아니라 결정이다.

| 항목 | 현재 | 실제 적용 시 필요한 것 |
|---|---|---|
| 서명 인증서 | 자체 서명 테스트 인증서(런타임 생성) | 공인 문서서명 인증서, HSM 또는 키 관리 서비스 |
| 신뢰 체인 검증 | 하지 않음 (자체 서명이라 항상 실패) | 서명자 인증서의 체인·실효(CRL/OCSP) 검증 |
| 장기검증(LTV) | 없음 (B-T 까지) | 서명 시점의 실효 정보를 문서에 내장(DSS). **인증서 만료 후 검증이 성립하려면 필수** |
| 주민번호 저장 | 평문 컬럼 (합성 데이터) | 컬럼 암호화 + 키 분리 보관 |
| 접근통제 | 없음 — 증권번호만 알면 내려받을 수 있다 | 본인확인, 발급·열람 감사로그 |
| 보관소 | 로컬 파일시스템 | WORM 스토리지 또는 객체 잠금, 이중화 |
| 정합성 감사 | 없음 | 보관 파일 ↔ 이력 대사, fileHash 전수 재검증 배치 |
| 보존기간 관리 | 없음 | 법정 보존기간 경과 후 파기 및 파기 증적 |

---

## 3. 요건이 구조를 바꾼 지점

표로는 드러나지 않지만, 이 세 가지는 **요건 때문에 코드 구조가 달라진** 경우다.

1. **마스킹이 도메인에 있는 이유** — 성능도 재사용도 아니다. 렌더 레이어가 원본에 접근할 수 있으면
   템플릿 한 줄로 유출이 일어나고, 그것을 코드 리뷰로 막는 것은 지속 가능하지 않다. 타입으로 막는다.

2. **해시가 두 개인 이유** — 멱등성(교부 증적)과 무결성(보관)이 서로 다른 요건이기 때문이다. 하나로
   합치면 둘 중 하나는 반드시 틀린다.

3. **파이프라인 순서가 고정된 이유** — 마스킹은 렌더 전, PDF/A 는 서명 전, 해시는 서명 전. 셋 다
   물리적으로 되돌릴 수 없다. 그래서 단계를 포트로 쪼개 놓고도 순서만은 서비스 코드에 하드코딩돼 있다.
