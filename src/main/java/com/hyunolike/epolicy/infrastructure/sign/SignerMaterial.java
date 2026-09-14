package com.hyunolike.epolicy.infrastructure.sign;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/** 서명에 쓰는 개인키와 인증서 체인. */
public record SignerMaterial(PrivateKey privateKey, List<X509Certificate> chain) {

    public SignerMaterial {
        if (privateKey == null) {
            throw new IllegalArgumentException("개인키가 없습니다");
        }
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("인증서 체인이 비어 있습니다");
        }
        chain = List.copyOf(chain);
    }

    public X509Certificate signerCertificate() {
        return chain.get(0);
    }

    public String signerName() {
        return signerCertificate().getSubjectX500Principal().getName();
    }
}
