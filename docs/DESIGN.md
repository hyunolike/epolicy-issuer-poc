# epolicy-issuer 설계

> v1.1 · 구현 완료 시점 개정 · 작성 hh.jang

v1.0 은 구현 전 설계안이었다. 이 판은 실제로 만들면서 **확인된 것과 바뀐 것**을 반영한다. 바뀐 부분은 [8장](#8-설계안에서-바뀐-것)에 이유와 함께 모아 두었다.

---

## 1. 이 PoC 가 증명하려는 것

라이브러리 벤치마크가 아니라 **규제 요건이 아키텍처를 결정하는 구조**를 만드는 것이 목적이다.

| 요건 | 아키텍처에 미치는 영향 |
|---|---|
| 전자문서 장기보존 | PDF/A 준수 → 폰트 임베딩 강제, 외부 리소스 참조 금지 |
| 문서 무결성 증명 | PAdES 전자서명 (+ TSA) → 서명 이후 파일 수정 불가 |
| 약관·증권 교부 증적 | 발급 이력 + 해시 저장 → 재발급 멱등성 설계 필요 |
| 개인정보 보호 | 마스킹 위치가 렌더 이전이어야 함 ([3.2 ①](#-마스킹은-1단계-템플릿-진입-전에)) |
| 대량 발급 | 청크 배치 + 스트리밍 → 힙에 PDF 전체를 올리지 않는 구조 |

### 성공 기준과 확인 결과

| 기준 | 확인 | 결과 |
|---|---|---|
| 1만 건 배치가 힙 512MB 안에서 완주 | `benchmark` 프로파일 + `-Xmx512m` | [BENCHMARK.md](BENCHMARK.md) |
| 생성된 PDF 가 veraPDF 검증 통과 (PDF/A-1b) | `PolicyIssuancePipelineTest` | 통과 |
| 서명 검증 통과 + 바이트 1개 변조 시 검증 실패 | `DocumentIntegrityTest` | 통과 |
| 동일 계약 재발급 시 콘텐츠 해시 동일 | `IssuanceIdempotencyTest` | 통과 |

---

## 2. 파이프라인

```
계약 데이터 조회
      ↓
 [1] 마스킹 적용        PolicyViewAssembler        ← 도메인. 렌더 전에 끝낸다
      ↓
 [2] 템플릿 바인딩       ThymeleafTemplateAdapter   → HTML
      ↓
 [3] PDF 렌더           OpenHtmlPdfAdapter
      ↓
 [4] PDF/A-1b 변환      PdfBoxArchiveAdapter       ← 반드시 서명 전
      ↓
 [5] 콘텐츠 해시 계산    ContentHash.of(stream)     ← 멱등성 판단 기준
      ↓
 [6] PAdES 전자서명      PadesSignAdapter (+ TSA)
      ↓
 [7] 보관소 저장 + 발급 이력 기록
```

상태 전이는 `REQUESTED → RENDERED → ARCHIVED → SIGNED → STORED`, 실패 시 `FAILED` 다. 설계안의 5단계에 `ARCHIVED` 를 더한 이유는 [8장](#8-설계안에서-바뀐-것)에 있다.

---

## 3. 설계 결정

이 PoC 의 알맹이다. 각 결정은 코드의 해당 클래스 javadoc 에도 같은 근거가 달려 있다.

### ① 마스킹은 [1]단계, 템플릿 진입 전에

템플릿에 원본 주민번호를 넘기고 CSS 로 가리면 PDF 텍스트 레이어에 원본이 그대로 남는다. 화면으로는 마스킹처럼 보이지만 텍스트를 추출하면 바로 나온다. **눈으로 보는 검수로는 절대 잡히지 않는 종류의 유출이다.**

마스킹은 도메인 객체를 만들 때 끝내고, 템플릿은 마스킹된 값만 받는다. 구조적으로 강제하기 위해 세 겹을 뒀다.

```java
// 1. 원본을 들고 다니는 유일한 타입. toString() 이 이미 마스킹돼 있어 로그로 새지 않는다.
public final class RegisteredNo {
    public String rawValue() { ... }        // 호출부를 grep 으로 전수 감사할 수 있다
    @Override public String toString() { return birthPart() + "-" + genderDigit() + "******"; }
}

// 2. 렌더 레이어가 받는 유일한 문자열 타입. 생성자가 "원본처럼 생긴 값"을 거부한다.
public record MaskedValue(String value) {
    private static final Pattern LOOKS_RAW = Pattern.compile("\\d{6}-\\d{7}|\\d{7,}");
    // 일치하면 PersonalDataLeakException — 렌더 전에 터진다
}

// 3. 템플릿이 받는 뷰. 모든 문자열 필드가 MaskedValue 다.
public record PolicyView(MaskedValue contractNo, MaskedValue policyholderName,
                         MaskedValue policyholderRegisteredNo, ...) {}
```

3번이 핵심이다. `Contract` 는 `PolicyViewAssembler` 밖으로 나가지 않고, 템플릿 작성자가 `${contract.policyholder.registeredNo}` 를 쓸 수 있는 경로가 존재하지 않는다.

2번의 패턴 검사는 정책에 구멍이 났을 때의 **조기 실패 장치**다. 방어가 아니라 폭발이다 — 조용히 넘어가는 것보다 발급이 멈추는 편이 낫다. 실제로 배치 테스트에서 담보 비고란에 8자리 숫자가 섞인 계약이 렌더 전에 걸려 DLQ 로 가는 것을 확인한다.

검증은 생성된 PDF 에서 텍스트를 추출해 원본 문자열이 없는지 보는 것으로 한다(`doesNotLeakPersonalDataIntoTextLayer`).

### ② PDF/A 변환은 서명 이전에

서명은 파일 바이트를 고정한다. 서명 후에 PDF/A 메타데이터를 넣으면 서명이 깨진다. 되돌릴 수 없는 순서 제약이고, 그래서 `PdfArchivePort` 가 파이프라인에서 `DocumentSignPort` 앞에 온다.

구현하며 드러난 것이 두 개 더 있다.

**PDFBox 3 은 기본이 압축 저장이다.** `doc.save(out)` 을 그대로 쓰면 객체 스트림과 상호참조 스트림이 생기는데, 둘 다 PDF 1.5 에서 도입된 구조라 헤더에 1.4 를 써 놔도 PDF/A-1b 검증에서 걸린다. PDFBox 2 에서 3 으로 올라오며 바뀐 기본값이라 2.x 예제를 그대로 옮기면 여기서 조용히 실패한다. `CompressParameters.NO_COMPRESSION` 이 선택이 아니라 필수다.

**서명이 PDF/A 를 깨뜨릴 수 있다.** 증분 저장이라 앞 단계의 구조는 보존되지만, PDFBox 가 만드는 서명 위젯 주석에는 외관 스트림(AP)이 없다. PDF/A-1b 는 Popup/Link 가 아닌 모든 주석에 AP 를 요구하고 Print 플래그를 켜라고 한다. 보이지 않는 서명이라 그릴 것은 없지만 빈 외관이라도 넣어야 한다. 이 처리를 넣은 뒤에야 **서명 완료 파일**이 veraPDF 를 통과했다.

### ③ 해시는 두 개를 따로 관리

- `contentHash` — [5]단계, **서명 전** PDF 의 SHA-256. 재발급 멱등성 판단
- `fileHash` — [7]단계, 최종 서명 파일의 SHA-256. 보관 무결성 검증

서명에는 시각이 들어가서 같은 내용이어도 파일이 매번 달라진다. 멱등성을 `fileHash` 로 판단하면 영원히 "다른 문서"가 되고, 재발급 요청마다 새 파일이 쌓인다.

그런데 `contentHash` 가 기준으로 성립하려면 **렌더가 결정적**이어야 한다. 여기서 걸리는 것이 세 개였다.

| 흔들리는 값 | 해결 |
|---|---|
| PDF 의 CreationDate / ModDate | 벽시계가 아니라 **계약 체결일**에서 끌어온다 (`Contract.issuedOn`) |
| trailer 의 `/ID` 배열 | PDFBox 기본은 저장 시각을 MD5 로 섞는다. `증권번호\|양식버전` 에서 결정적으로 만든다 |
| 날짜 포맷 로케일 | `Locale.KOREA` 를 명시. 기본 로케일에 맡기면 서버마다 다른 문자열이 나온다 |

발급회차(`재발급 2회차`)를 **문서 본문에 넣지 않는 것**도 같은 이유다. 본문에 찍으면 회차마다 바이트가 달라져 contentHash 로 멱등성을 볼 수 없다. 회차는 문서 내용이 아니라 교부 이력의 속성으로 본다. 실무에서 재발급본 표기가 필요하다면 본문이 아니라 교부 안내나 파일명으로 다뤄야 한다 — 이것은 이 설계가 감수한 트레이드오프다.

세 가지를 모두 처리한 뒤, 강제 재발급(`force=true`)으로 파이프라인을 처음부터 다시 돌려도 contentHash 가 같고 fileHash 는 다르다는 것을 테스트로 고정했다.

### ④ 템플릿 버전을 발급 이력에 고정

재발급 시 "발급 당시 양식"으로 렌더해야 동일한 문서가 나온다. `templateVersion` 을 이력에 저장하고, 템플릿 파일은 `templates/policy/v1/` 처럼 버전 디렉터리를 둔다. **기존 버전 디렉터리는 수정하지 않는다** — 수정하는 순간 과거 발급분의 재현성이 깨진다.

이 규칙은 v2 가 생기면서 처음으로 실효를 갖게 됐다. 로고를 넣을 때 v1 을 고치는 대신 `templates/policy/v2/` 를 만들고 기본값을 v2 로 올렸다. v1 을 고쳤다면 이미 발급된 증권 전부의 contentHash 가 그 순간 달라진다 — 문서상 당부가 아니라 실제로 깨지는 값이다. `TemplateVersioningTest` 가 "v2 가 있어도 v1 로 재발급하면 최초와 같은 바이트"를 고정한다.

### ⑤ 폰트는 서브셋 임베딩

PDF/A 는 폰트 전체 임베딩을 요구하지만 실제로 사용된 글리프만 담는 서브셋은 허용된다. 나눔고딕 Regular+Bold 원본은 합쳐 4MB 이고, 통째로 넣으면 1만 건에 40GB 다.

서브셋 시 CIDSet 일관성이 깨지면 veraPDF 에서 걸리므로 여기가 실제 검증 포인트였다. PDFBox 3 의 `PDType0Font` 서브셋 임베딩은 CIDSet 을 제대로 써 주며, 결과 파일은 증권 1장 기준 **76KB** 다(목표 300KB 미만).

폰트 바이트는 어댑터 생성 시 한 번 읽어 힙에 둔다. 렌더러는 요청마다 새 스트림을 요구하는데, 매번 클래스패스에서 2MB TTF 를 읽으면 1만 건에 20GB 를 읽게 된다. 상주 4MB 는 이 트레이드오프에서 싸다.

### ⑥ PDF 버퍼 전략을 포트로 뺐다 (추가)

설계안에 없던 결정이다. 메모리 측정이 "코드를 고쳐서 두 번 돌린다"가 되면 비교가 성립하지 않는다. 같은 인터페이스 뒤에 두 구현을 두고 설정 한 줄로 바꾼다.

```java
public interface PdfArtifactFactory {
    PdfArtifact create(PdfWriter writer) throws IOException;   // memory | temp-file
    String strategyName();
}
```

`PdfArtifact` 는 `AutoCloseable` 이고, 임시파일 구현은 close 시점에 파일을 지운다. 힙 누수를 디스크 누수로 바꿔 놓고 측정만 좋아 보이는 것을 막기 위해, 발급 3회 후 스풀 디렉터리가 비어 있는지 확인하는 테스트를 뒀다.

`PdfArtifact.location()` 이 디스크 경로를 돌려주면 PDFBox 는 `RandomAccessReadBufferedFile` 로 읽어 힙을 거의 쓰지 않는다. PDFBox 3 에서 `MemoryUsageSetting.setupTempFileOnly()` 의 자리는 `IOUtils.createTempFileOnlyStreamCache()` 가 대신한다.

**측정 결과는 예상과 달랐다.** 두 전략의 처리량이 16.7 vs 16.2건/초, 힙 최대가 511 vs 510MB 로 사실상 같았다. 설계 단계에서는 chunk 100 × 500KB = 50MB 상주를 걱정했는데, 실제 문서가 76KB 라 chunk 당 7.6MB 에 그친다. 이 규모에서는 임시파일로 뺄 유인이 없고 파일 I/O 만 붙는다 — **지금 조건에서는 메모리 전략이 맞다.** 측정하지 않았다면 반대로 갔을 결정이고, 포트를 둔 값어치가 여기서 났다.

### ⑦ 이미지는 인라인 SVG, 단 투명도 금지 (추가)

증권에 발행사 로고가 필요해지면서 정한 것이다. 선택지는 셋이었다.

| 방법 | 평가 |
|---|---|
| 순수 CSS/텍스트 | 의존성 0, PDF/A 안전 보장. 형태 있는 로고는 못 만든다 |
| **인라인 SVG** | 벡터로 들어가 확대해도 안 깨지고 파일도 거의 안 는다. Batik 의존성이 붙는다 |
| 래스터 PNG | PDF/A-1b 가 허용은 하지만 해상도에 묶이고 파일이 커진다. 장기보존엔 제일 나쁘다 |

SVG 를 골랐다. `openhtmltopdf-svg-support`(Batik)가 SVG 를 PDF 벡터 연산자로 그리므로 로고가 래스터가 아니라 path 로 들어간다. 측정해 보니 v1(로고 없음) 대비 파일 크기 증가는 1KB 미만이었다.

**투명도는 쓸 수 없다.** PDF/A-1 이 금지한다. 이것을 주석으로 당부하는 대신 테스트로 고정했다 — `fill-opacity="0.45"` 하나를 넣은 SVG 로 렌더하면 veraPDF 가 이렇게 답한다.

```
[6.4] If a ca key is present in an ExtGState object, its value shall be 1.0
[6.4] If a CA key is present in an ExtGState object, its value shall be 1.0
```

`fill-opacity` 가 그대로 `/ca 0.45` 인 ExtGState 가 된다. `PdfBoxArchiveAdapter` 는 페이지의 투명도 그룹만 벗겨낼 뿐 콘텐츠 안의 이 값은 손대지 못하므로, 제약은 **입력 쪽에서** 지켜야 한다. `SvgRenderingTest` 가 불투명 SVG 통과와 투명 SVG 거부를 한 쌍으로 확인한다.

**외부 참조와 스크립트는 차단한다.** `BatikSVGDrawer` 기본 생성자가 `SvgScriptMode.SECURE` + `SvgExternalResourceMode.SECURE` 를 고른다. PDF/A 가 외부 참조를 금지하기도 하지만, SVG 는 스크립트와 외부 엔티티를 실어 나를 수 있는 입력이기도 하다.

**SVG 안에 텍스트를 두지 않았다.** `<text>` 를 쓰면 폰트가 필요해지고, 등록하지 않은 폰트는 시스템 폰트로 대체된다. 서명 단계의 AcroForm `/DR` 에서 이미 한 번 겪은 문제(산출물이 실행 머신에 의존)라 로고는 path 로만 구성했다.

**비용은 공짜가 아니고, 예상한 곳에 있지도 않았다.** 200건 표본에서는 렌더가 42.8ms → 58.6ms 로 늘어난 것만 보였다. 1만 건으로 올리자 진짜 비용이 드러났다.

| | 렌더 | 처리량 | **GC** |
|---|---|---|---|
| 로고 없음(v1) | 28.5ms | 22.0건/초 | **20.5s** |
| 로고 있음(v2) | 39.5ms | 16.7건/초 | **93.4s** |

렌더는 11ms 늘었는데 GC 는 4.5배가 됐다. Batik 이 문서마다 같은 SVG 를 다시 파싱하며 객체를 크게 쏟아내고, 512MB 천장에서 그것이 GC 시간으로 되돌아온다. 총 소요 차이 142초 중 73초가 GC 다.

더 나아가, 이 할당 압력이 **4분할 파티셔닝을 OOM 으로 죽였다.** 로고를 빼면 같은 설정이 완주하고 처리량이 22.0 → 49.5건/초로 뛴다. 장식 하나가 아키텍처 비용을 갖는 지점이고, 자세한 것은 [BENCHMARK.md](BENCHMARK.md) 4장에 있다. 근본 해결은 SVG 를 한 번 렌더해 PDF Form XObject 로 재사용하는 것이다 — [10장](#10-남은-것) 참고.

---

## 4. 프로젝트 구조

```
epolicy-issuer/
├─ docs/{DESIGN,COMPLIANCE,BENCHMARK}.md
├─ src/main/java/com/hyunolike/epolicy/
│  ├─ domain/{contract,masking,document}
│  ├─ application/{port/in,port/out,service}
│  ├─ infrastructure/{render,pdfa,sign,storage,buffer,persistence,config}
│  ├─ batch/     PolicyIssuanceJobConfig, ContractPartitioner, IssuanceSkipListener
│  ├─ api/       IssuanceController, VerificationController, AdminController
│  └─ support/   SyntheticContractSeeder, BenchmarkRunner
└─ src/main/resources/
   ├─ templates/policy/v1/policy.html    양식 버전 디렉터리
   ├─ fonts/NanumGothic-{Regular,Bold}.ttf
   └─ icc/sRGB-v2-micro.icc
```

포트 목록은 README 의 구조 절에 있다. 렌더러를 openhtmltopdf → Playwright 로 바꿔도 `PdfRenderPort` 구현체만 교체하면 되고, PD4ML 비교군을 추가할 때도 같다. 라이브러리 비교가 PoC 목적 중 하나이므로 이 경계가 실제로 값을 한다.

---

## 5. 배치

```
Job: policyIssuanceJob
└─ Step: issuancePartitionStep        (partition-count > 1 일 때만 감싼다)
   └─ Step: issuePolicyStep  (chunk = 100)
      ├─ Reader:    JdbcPagingItemReader<ContractNo>
      ├─ Processor: 발급 파이프라인 전체
      ├─ Writer:    청크 집계 (신규/재사용 건수, 바이트)
      └─ SkipPolicy: IssuanceFailedException 만 건너뛰고 DLQ 적재
```

**Skip 대상을 `IssuanceFailedException` 으로 한정한 것**이 중요하다. 모든 예외를 건너뛰게 하면 설정 오류나 DB 단절로 1만 건을 전부 DLQ 에 넣고도 Job 은 "성공"으로 끝난다.

**스텝 트랜잭션 밖에서 파일이 만들어진다.** 청크가 롤백되면 DB 행은 사라지지만 보관소의 PDF 는 남는다. 파일 쓰기를 트랜잭션에 묶는 것은 분산 트랜잭션 없이는 불가능하고 PoC 범위에서 XA 를 끌어올 이유가 없다. 고아 파일은 다음 발급이 같은 경로에 덮어쓰거나 정리 배치가 지우는 것으로 다룬다 — **알고 남긴 구멍**이다.

파티셔닝은 `MOD(증권번호 끝 4자리, 파티션수)` 로 나눈다. SQL 해시 함수는 H2 와 PostgreSQL 이 서로 다른 이름을 쓰는데, 증권번호 끝자리는 채번 순서라 이미 고르게 퍼져 있어 나머지 연산으로 충분하다. 파티션에서 제일 먼저 깨지는 것은 속도가 아니라 **배타성**이라, 4분할 실행 후 모든 문서의 발급회차가 1인지(= 한 계약을 두 워커가 집지 않았는지) 확인한다.

---

## 6. 도메인 모델

```java
record Contract(ContractNo contractNo, ProductCode productCode,
                Party policyholder, Party insured, InsurancePeriod period,
                Money premium, LocalDate issuedOn, List<Coverage> coverages) {}

record PolicyDocument(DocumentId id, ContractNo contractNo,
                      int issueSequence, TemplateVersion templateVersion,
                      ContentHash contentHash,   // 서명 전 — 멱등성 기준
                      ContentHash fileHash,      // 서명 후 — 보관 무결성
                      Instant signedAt, String storagePath, long fileSize,
                      IssuanceStatus status) {}
```

`Contract.issuedOn` 은 표시값이 아니라 **결정성의 기준점**이다([3.2 ③](#-해시는-두-개를-따로-관리)). `DocumentId` 도 랜덤 UUID 가 아니라 `(증권번호, 발급회차)` 에서 결정적으로 파생한다 — 재처리가 중복 행을 만들지 않게 하기 위해서다.

| 테이블 | 용도 |
|---|---|
| `contract` / `contract_coverage` | 합성 계약 데이터 |
| `policy_document` | 발급 문서 메타 |
| `issuance_history` | 상태 전이 이력 (append-only 증적) |
| `issuance_dlq` | 실패 건 + 단계 + 사유, 배치 재처리 대상 |

`issuance_history` 는 갱신하지 않고 쌓기만 한다. 감사 대응에서 필요한 것은 현재 상태가 아니라 **경로**다. 그리고 실패 기록은 `REQUIRES_NEW` 로 쓴다 — 발급 실패로 바깥 트랜잭션이 롤백되면 실패 기록까지 사라져서, DLQ 가 빈 채로 건수만 줄어든다.

---

## 7. 측정

| 지표 | 측정 방법 | 목표 |
|---|---|---|
| 건당 생성 시간 p50 / p95 | Micrometer Timer | p95 < 500ms |
| 1만 건 배치 완주 힙 | `-Xmx512m` 고정 + 0.2초 간격 힙 샘플링 | OOM 없음 |
| 파일 크기 | 보관 파일 평균 | 서브셋 후 < 300KB |
| PDF/A-1b 검증 | veraPDF (테스트 내장) | 통과율 100% |
| 서명 검증 | PDFBox + BouncyCastle | 통과 |
| 변조 탐지 | 서명 파일 바이트 1개 변경 후 재검증 | **검증 실패해야 정상** |
| 재발급 멱등성 | 강제 재발급 후 contentHash 비교 | 동일 |
| SVG 로고 비용 | 같은 조건에서 v1(로고 없음) ↔ v2(로고) 렌더 시간 | 차이를 수치로 남긴다 |

힙은 `Runtime.totalMemory()` 를 끝에 한 번 읽는 방식으로는 GC 직후 값을 보게 되어 항상 낮게 나온다. 0.2초 간격 샘플링 최대치를 쓴다. 정확한 값은 JFR 이 필요하지만, "512MB 안에서 완주하는가" 판단에는 이것으로 충분하다.

결과는 [BENCHMARK.md](BENCHMARK.md).

---

## 8. 설계안에서 바뀐 것

구현하며 판단을 바꾼 지점들이다. 각각 코드 주석에도 같은 설명이 있다.

### 상태에 `ARCHIVED` 를 추가

설계안은 `REQUESTED / RENDERED / SIGNED / STORED / FAILED` 5개였다. PDF/A 변환은 "서명 이전에만 가능한" 되돌릴 수 없는 단계라, 렌더 성공과 PDF/A 변환 성공을 한 상태로 뭉치면 실패 원인 분석에서 이 둘을 가를 수 없다.

### 테스트 인증서를 레포에 넣지 않고 런타임에 생성

설계안은 `resources/certs/test-signer.p12` 를 커밋하는 그림이었다. 그러면 개인키가 저장소에 들어간다. 시크릿 스캐너가 걸고, "테스트용"이라는 맥락은 클론된 뒤 사라진다. `SelfSignedSignerFactory` 가 첫 기동 시 `build/certs/` 에 만들어 쓰고, 운영 프로파일에서는 `generate-if-absent: false` 로 두고 실제 키스토어를 주입한다. **커밋할 비밀이 애초에 없다.**

### TSA 기본값을 꺼짐으로

설계안은 공개 TSA(FreeTSA)를 전제했다. 기본으로 켜 두면 발급 전체가 남의 서버 가용성에 묶이고, 망 분리 환경이나 CI 에서 통째로 멈춘다. 기본은 PAdES-B-B 이고 `epolicy.sign.tsa.enabled=true` 로 B-T 를 만든다. 타임스탬프가 더해 주는 것은 변조 탐지가 아니라 "언제" 서명했는지에 대한 제3자 보증이다 — 이 PoC 의 성공 기준은 전자로, 없어도 성립한다.

### Batch Writer 의 책임

설계안은 Processor 가 "마스킹 → 렌더 → PDF/A → 해시 → 서명"까지 하고 Writer 가 "저장 + 이력"을 맡는 그림이었다. 그렇게 나누려면 유스케이스를 둘로 쪼개야 하고, 그 순간 API 경로와 배치 경로가 서로 다른 코드로 갈라진다. 멱등성·마스킹·순서 제약 같은 규제 요건이 두 벌 존재하게 되는 것이라 한쪽만 고쳐지는 사고가 시간 문제다.

같은 파이프라인을 쓰고 Writer 는 청크 단위 집계(신규/재사용 건수)를 맡는 쪽으로 바꿨다. 재사용 건수를 세는 것이 실제로 쓸모 있다 — 배치를 두 번 돌렸을 때 이 값이 전체 건수와 같아야 멱등성이 동작한 것이고, 0 이면 매번 새 파일을 찍어 내고 있다는 뜻이다.

### DLQ 적재를 이력 기록에서 분리

`recordFailure`(증적)와 `enqueueDeadLetter`(배치 재처리 큐)를 나눴다. 증적은 API 경로든 배치 경로든 남아야 하지만, DLQ 는 "나중에 배치가 다시 집어갈 대상"이라는 운영상의 의미를 가진다. API 단건 발급 실패는 호출자가 바로 재시도하면 되고 큐에 쌓을 대상이 아니다. 둘을 묶으면 DLQ 가 재처리할 필요 없는 건으로 채워진다.

### veraPDF 를 CLI 대신 라이브러리로

설계안은 veraPDF CLI 를 별도로 돌리는 그림이었다. `org.verapdf:validation-model` 을 테스트 의존성으로 넣으면 같은 검증 엔진이 JUnit 안에서 돈다. 설치할 것이 없어지고, PDF/A 준수가 사람이 가끔 돌려 보는 확인이 아니라 **회귀 테스트**가 된다.

함정이 하나 있었다. veraPDF 는 검증 프로파일(`PDFA-1B.xml`)을 JAXB 로 읽는데, Spring Boot BOM 이 `jaxb-impl` 을 4.x 로 올리면 4.x 는 `jakarta.xml.bind` 를, veraPDF 는 `javax.xml.bind` 를 바라보게 되어 프로파일 로딩이 조용히 실패한다. 예외 대신 `PDFAFlavour 1b is not supported by this directory` 가 뜨고 원인이 전혀 드러나지 않는다. 테스트 클래스패스에서만 2.3.x 로 고정했다(`build.gradle`).

### ICC 프로파일

PDFBox 3.0.7 에 번들된 ICC 는 CMYK(`CGATS001Compat-v2-micro.icc`)뿐이라 RGB 문서에 쓸 수 없다. Compact ICC Profiles 의 sRGB v2(CC0, **456바이트**)를 쓴다. 일반 sRGB 프로파일은 3~60KB 이고 1만 건이면 그대로 곱해진다.

### 테스트 설정이 운영 설정을 가리고 있었다

`src/test/resources/application.yml` 은 main 의 같은 이름 파일을 **덮어쓰는 것이 아니라 클래스패스에서 가려 버린다.** 그래서 통합 테스트는 운영 설정이 아니라 `@ConfigurationProperties` 의 자바 기본값을 검증하고 있었다. 둘이 같은 동안에는 아무 증상이 없다가, 기본 양식을 v2 로 올리면서 드러났다 — application.yml 은 v2 인데 테스트는 v1 을 보고 있었다.

`application-test.yml` + `@ActiveProfiles("test")` 로 바꿔 main 설정 위에 얹히게 했다. 자바 기본값도 v2 로 맞춰 두었고, 어긋나면 `TemplateVersioningTest` 가 잡는다.

### `ContractLoadPort` 추가

설계안의 포트 목록에 없었지만 파이프라인 [0]단계가 필요로 한다. 실제 시스템이라면 계약 원장은 다른 시스템이고, 여기가 그 경계다.

### 증권번호 형식

`AB-2026-00000001` 에서 `KB-2026-0001-0042` 로 바꿨다. `MaskedValue` 가 7자리 이상 연속 숫자를 "마스킹 안 된 값"으로 보고 거부하기 때문에, 자기 증권번호에 스스로 걸린다. 네 자리씩 끊으면 해결되고, 파티셔닝 키로도 그대로 쓸 수 있다.

---

## 9. 마일스톤

| 단계 | 내용 | 상태 |
|---|---|---|
| M1 | 도메인 모델 + 합성 데이터 시더 | 완료 — 검증번호가 일부러 틀린 주민번호 생성 |
| M2 | 템플릿 + 단건 PDF 렌더 | 완료 — 한글 정상, 1페이지 |
| M3 | 마스킹 + PDF/A-1b 변환 | 완료 — veraPDF 통과 |
| M4 | 전자서명 + TSA | 완료 — B-B 기본, B-T 옵션, 변조 탐지 확인 |
| M5 | Spring Batch | 완료 — DLQ 동작, 재실행 멱등 |
| M6 | 파티셔닝 + 측정 문서화 | 완료 — 4분할 배타성 확인. 1만 건에서는 OOM 이 나서 원인까지 분리했다 ([BENCHMARK.md](BENCHMARK.md)) |

---

## 10. 남은 것

PoC 범위 밖이지만, 실제로 만든다면 다음이 먼저다.

- **LTV(장기검증)** — 서명 시점의 CRL/OCSP 를 문서에 박아 넣어야 인증서 만료 후에도 검증이 성립한다. 지금은 B-T 까지다
- **보관 파일과 이력의 정합성 배치** — 고아 파일 정리, fileHash 전수 재검증
- **재발급 표기** — contentHash 멱등성과 양립하는 방법(교부 안내문 분리 등)
- **로고 SVG 를 문서마다 다시 파싱한다** — 1만 건이면 같은 로고를 1만 번 파싱한다(문서당 약 16ms, 합계 160초). 한 번 렌더해 PDF Form XObject 로 만들어 두고 재사용하면 사라지는 비용인데, openhtmltopdf 가 그 훅을 열어 두지 않아 렌더러 밖에서 후처리해야 한다
- **PD4ML 비교군** — `PdfRenderPort` 에 어댑터 하나를 더 붙이면 "레거시 상용 라이브러리 vs 오픈소스" 비교표가 나온다
