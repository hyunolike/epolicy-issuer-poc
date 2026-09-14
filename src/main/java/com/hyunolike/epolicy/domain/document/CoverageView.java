package com.hyunolike.epolicy.domain.document;

import com.hyunolike.epolicy.domain.masking.MaskedValue;

/** 담보 한 줄의 렌더용 표현. 금액은 도메인에서 이미 포맷된 문자열로 넘어온다. */
public record CoverageView(MaskedValue name, MaskedValue amount, MaskedValue note) {

    public String getName() {
        return name.value();
    }

    public String getAmount() {
        return amount.value();
    }

    public String getNote() {
        return note.value();
    }
}
