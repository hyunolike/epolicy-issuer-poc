package com.hyunolike.epolicy.infrastructure.render;

import com.hyunolike.epolicy.application.port.out.PdfArtifactFactory;
import com.hyunolike.epolicy.application.port.out.PdfRenderPort;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.slf4j.Slf4jLogger;
import com.openhtmltopdf.util.XRLog;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.core.io.Resource;

/**
 * 파이프라인 [3]단계 어댑터. openhtmltopdf(PDFBox 3 기반 포크).
 *
 * <p><b>폰트를 바이트 배열로 미리 읽어 두는 이유</b> — 렌더러는 요청마다 폰트 스트림을 새로 공급받는다.
 * 매번 클래스패스에서 2MB TTF 를 읽으면 1만 건 배치에서 20GB 를 읽게 된다. 한 번 읽어 힙에 두고
 * {@link ByteArrayInputStream} 을 새로 감싸 준다. 상주 비용 약 4MB 는 이 트레이드오프에서 싸다.
 *
 * <p><b>서브셋 임베딩</b> — PDF/A 는 폰트 전체 임베딩을 요구하지만 실제로 사용된 글리프만 담는 서브셋은
 * 허용한다. 한글 폰트를 통째로 넣으면 건당 4~6MB 이고 1만 건이면 수십 GB 다. 대신 서브셋 시 CIDSet
 * 일관성이 깨지면 veraPDF 에서 걸리므로, 이 지점이 이 PoC 의 실제 검증 포인트다.
 */
public class OpenHtmlPdfAdapter implements PdfRenderPort {

    static {
        // openhtmltopdf 는 기본적으로 java.util.logging 으로 직접 찍는다. 그대로 두면 폰트 로딩
        // 안내가 건당 두 줄씩 표준출력으로 나가고 application.yml 의 로그 레벨이 먹지 않는다.
        // 1만 건이면 2만 줄이다. slf4j 로 넘겨 로깅 설정 아래로 끌어온다.
        XRLog.setLoggerImpl(new Slf4jLogger());
    }

    /** 상대 URL 을 해석할 기준. 외부 리소스를 쓰지 않으므로 형식적인 값이다. */
    private static final String BASE_URI = "file:///epolicy/";

    private static final String FONT_FAMILY = "NanumGothic";

    private final byte[] regularFont;
    private final byte[] boldFont;
    private final PdfArtifactFactory artifactFactory;
    private final boolean subsetFonts;

    public OpenHtmlPdfAdapter(Resource regularFont, Resource boldFont,
                              PdfArtifactFactory artifactFactory, boolean subsetFonts) {
        this.regularFont = readFully(regularFont);
        this.boldFont = readFully(boldFont);
        this.artifactFactory = artifactFactory;
        this.subsetFonts = subsetFonts;
    }

    @Override
    public PdfArtifact render(String html) throws IOException {
        return artifactFactory.create(out -> {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            // useFastMode() 는 1.1.x 에서 deprecated 되었다. 이 포크는 fast renderer 가 기본이라
            // 호출할 필요가 없다 — 오래된 예제를 그대로 옮기면 경고가 뜬다.
            // PDF/A-1b 는 PDF 1.4 를 요구한다. 변환 단계에서 내려도 되지만, 애초에 1.4 로 만들면
            // 1.5 이상에서만 쓰는 구조(객체 스트림, 상호참조 스트림)가 생기지 않는다.
            builder.usePdfVersion(1.4f);
            registerFonts(builder);
            builder.withHtmlContent(html, BASE_URI);
            builder.toStream(out);
            try {
                builder.run();
            } catch (IOException e) {
                throw new UncheckedIOException("PDF 렌더 실패", e);
            }
        });
    }

    private void registerFonts(PdfRendererBuilder builder) {
        for (FontFace face : fontFaces()) {
            builder.useFont(() -> new ByteArrayInputStream(face.bytes()), FONT_FAMILY,
                    face.weight(), BaseRendererBuilder.FontStyle.NORMAL, subsetFonts);
        }
    }

    private List<FontFace> fontFaces() {
        return List.of(new FontFace(regularFont, 400), new FontFace(boldFont, 700));
    }

    @Override
    public String rendererName() {
        return "openhtmltopdf-" + (subsetFonts ? "subset" : "full-embed");
    }

    private static byte[] readFully(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("폰트를 읽을 수 없습니다: " + resource.getDescription(), e);
        }
    }

    private record FontFace(byte[] bytes, int weight) {
    }
}
