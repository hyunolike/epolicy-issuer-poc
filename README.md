# epolicy-issuer

전자보험증권(electronic policy) 발급 PoC. **규제 요건이 아키텍처를 어떻게 결정하는가**를 코드로 확인하는 것이 목적이다.

라이브러리 벤치마크가 아니다. 장기보존(PDF/A), 무결성 증명(전자서명), 교부 증적(발급 이력), 개인정보 마스킹, 대량 발급 — 다섯 가지 요건이 각각 파이프라인의 **순서와 경계를 어떻게 강제하는지**가 이 저장소의 내용이다.

```
계약 데이터 조회
      ↓
 [1] 마스킹 적용        ← 도메인 레벨. 렌더 전에 끝낸다
      ↓
 [2] 템플릿 바인딩       Thymeleaf → HTML
      ↓
 [3] PDF 렌더           openhtmltopdf
      ↓
 [4] PDF/A-1b 변환      PDFBox + ICC + XMP     ← 반드시 서명 전
      ↓
 [5] 콘텐츠 해시 계산    SHA-256                ← 멱등성 판단 기준
      ↓
 [6] PAdES 전자서명      BouncyCastle (+ TSA)
      ↓
 [7] 보관소 저장 + 발급 이력 기록
```

순서를 바꿀 수 없는 지점이 세 군데다. 그 이유가 [docs/DESIGN.md](docs/DESIGN.md) 3.2 에 있다.

---

## 빠른 시작

```bash
./gradlew test            # 전체 검증 (veraPDF PDF/A 검증 포함, 네트워크 불필요)
./gradlew bootRun         # H2 인메모리로 기동, http://localhost:8080
```

```bash
# 합성 계약 100건 생성 → 배치 발급 → 검증
curl -XPOST 'localhost:8080/api/admin/seed?count=100'
curl -XPOST 'localhost:8080/api/admin/batch/run'
curl 'localhost:8080/api/verification/policies/KB-2026-0001-0000' | jq

# 단건 발급 + 증권 내려받기
curl -XPOST 'localhost:8080/api/policies/KB-2026-0001-0000/issue' | jq
curl -o policy.pdf 'localhost:8080/api/policies/KB-2026-0001-0000/document'
```

PostgreSQL 로 돌리려면:

```bash
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=postgres'
```

측정:

```bash
./gradlew bootJar
java -Xmx512m -jar build/libs/epolicy-issuer-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=benchmark --count=10000 \
  --spring.main.web-application-type=none
```

마크다운 표가 그대로 찍힌다. 결과는 [docs/BENCHMARK.md](docs/BENCHMARK.md) 참고.

---

## 이 PoC 가 증명하는 것

| 성공 기준 | 확인 방법 | 결과 |
|---|---|---|
| 1만 건 배치가 힙 512MB 안에서 완주 | `--spring.profiles.active=benchmark` + `-Xmx512m` | [BENCHMARK.md](docs/BENCHMARK.md) |
| 생성 PDF 가 PDF/A-1b 검증 통과 | `PolicyIssuancePipelineTest.isPdfA1bCompliant` (veraPDF) | 통과 |
| 서명 검증 통과 + **1바이트 변조 시 검증 실패** | `DocumentIntegrityTest` | 통과 |
| 동일 계약 재발급 시 contentHash 동일 | `IssuanceIdempotencyTest` | 통과 |
| PDF 텍스트 레이어에 원본 개인정보 없음 | `PolicyIssuancePipelineTest.doesNotLeakPersonalDataIntoTextLayer` | 통과 |

veraPDF 는 CLI 가 아니라 `org.verapdf:validation-model` 라이브러리를 테스트 의존성으로 넣어 JUnit 안에서 돌린다. 따로 설치할 것이 없고, PDF/A 준수가 **회귀 테스트**가 된다.

---

## 구조

`application` 이 포트를 정의하고 `infrastructure` 가 어댑터를 붙이는 헥사고날 라이트 구조다.

```
src/main/java/com/hyunolike/epolicy/
├─ domain/
│  ├─ contract/     Contract, Party, RegisteredNo, Money, Coverage
│  ├─ masking/      MaskingPolicy, MaskedValue          ← 마스킹은 도메인에 있다
│  └─ document/     PolicyView, PolicyDocument, ContentHash, PdfArtifact
├─ application/
│  ├─ port/in/      IssuePolicyUseCase, VerifyDocumentUseCase
│  ├─ port/out/     TemplateRenderPort, PdfRenderPort, PdfArchivePort,
│  │                DocumentSignPort, TimestampPort, DocumentStoragePort,
│  │                IssuanceHistoryPort, ContractLoadPort, PdfArtifactFactory
│  └─ service/      PolicyIssuanceService, DocumentVerificationService
├─ infrastructure/
│  ├─ render/       ThymeleafTemplateAdapter, OpenHtmlPdfAdapter
│  ├─ pdfa/         PdfBoxArchiveAdapter
│  ├─ sign/         PadesSignAdapter, TsaClientAdapter, 서명 검증
│  ├─ storage/      LocalFsStorageAdapter
│  ├─ buffer/       InMemory / TempFile PdfArtifactFactory   ← 메모리 전략 실험
│  ├─ persistence/  JPA 엔티티 + 어댑터
│  └─ config/       포트 ↔ 어댑터 결선
├─ batch/           Spring Batch 잡, 파티셔너, SkipListener
├─ api/             발급 / 검증 / 운영 컨트롤러
└─ support/         합성 데이터 시더, 측정 러너
```

포트를 잘게 쪼갠 이유는 라이브러리 비교가 PoC 목적 중 하나이기 때문이다. 렌더러를 Playwright 나 PD4ML 로 바꾸는 변경은 `IssuanceConfiguration` 한 줄이고, 파이프라인 코드는 손대지 않는다.

---

## 기술 스택

| 영역 | 선택 | 비고 |
|---|---|---|
| 런타임 | Java 17 타깃, Spring Boot 3.3 | 빌드는 JDK 17+ |
| 배치 | Spring Batch 5 | 청크 + 파티셔닝 |
| 템플릿 | Thymeleaf | 버전 디렉터리(`templates/policy/v1/`) |
| PDF 렌더 | `io.github.openhtmltopdf` 1.1.85 | PDFBox 3 기반 포크. `danfickle` 원본 아님 |
| PDF 조작 | Apache PDFBox 3.0.7 | PDF/A 변환, 서명, 검증 |
| 서명 | BouncyCastle 1.78.1 | PAdES-B-B / B-T |
| 타임스탬프 | RFC 3161 (공개 TSA) | 기본 비활성 |
| DB | H2 (로컬) / PostgreSQL (docker-compose) | |
| PDF/A 검증 | veraPDF 1.28.2 (라이브러리) | 테스트에 내장 |

`openhtmltopdf` 는 **LGPL-2.1** 이다. 라이브러리로 링크해 쓰는 이 구성에서는 문제가 없지만, 사내 배포 정책에 따라 검토가 필요할 수 있다. 상용 비교군(PD4ML)을 붙일 자리를 `PdfRenderPort` 로 열어 둔 것도 이 맥락이다.

폰트는 나눔고딕(SIL Open Font License 1.1, `src/main/resources/fonts/OFL.txt`),
ICC 프로파일은 Compact ICC Profiles(CC0)를 쓴다.

---

## 스코프 아웃

PoC 이므로 아래는 **하지 않기로 결정**한 것이다. 빠뜨린 것과 구분하기 위해 명시한다.

- **인증/인가** — 발급 API 는 인증 없이 노출된다. 증권번호만 알면 남의 증권을 받을 수 있다
- **실제 공인인증서 연동** — 자체 서명 테스트 인증서를 런타임에 생성해 쓴다. 신뢰 체인 검증은 하지 않는다
- **약관 본문 전체** — 증권 요약 1페이지만
- **모바일 전자서명 UI** — 서버 사이드 서명만
- **주민번호 컬럼 암호화** — 합성 데이터 전제. 실 데이터라면 필수다
- **실 개인정보** — **절대 사용 금지.** 합성 데이터만 쓰며, 생성되는 주민등록번호는 검증번호가 일부러 틀리도록 만들어져 실존 번호와 겹칠 수 없다

## 문서

- [docs/DESIGN.md](docs/DESIGN.md) — 설계 결정과 그 근거, 구현하며 바뀐 것
- [docs/COMPLIANCE.md](docs/COMPLIANCE.md) — 규제 요건 ↔ 구현 ↔ 검증 매핑표
- [docs/BENCHMARK.md](docs/BENCHMARK.md) — 측정 결과
