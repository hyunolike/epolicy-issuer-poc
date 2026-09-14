package com.hyunolike.epolicy.domain.contract;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 보험기간. */
public record InsurancePeriod(LocalDate start, LocalDate end) {

    public InsurancePeriod {
        if (start == null || end == null) {
            throw new IllegalArgumentException("보험기간은 시작일과 종료일이 모두 필요합니다");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("보험종료일이 개시일보다 빠릅니다: " + start + " ~ " + end);
        }
    }

    public long months() {
        return ChronoUnit.MONTHS.between(start, end);
    }
}
