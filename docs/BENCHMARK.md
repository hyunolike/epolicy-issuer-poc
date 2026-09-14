# 측정 결과

1만 건 × 6가지 구성을 `-Xmx512m` 고정으로 실행한 결과다. **하나는 실패했고, 그 실패가 이 문서에서
제일 쓸모 있는 부분이다.**

---

## 1. 측정 환경

| 항목 | 값 |
|---|---|
| CPU | Intel Xeon @ 2.80GHz, 4 vCPU |
| 메모리 | 16GB (JVM 힙은 `-Xmx512m` 로 고정) |
| OS | Ubuntu 24.04 LTS |
| JVM | OpenJDK 21.0.10 (바이트코드 타깃은 17) |
| DB | H2 인메모리 — **같은 힙을 쓴다. 아래 주의 참고** |
| 보관소 | 로컬 파일시스템 |
| 서명 | PAdES-B-B (TSA 비활성) |

> **H2 인메모리가 같은 힙에 산다.** 계약 1만 건 + 발급문서 1만 건 + 상태전이 이력 2만여 건이
> 512MB 안에 PDF 처리와 함께 들어앉는다. 따라서 아래 힙 수치는 "PDF 파이프라인만의 소비"가 아니라
> **애플리케이션 전체**의 소비다. PostgreSQL 로 분리하면 여유가 더 생긴다 — 이 조건이 오히려
> 보수적이라는 뜻이다.

---

## 2. 실행 방법

```bash
./gradlew bootJar

java -Xmx512m -jar build/libs/epolicy-issuer-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=benchmark --count=10000 \
  --epolicy.pdf.buffer-strategy=memory \
  --spring.main.web-application-type=none
```

`benchmark` 프로파일은 합성 계약을 만들고 발급 배치를 한 번 돌린 뒤, 이 문서에 그대로 붙일 수 있는
마크다운 표를 찍고 종료한다 (`support/BenchmarkRunner`).

비교한 구성:

| 구성 | 옵션 |
|---|---|
| A. 메모리 버퍼 (기본) | `--epolicy.pdf.buffer-strategy=memory` |
| B. 임시파일 버퍼 | `--epolicy.pdf.buffer-strategy=temp-file` |
| C. 임시파일 + 4분할 | `B` + `--epolicy.batch.partition-count=4` |
| D. 로고 없는 v1 양식 | `A` + `--epolicy.default-template-version=v1` |
| E. 4분할 + 청크 25 | `C` + `--epolicy.batch.chunk-size=25` |
| F. 4분할 + 로고 없는 v1 | `C` + `--epolicy.default-template-version=v1` |

A·B 는 버퍼 전략 비교, A·D 는 SVG 로고 비용 분리, C·E·F 는 C 가 왜 실패했는지를 가르기 위한
구성이다. D~F 는 C 의 실패를 보고 추가했다.

합성 데이터는 고정 시드(`20260914`)로 만들어져 세 실행이 **같은 1만 건**을 처리한다. 시드를 고정하지
않으면 담보 개수와 이름 길이가 달라져 파일 크기와 렌더 시간이 흔들리고, 비교가 성립하지 않는다.

---

## 3. 측정 방법에 대한 주석

**힙은 0.2초 간격 샘플링의 최대치다.** `Runtime.totalMemory()` 를 끝에 한 번 읽으면 GC 가 방금
쓸어간 뒤의 값을 보게 되어 항상 낮게 나온다. 정확한 값은 JFR 이 필요하지만, "512MB 안에서
완주하는가" 판단에는 샘플링 최대치로 충분하다.

**건당 시간은 Micrometer Timer 의 히스토그램 스냅샷이다.** 평균이 아니라 p50/p95/p99 를 본다.
평균은 GC 정지나 파일시스템 지연 같은 꼬리를 감춘다.

**단계별 시간은 합계가 총 소요와 맞지 않는다.** 배치 오버헤드(청크 커밋, 리더 페이징, 이력 기록)가
단계 밖에 있기 때문이다. 단계별 수치는 "어디에 시간이 쏠리는가"를 보기 위한 것이지 총합의 분해가
아니다.

---

## 4. 결과

| 구성 | 결과 | 처리량 | p50 | p95 | 힙 최대 | GC |
|---|---|---|---|---|---|---|
| A. 메모리 버퍼 | ✅ 10,000건 / 597s | 16.7건/초 | 47ms | 112ms | 511MB | 93.4s |
| B. 임시파일 | ✅ 10,000건 / 617s | 16.2건/초 | 47ms | 116ms | 510MB | 96.0s |
| C. 임시파일 + 4분할 | ❌ **OOM, 7,900건** | — | 70ms | 284ms | 510MB | 132.7s |
| D. 로고 없는 v1 | ✅ 10,000건 / 455s | 22.0건/초 | 35ms | 79ms | 510MB | **20.5s** |
| E. 4분할 + 청크 25 | ✅ 10,000건 / 379s | 26.4건/초 | 87ms | 318ms | 509MB | 182.9s |
| F. 4분할 + 로고 없는 v1 | ✅ 10,000건 / 202s | **49.5건/초** | 58ms | 209ms | 510MB | 43.3s |

단계별 평균(ms):

| 구성 | template | render | pdfa | sign | store | 평균 파일 |
|---|---|---|---|---|---|---|
| A | 1.4 | 39.5 | 4.7 | 5.4 | 1.4 | 76KB |
| B | 1.5 | 40.4 | 5.5 | 5.9 | 0.8 | 76KB |
| D | 1.1 | **28.5** | 4.1 | 4.7 | 1.2 | 76KB |
| E | 3.5 | 104.2 | 12.6 | 12.9 | 3.1 | 76KB |
| F | 1.9 | 52.3 | 7.1 | 8.2 | 1.5 | 76KB |

### 성공 기준 대비

| 기준 | 목표 | 결과 |
|---|---|---|
| 1만 건이 힙 512MB 안에서 완주 | OOM 없음 | **기본 구성(A) 통과.** 4분할(C)은 실패 — 아래 참고 |
| 건당 생성 시간 p95 | < 500ms | 112ms (A) |
| 파일 크기 (서브셋 후) | < 300KB | 76KB |
| PDF/A-1b 검증 | 통과율 100% | 통과 ([5장](#5-부수-검증)) |
| 재발급 멱등성 | contentHash 동일 | 동일 |

---

### 읽을 것 ① 버퍼 전략은 이 규모에서 차이가 없었다

A(메모리) 16.7건/초 vs B(임시파일) 16.2건/초, 힙 최대 511MB vs 510MB. **설계 단계의 예상이 빗나간
부분이다.** chunk 100 × 500KB = 50MB 가 힙에 상주할 것으로 봤는데, 실제 문서가 76KB 라 chunk 당
7.6MB 에 그친다. 이 정도는 512MB 안에서 임시파일로 뺄 유인이 되지 못하고, 대신 파일 I/O 가
그만큼 붙어 근소하게 느려졌다.

임시파일 전략이 값을 하려면 문서가 훨씬 크거나(다페이지 약관 전문) 청크가 훨씬 커야 한다.
**지금 조건에서는 메모리 전략이 맞다** — 측정하지 않았다면 반대로 갔을 결정이다.

### 읽을 것 ② 로고 SVG 하나의 비용은 CPU 가 아니라 GC 였다

A(로고 있음) vs D(로고 없음):

| | 렌더 | 처리량 | **GC** |
|---|---|---|---|
| D. 로고 없음 | 28.5ms | 22.0건/초 | **20.5s** |
| A. 로고 있음 | 39.5ms | 16.7건/초 | **93.4s** |

렌더는 11ms 늘었는데 GC 는 **4.5배**가 됐다. Batik 이 문서마다 SVG 를 다시 파싱하면서 객체를
크게 쏟아내고, 512MB 천장에서 그것이 GC 시간으로 되돌아온다. 총 소요 차이 142초 중 73초가 GC 다.

200건 표본으로 "렌더가 16ms 늘었다"고만 봤을 때는 이 절반을 놓치고 있었다. **짧은 표본은 할당
압력을 드러내지 못한다.**

### 읽을 것 ③ 4분할이 OOM 을 냈고, 두 가지 방법으로 살아났다

C 는 `OutOfMemoryError: Java heap space` 로 7,900건에서 죽었다.

```
ERROR [icy-partition-2] AbstractStep : Encountered an error executing step issuePolicyStep
java.lang.OutOfMemoryError: Java heap space
```

놀랄 일이 아니다. **A·B 가 이미 510MB 로 천장에 닿아 있었다.** 여기에 동시성 4를 얹으면 in-flight
청크가 4배가 되고, 그 위에 Batik 이 4중으로 돌아간다.

원인 후보 둘을 각각 끊어 확인했다.

| | 처리량 | GC | 결과 |
|---|---|---|---|
| C. 4분할, 청크 100, 로고 있음 | — | 132.7s | ❌ OOM |
| E. 4분할, **청크 25**, 로고 있음 | 26.4건/초 | 182.9s | ✅ 완주 |
| F. 4분할, 청크 100, **로고 없음** | **49.5건/초** | 43.3s | ✅ 완주 |

둘 다 살렸지만 값어치가 다르다.

- **E** 는 청크를 동시성으로 나눠(100÷4) 겨우 천장 아래로 들어갔다. 완주는 하되 총 379초 중
  **183초를 GC 에 쓴다** — 절반에 가깝다. 살아 있을 뿐 건강하지 않다.
- **F** 는 로고를 빼서 202초에 끝냈다. 단일 스레드 22.0 → 49.5건/초, **4 vCPU 에서 2.25배**다.
  파티셔닝 자체는 제대로 확장한다.

### 그래서 결론

**파티셔닝이 안 되는 것이 아니라, 로고 SVG 가 파티셔닝의 이득을 먹고 있었다.** 장식 하나가
아키텍처 비용을 갖는 지점이고, 이 PoC 가 보고 싶었던 종류의 결과다.

| 상황 | 권고 |
|---|---|
| 512MB 고정, 로고 필요 | 파티셔닝을 켜려면 청크를 동시성으로 나눈다(100→25). 대신 GC 가 절반을 먹는다 |
| 512MB 고정, 로고 불필요 | 4분할 + 청크 100. 49.5건/초, 가장 건강하다 |
| **근본 해결** | SVG 를 문서마다 다시 파싱하지 않는다 — 한 번 렌더해 PDF Form XObject 로 재사용. [DESIGN 10장](DESIGN.md#10-남은-것) |
| 힙을 늘릴 수 있다면 | 1GB 면 C 가 그대로 돈다. 다만 H2 인메모리도 같은 힙에 있으므로, PostgreSQL 로 분리하는 편이 먼저다 |

`epolicy.batch.chunk-size` 와 `partition-count` 를 따로 둔 설정이 여기서 값을 했다. 코드를 고치지
않고 여섯 번의 실행으로 답을 얻었다.

---

## 5. 부수 검증

수치가 아니라 통과/실패로 확인되는 항목들이다. 전부 `./gradlew test` 안에서 돈다.

| 항목 | 테스트 | 결과 |
|---|---|---|
| PDF/A-1b 준수 (서명 완료 파일) | `PolicyIssuancePipelineTest.isPdfA1bCompliant` | 통과 |
| PDF/A-1b 준수 (임시파일 전략) | `TempFileBufferStrategyTest.producesCompliantDocument` | 통과 |
| PDF/A-1b 준수 (PAdES-B-T) | `TimestampedSignatureTest.staysPdfACompliantWithTimestamp` | 통과 |
| 서명 검증 | `DocumentIntegrityTest.verifiesIntactDocument` | 통과 |
| **1바이트 변조 탐지** | `detectsSingleByteTampering` | 검증 실패 확인 |
| **서명 범위 밖 덧붙임 탐지** | `detectsAppendedContent` | 검증 실패 확인 |
| 보관 파일 변조 탐지 | `detectsStorageTampering` | fileHash 불일치 확인 |
| 재발급 멱등성 | `IssuanceIdempotencyTest` | contentHash 동일, fileHash 상이 |
| 텍스트 레이어 개인정보 유출 없음 | `doesNotLeakPersonalDataIntoTextLayer` | 통과 |
| 임베딩 폰트가 나눔고딕 서브셋뿐 | `embedsOnlyBundledFonts` | 통과 |
| 임시파일 누수 없음 | `doesNotLeakTempFiles` | 통과 |
| 파티션 배타성 | `PartitionedBatchTest` | 전 건 1회차 |
