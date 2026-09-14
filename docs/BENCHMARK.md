# 측정 결과

> **결과 표는 아직 채워지지 않았다.** 1만 건 × 3가지 구성을 실행 중이고, 끝나는 대로 이 문서의
> [4장](#4-결과)에 그대로 붙는다. 방법론(1~3장)은 확정본이다.

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

비교한 세 가지 구성:

| 구성 | 옵션 |
|---|---|
| A. 메모리 버퍼 | `--epolicy.pdf.buffer-strategy=memory` |
| B. 임시파일 버퍼 | `--epolicy.pdf.buffer-strategy=temp-file` |
| C. 임시파일 + 4분할 | `B` + `--epolicy.batch.partition-count=4` |

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

_측정 진행 중._

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
