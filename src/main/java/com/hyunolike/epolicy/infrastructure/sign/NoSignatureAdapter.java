package com.hyunolike.epolicy.infrastructure.sign;

import com.hyunolike.epolicy.application.port.out.DocumentSignPort;
import com.hyunolike.epolicy.application.port.out.PdfArtifactFactory;
import com.hyunolike.epolicy.application.port.out.SignRequest;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import java.io.IOException;
import java.io.InputStream;

/**
 * 서명을 건너뛰는 구현({@code epolicy.sign.enabled=false}).
 *
 * <p>원본을 그대로 돌려주지 않고 복사본을 만든다. 파이프라인은 "각 단계가 새 아티팩트를 돌려주고
 * 호출자가 이전 것을 닫는다"는 규칙으로 자원을 관리하는데, 여기서만 같은 객체를 돌려주면 이중 close
 * 가 나기 때문이다. 규칙에 예외를 두는 것보다 한 번 복사하는 편이 싸다.
 */
public class NoSignatureAdapter implements DocumentSignPort {

    private final PdfArtifactFactory artifactFactory;

    public NoSignatureAdapter(PdfArtifactFactory artifactFactory) {
        this.artifactFactory = artifactFactory;
    }

    @Override
    public PdfArtifact sign(PdfArtifact source, SignRequest request) throws IOException {
        return artifactFactory.create(out -> {
            try (InputStream in = source.openStream()) {
                in.transferTo(out);
            }
        });
    }
}
