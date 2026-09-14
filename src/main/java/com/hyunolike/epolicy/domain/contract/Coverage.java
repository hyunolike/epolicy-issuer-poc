package com.hyunolike.epolicy.domain.contract;

/** 담보 한 줄. */
public record Coverage(String name, Money amount, String note) {

    public Coverage {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("담보명은 비어 있을 수 없습니다");
        }
        if (amount == null) {
            throw new IllegalArgumentException("가입금액은 비어 있을 수 없습니다");
        }
        note = note == null ? "" : note;
    }
}
