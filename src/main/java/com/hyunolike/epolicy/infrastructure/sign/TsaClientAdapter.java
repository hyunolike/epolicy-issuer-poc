package com.hyunolike.epolicy.infrastructure.sign;

import com.hyunolike.epolicy.application.port.out.TimestampPort;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RFC 3161 타임스탬프 클라이언트.
 *
 * <p>nonce 는 선택이 아니다. 없으면 중간자가 예전 응답을 재생(replay)해도 구분할 수 없다. 응답의
 * {@code validate(request)} 호출이 nonce 와 messageImprint 일치를 확인한다.
 *
 * <p>certReq 를 켜면 TSA 인증서가 토큰에 포함된다. 장기검증(LTV)에서 필요하고 토큰이 커지므로,
 * 서명값 예약 크기를 넉넉히 잡아야 한다({@link PadesSignAdapter} 참고).
 */
public class TsaClientAdapter implements TimestampPort {

    private static final Logger log = LoggerFactory.getLogger(TsaClientAdapter.class);

    private static final String CONTENT_TYPE = "application/timestamp-query";
    private static final String ACCEPT = "application/timestamp-reply";

    private final URI endpoint;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final boolean requestCertificate;
    private final SecureRandom random = new SecureRandom();

    public TsaClientAdapter(String url, Duration timeout, boolean requestCertificate) {
        this.endpoint = URI.create(url);
        this.timeout = timeout;
        this.requestCertificate = requestCertificate;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public byte[] timestamp(byte[] messageImprint) throws IOException {
        try {
            TimeStampRequestGenerator generator = new TimeStampRequestGenerator();
            generator.setCertReq(requestCertificate);
            BigInteger nonce = new BigInteger(64, random);
            TimeStampRequest request = generator.generate(TSPAlgorithms.SHA256, messageImprint, nonce);

            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", CONTENT_TYPE)
                    .header("Accept", ACCEPT)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request.getEncoded()))
                    .build();
            HttpResponse<byte[]> httpResponse =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (httpResponse.statusCode() != 200) {
                throw new IOException("TSA 응답 코드가 200 이 아닙니다: " + httpResponse.statusCode());
            }

            TimeStampResponse response = new TimeStampResponse(httpResponse.body());
            response.validate(request);
            TimeStampToken token = response.getTimeStampToken();
            if (token == null) {
                throw new IOException("TSA 가 토큰을 돌려주지 않았습니다: " + response.getStatusString());
            }
            return token.getEncoded();
        } catch (TSPException e) {
            throw new IOException("TSA 응답 검증 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("TSA 호출이 중단되었습니다", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    /** 기동 시 한 번 호출해 TSA 도달 가능 여부를 로그로 남긴다. */
    public void logEndpoint() {
        log.info("TSA 사용: {} (timeout={}ms, certReq={})", endpoint, timeout.toMillis(), requestCertificate);
    }
}
