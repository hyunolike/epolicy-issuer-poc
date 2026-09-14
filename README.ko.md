<div align="center">

# epolicy-issuer

**전자보험증권 발급 PoC — 라이브러리가 아니라 규제 요건이 아키텍처를 결정하는 구조.**

[![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring Batch](https://img.shields.io/badge/Spring%20Batch-5-6DB33F?logo=spring&logoColor=white)](https://spring.io/projects/spring-batch)
[![PDFBox](https://img.shields.io/badge/Apache%20PDFBox-3.0-D22128?logo=apache&logoColor=white)](https://pdfbox.apache.org/)
[![PDF/A-1b](https://img.shields.io/badge/PDF%2FA--1b-veraPDF%20verified-0A7BBB)](https://verapdf.org/)
[![PAdES](https://img.shields.io/badge/Signature-PAdES%20B--B%20%2F%20B--T-4B32C3)](https://www.etsi.org/)

[English](README.md) · **한국어**

</div>

---

## 목차

- [무엇을 만든 것인가](#무엇을-만든-것인가)
- [무엇이 나오는가](#무엇이-나오는가)
- [파이프라인](#파이프라인)
- [빠른 시작](#빠른-시작)
- [이 PoC 가 증명하는 것](#이-poc-가-증명하는-것)
- [전체를 좌우하는 세 가지 결정](#전체를-좌우하는-세-가지-결정)
- [프로젝트 구조](#프로젝트-구조)
- [기술 스택](#기술-스택)
- [스코프 아웃](#스코프-아웃)
- [문서](#문서)

---

## 무엇을 만든 것인가

라이브러리 벤치마크가 아니다. **규제 요건이 파이프라인의 순서와 경계를 어떻게 강제하는지**를
보여주는 것이 목적이다.

장기보존(PDF/A), 무결성 증명(전자서명), 교부 증적(발급 이력), 개인정보 마스킹, 대량 발급 —
다섯 가지 요건이 각각 나중에는 되돌릴 수 없는 설계 지점을 하나씩 못 박는다. 이 저장소의 내용이
그것이다.

| 요건 | 아키텍처에 미치는 영향 |
|---|---|
| 전자문서 장기보존 | PDF/A 준수 → 폰트 임베딩 강제, 외부 리소스 참조 금지 |
| 문서 무결성 증명 | PAdES 전자서명 (+ TSA) → 서명 이후 파일 수정 불가 |
| 약관·증권 교부 증적 | 발급 이력 + 해시 저장 → 재발급 멱등성 규칙이 필요해진다 |
| 개인정보 보호 | 마스킹이 템플릿이 아니라 **렌더 이전**에 끝나야 한다 |
| 대량 발급 | 청크 배치 + 스트리밍 → 힙에 PDF 전체를 올리지 않는 구조 |

## 무엇이 나오는가

<div align="center">
  <img src="docs/images/policy-sample.png" width="560" alt="실제 발급 PDF 를 렌더한 전자보험증권">
</div>

프론트엔드는 없다. 사용자에게 가는 산출물이 **PDF 그 자체**라서, 이 이미지가 화면에 가장 가깝다.
그리고 이 한 장에 저장소의 주장이 거의 다 들어 있다.

- **마스킹이 표시가 아니라 데이터에 있다.** `류*현`, `880324-1******`, `010-****-1694`,
  `대구광역시 동구 ***` — 텍스트 레이어를 추출해도 정확히 저대로 나온다. 덮어 가린 것이 아니다.
- **담보가 가장 많은 계약도 한 장이다.** 페이지 수는 1만 건에서 그대로 렌더 시간과 파일 크기에
  곱해진다.
- **외부 리소스가 없다.** 폰트는 서브셋 임베딩, 레이아웃은 순수 CSS, 이미지는 없다 — 전부
  PDF/A-1b 가 강제한 것이다.

데이터는 합성이다. 이미지를 다시 만들려면 문서를 한 건 발급한 뒤 PDFBox 의 `PDFRenderer` 로
1페이지를 130 DPI 로 렌더하면 된다.

## 파이프라인

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

순서를 바꿀 수 없는 지점이 세 군데다. 근거는
[docs/DESIGN.md 3장](docs/DESIGN.md)에 있다.

## 빠른 시작

```bash
./gradlew test      # 전체 검증 (veraPDF PDF/A 검증 포함, 네트워크 불필요)
./gradlew bootRun   # H2 인메모리로 기동, http://localhost:8080
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

[docs/BENCHMARK.md](docs/BENCHMARK.md)에 그대로 붙일 수 있는 마크다운 표가 찍힌다.

## 이 PoC 가 증명하는 것

| 성공 기준 | 확인 방법 | 결과 |
|---|---|---|
| 1만 건 배치가 힙 512MB 안에서 완주 | `benchmark` 프로파일 + `-Xmx512m` | [BENCHMARK.md](docs/BENCHMARK.md) |
| 생성 PDF 가 PDF/A-1b 검증 통과 | `PolicyIssuancePipelineTest.isPdfA1bCompliant` (veraPDF) | 통과 |
| 서명 검증 통과 + **1바이트 변조 시 검증 실패** | `DocumentIntegrityTest` | 통과 |
| 동일 계약 재발급 시 contentHash 동일 | `IssuanceIdempotencyTest` | 통과 |
| PDF 텍스트 레이어에 원본 개인정보 없음 | `PolicyIssuancePipelineTest.doesNotLeakPersonalDataIntoTextLayer` | 통과 |

veraPDF 는 CLI 가 아니라 **라이브러리**(`org.verapdf:validation-model`)로 JUnit 안에서 돌린다.
따로 설치할 것이 없고, PDF/A 준수가 사람이 가끔 돌려 보는 확인이 아니라 **회귀 테스트**가 된다.
타임스탬프도 같은 방식으로,
[프로세스 안에 띄운 RFC 3161 TSA](src/test/java/com/hyunolike/epolicy/support/EmbeddedTsaServer.java)가
네트워크 없이 PAdES-B-T 경로를 통과시킨다.

## 전체를 좌우하는 세 가지 결정

**마스킹은 템플릿이 아니라 도메인에 있다.** 템플릿에 원본 주민번호를 넘기고 CSS 로 가리면 PDF
텍스트 레이어에 원본이 그대로 남는다. 텍스트를 추출하면 바로 나오고, 눈으로 보는 검수로는 절대
잡히지 않는다. 그래서 렌더 레이어는 `PolicyView` 만 받고, 그 모든 필드는 `MaskedValue` 이며,
`MaskedValue` 는 아직 **마스킹 안 된 것처럼 생긴 값**을 거부한다. 템플릿에서 원본으로 가는
코드 경로가 존재하지 않는다.

**PDF/A 변환은 서명 앞에 온다.** 서명은 바이트를 고정하므로, 그 뒤에 메타데이터를 넣으면 서명이
깨진다. 취향이 아니라 물리적 제약이다.

**해시는 의도적으로 두 개다.** `contentHash`(서명 전)는 "같은 문서인가"에, `fileHash`(서명 후)는
"보관된 파일이 손대어졌는가"에 답한다. 서명에는 시각이 들어가 같은 내용이어도 파일이 매번
달라지므로, 멱등성을 `fileHash` 로 판단하면 재발급이 영원히 새 문서로 보인다.

`contentHash` 가 기준으로 성립하려면 렌더가 결정적이어야 했다. 문서 시각을 벽시계가 아니라 계약
체결일에서 끌어오고, PDF `/ID` 를 증권번호에서 파생시키고, 날짜 로케일을 고정했다. 전체 근거와
구현하며 바뀐 것은 [docs/DESIGN.md](docs/DESIGN.md)에 있다.

## 프로젝트 구조

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

포트를 잘게 쪼갠 이유는 라이브러리 비교가 PoC 목적 중 하나이기 때문이다. 렌더러를 Playwright 나
PD4ML 로 바꾸는 변경은 `IssuanceConfiguration` 한 줄이고, 파이프라인 코드는 손대지 않는다.

## 기술 스택

| 영역 | 선택 | 비고 |
|---|---|---|
| 런타임 | Java 17 타깃, Spring Boot 3.3 | 빌드는 JDK 17+ |
| 배치 | Spring Batch 5 | 청크 + 파티셔닝 |
| 템플릿 | Thymeleaf | 버전 디렉터리(`templates/policy/v1/`) |
| PDF 렌더 | `io.github.openhtmltopdf` 1.1.85 | PDFBox 3 기반 포크. `danfickle` 원본 **아님** |
| PDF 조작 | Apache PDFBox 3.0.7 | PDF/A 변환, 서명, 검증 |
| 서명 | BouncyCastle 1.78.1 | PAdES-B-B / B-T |
| 타임스탬프 | RFC 3161 (공개 TSA) | 기본 비활성 |
| DB | H2 (로컬) / PostgreSQL (docker-compose) | |
| PDF/A 검증 | veraPDF 1.28.2 (라이브러리) | 테스트에 내장 |

`openhtmltopdf` 는 **LGPL-2.1** 이다. 라이브러리로 링크해 쓰는 이 구성에서는 문제가 없지만, 사내
배포 정책에 따라 검토가 필요할 수 있다. 상용 비교군(PD4ML)을 붙일 자리를 `PdfRenderPort` 로 열어
둔 것도 이 맥락이다.

폰트는 나눔고딕(SIL Open Font License 1.1, `src/main/resources/fonts/OFL.txt`),
ICC 프로파일은 Compact ICC Profiles(CC0)를 쓴다.

## 스코프 아웃

PoC 이므로 아래는 **하지 않기로 결정**한 것이다. 빠뜨린 것과 구분하기 위해 명시한다.

- **인증/인가** — 발급 API 는 인증 없이 노출된다. 증권번호만 알면 남의 증권을 받을 수 있다
- **실제 공인인증서 연동** — 자체 서명 테스트 인증서를 런타임에 생성해 쓴다. 신뢰 체인 검증은
  하지 않는다
- **약관 본문 전체** — 증권 요약 1페이지만
- **모바일 전자서명 UI** — 서버 사이드 서명만
- **주민번호 컬럼 암호화** — 합성 데이터라서 넘어간 것이다. 실 데이터라면 필수다
- **실 개인정보** — **절대 사용 금지.** 합성 데이터만 쓰며, 생성되는 주민등록번호는 검증번호가
  일부러 틀리도록 만들어져 실존 번호와 겹칠 수 없다

## 문서

- [docs/DESIGN.md](docs/DESIGN.md) — 설계 결정과 그 근거, 구현하며 바뀐 것
- [docs/COMPLIANCE.md](docs/COMPLIANCE.md) — 규제 요건 ↔ 구현 ↔ 검증 매핑표, 그리고 알고 남긴 한계
- [docs/BENCHMARK.md](docs/BENCHMARK.md) — 측정 결과
