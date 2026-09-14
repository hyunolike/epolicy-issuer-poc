package com.hyunolike.epolicy.support;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

/**
 * 테스트용 RFC 3161 TSA.
 *
 * <p>공개 TSA(FreeTSA 등)에 의존하면 PAdES-B-T 경로는 네트워크가 되는 날에만 검증된다. 망 분리
 * 환경이나 CI 에서는 영영 돌지 않는다는 뜻이고, 그러면 그 코드는 사실상 검증되지 않은 채로 남는다.
 * BouncyCastle 에 TSA 쪽 구현({@link TimeStampTokenGenerator})도 들어 있으므로 프로세스 안에서
 * 띄운다.
 *
 * <p>TSA 인증서는 {@code id-kp-timeStamping} EKU 를 <b>critical</b> 로 달아야 한다. BouncyCastle 이
 * 토큰 생성 시점에 이것을 검사하고, 없으면 거부한다 — 아무 인증서로나 시각을 보증할 수 없게 하는
 * 장치다.
 */
public final class EmbeddedTsaServer implements AutoCloseable {

    private static final ASN1ObjectIdentifier TSA_POLICY = new ASN1ObjectIdentifier("1.2.3.4.1");

    private final HttpServer server;
    private final X509Certificate certificate;

    public EmbeddedTsaServer() {
        try {
            BouncyCastleProvider provider = new BouncyCastleProvider();
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            this.certificate = selfSignedTsaCertificate(keyPair, provider);

            SignerInfoGenerator signerInfoGenerator = new JcaSimpleSignerInfoGeneratorBuilder()
                    .setProvider(provider)
                    .build("SHA256withRSA", keyPair.getPrivate(), certificate);
            DigestCalculator digestCalculator = new JcaDigestCalculatorProviderBuilder()
                    .setProvider(provider)
                    .build()
                    .get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1));

            TimeStampTokenGenerator tokenGenerator = new TimeStampTokenGenerator(
                    signerInfoGenerator, digestCalculator, TSA_POLICY);
            tokenGenerator.addCertificates(new JcaCertStore(List.of(certificate)));
            TimeStampResponseGenerator responseGenerator =
                    new TimeStampResponseGenerator(tokenGenerator, TSPAlgorithms.ALLOWED);

            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/tsr", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                byte[] response;
                try {
                    TimeStampRequest request = new TimeStampRequest(body);
                    TimeStampResponse timeStampResponse = responseGenerator.generate(
                            request, BigInteger.valueOf(System.nanoTime()), new Date());
                    response = timeStampResponse.getEncoded();
                } catch (Exception e) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type", "application/timestamp-reply");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
            });
            this.server.start();
        } catch (Exception e) {
            throw new IllegalStateException("테스트용 TSA 를 띄우지 못했습니다", e);
        }
    }

    private static X509Certificate selfSignedTsaCertificate(KeyPair keyPair, BouncyCastleProvider provider)
            throws Exception {
        X500Name subject = new X500Name("CN=epolicy embedded TSA (TEST), O=epolicy, C=KR");
        Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.ONE, Date.from(notBefore),
                Date.from(notBefore.plus(365, ChronoUnit.DAYS)), subject, keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        // critical 이어야 한다. BouncyCastle 이 토큰 생성 시 확인한다.
        builder.addExtension(Extension.extendedKeyUsage, true,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        return new JcaX509CertificateConverter().setProvider(provider).getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA")
                        .setProvider(provider).build(keyPair.getPrivate())));
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/tsr";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
