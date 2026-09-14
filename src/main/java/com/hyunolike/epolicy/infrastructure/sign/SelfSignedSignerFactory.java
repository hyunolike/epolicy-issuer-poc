package com.hyunolike.epolicy.infrastructure.sign;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 자체 서명 테스트 인증서 생성기.
 *
 * <p>설계 문서는 {@code resources/certs/test-signer.p12} 를 레포에 넣는 그림이었는데, 그러면 개인키가
 * 저장소에 들어간다. 시크릿 스캐너가 걸고, "테스트용"이라는 맥락은 클론된 뒤 사라진다. 그래서 첫 기동
 * 시 런타임에 만들어 쓰는 쪽으로 바꿨다 — 커밋할 비밀이 애초에 없다.
 *
 * <p>KeyUsage 에 {@code nonRepudiation} 을 넣는 것은 형식적 장식이 아니다. 문서 서명용 인증서의 표식이고,
 * Adobe Reader 는 이 비트가 없는 인증서로 서명된 문서에 경고를 띄운다.
 *
 * <p>물론 자체 서명이라 신뢰 체인은 없다. 검증에서 "서명은 무결하나 발급자를 신뢰할 수 없음"이 나오는
 * 것이 정상이고, 이 PoC 가 증명하려는 것은 신뢰 체인이 아니라 <b>변조 탐지</b>다.
 */
public final class SelfSignedSignerFactory {

    private static final Logger log = LoggerFactory.getLogger(SelfSignedSignerFactory.class);

    /** Adobe 문서 서명 EKU (1.2.840.113583.1.1.5). */
    private static final KeyPurposeId ADOBE_DOCUMENT_SIGNING =
            KeyPurposeId.getInstance(new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.113583.1.1.5"));

    private SelfSignedSignerFactory() {
    }

    /** 키스토어 파일을 만들어 저장한다. 이미 있으면 덮지 않는다. */
    public static void generateKeyStore(Path path, String alias, String password, String issuerName) {
        try {
            if (Files.exists(path)) {
                return;
            }
            BouncyCastleProvider provider = new BouncyCastleProvider();

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair keyPair = generator.generateKeyPair();

            Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant notAfter = notBefore.plus(3650, ChronoUnit.DAYS);
            X500Name subject = new X500Name(
                    "CN=%s 전자문서 서명(TEST), O=%s, C=KR".formatted(issuerName, issuerName));

            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject,
                    BigInteger.valueOf(System.currentTimeMillis()),
                    Date.from(notBefore),
                    Date.from(notAfter),
                    subject,
                    keyPair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(ADOBE_DOCUMENT_SIGNING));
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    new JcaX509ExtensionUtils().createSubjectKeyIdentifier(keyPair.getPublic()));

            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(provider)
                    .build(keyPair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter()
                    .setProvider(provider)
                    .getCertificate(builder.build(contentSigner));

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry(alias, keyPair.getPrivate(), password.toCharArray(),
                    new java.security.cert.Certificate[]{certificate});

            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(path)) {
                keyStore.store(out, password.toCharArray());
            }
            log.info("테스트용 자체 서명 인증서를 생성했습니다: {} (유효기간 {} ~ {})", path, notBefore, notAfter);
        } catch (GeneralSecurityException | IOException | OperatorCreationException e) {
            throw new IllegalStateException("테스트 서명 인증서 생성 실패: " + path, e);
        }
    }

    /** PKCS#12 키스토어에서 서명 자료를 읽는다. 운영에서는 미리 배치된 키스토어를 가리킨다. */
    public static SignerMaterial load(Path path, String alias, String password) {
        try (var in = Files.newInputStream(path)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, password.toCharArray());
            var key = keyStore.getKey(alias, password.toCharArray());
            if (!(key instanceof java.security.PrivateKey privateKey)) {
                throw new IllegalStateException("키스토어에 개인키가 없습니다: alias=" + alias);
            }
            java.security.cert.Certificate[] chain = keyStore.getCertificateChain(alias);
            if (chain == null || chain.length == 0) {
                throw new IllegalStateException("키스토어에 인증서 체인이 없습니다: alias=" + alias);
            }
            List<X509Certificate> certificates = java.util.Arrays.stream(chain)
                    .map(X509Certificate.class::cast)
                    .toList();
            return new SignerMaterial(privateKey, certificates);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("서명 키스토어를 읽을 수 없습니다: " + path, e);
        }
    }
}
