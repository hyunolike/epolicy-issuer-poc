package com.hyunolike.epolicy.application.service;

import com.hyunolike.epolicy.domain.contract.ContractNo;
import com.hyunolike.epolicy.domain.document.IssuanceStatus;

/**
 * 발급 실패.
 *
 * <p>어느 단계에서 깨졌는지를 타입에 담는다. 배치의 SkipListener 가 이 값을 그대로 DLQ 에 적재하므로,
 * 재처리 시 "렌더에서 죽은 건"과 "TSA 타임아웃으로 죽은 건"을 분리해 다룰 수 있다.
 */
public class IssuanceFailedException extends RuntimeException {

    private final ContractNo contractNo;
    private final IssuanceStatus failedAfter;

    public IssuanceFailedException(ContractNo contractNo, IssuanceStatus failedAfter, String message, Throwable cause) {
        super("[%s] %s 단계 이후 실패: %s".formatted(contractNo, failedAfter, message), cause);
        this.contractNo = contractNo;
        this.failedAfter = failedAfter;
    }

    public ContractNo contractNo() {
        return contractNo;
    }

    /** 마지막으로 성공한 단계. 실패한 단계는 이 다음이다. */
    public IssuanceStatus failedAfter() {
        return failedAfter;
    }

    public String stage() {
        return failedAfter.name();
    }
}
