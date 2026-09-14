package com.hyunolike.epolicy.domain.masking;

/** 마스킹되지 않은 개인정보가 렌더 경계를 넘으려 할 때 발생한다. 절대 삼키지 않는다. */
public class PersonalDataLeakException extends RuntimeException {

    public PersonalDataLeakException(String message) {
        super(message);
    }
}
