package com.hyunolike.epolicy.infrastructure.sign;

import com.hyunolike.epolicy.application.port.out.TimestampPort;
import java.io.IOException;

/**
 * TSA 를 쓰지 않는 구현. 기본값이다.
 *
 * <p>공개 TSA 는 외부 네트워크가 필요하다. 망 분리 환경이나 CI 에서 기본으로 켜 두면 발급 전체가
 * 남의 서버 가용성에 묶인다. 서명 자체(PAdES-B-B)는 타임스탬프 없이도 변조 탐지에 충분하다 —
 * 타임스탬프가 더해 주는 것은 "언제" 서명했는지에 대한 제3자 보증이다.
 */
public class DisabledTimestampAdapter implements TimestampPort {

    @Override
    public byte[] timestamp(byte[] messageImprint) throws IOException {
        throw new IOException("TSA 가 비활성화되어 있습니다 (epolicy.sign.tsa.enabled=false)");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
