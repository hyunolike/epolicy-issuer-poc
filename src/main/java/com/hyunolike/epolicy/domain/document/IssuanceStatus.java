package com.hyunolike.epolicy.domain.document;

/**
 * 발급 상태.
 *
 * <p>설계 문서의 5단계에 ARCHIVED 를 하나 더 두었다. PDF/A 변환은 "서명 이전에만 가능한" 되돌릴 수 없는
 * 단계라서, 렌더 성공과 PDF/A 변환 성공을 한 상태로 뭉치면 실패 원인 분석에서 이 둘을 못 가른다.
 */
public enum IssuanceStatus {

    /** 발급 요청 접수. */
    REQUESTED,
    /** HTML → PDF 렌더 완료. */
    RENDERED,
    /** PDF/A-1b 변환 완료. 이 시점 바이트가 contentHash 의 입력이다. */
    ARCHIVED,
    /** PAdES 서명 완료. */
    SIGNED,
    /** 보관소 저장 + 이력 기록 완료. 최종 성공 상태. */
    STORED,
    /** 실패. 사유와 함께 DLQ 로 간다. */
    FAILED
}
