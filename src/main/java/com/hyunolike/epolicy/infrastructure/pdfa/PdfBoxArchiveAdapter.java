package com.hyunolike.epolicy.infrastructure.pdfa;

import com.hyunolike.epolicy.application.port.out.ArchiveMetadata;
import com.hyunolike.epolicy.application.port.out.PdfArchivePort;
import com.hyunolike.epolicy.application.port.out.PdfArtifactFactory;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.AdobePDFSchema;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.schema.XMPBasicSchema;
import org.apache.xmpbox.xml.XmpSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

/**
 * 파이프라인 [4]단계 어댑터. 일반 PDF → PDF/A-1b.
 *
 * <p>여기서 하는 일은 네 가지다.
 * <ol>
 *   <li>문서정보 딕셔너리와 XMP 를 <b>같은 값으로</b> 채운다. PDF/A-1b 는 둘의 불일치를 위반으로 본다</li>
 *   <li>OutputIntent 에 sRGB ICC 프로파일을 심는다. 색 해석 기준을 문서에 못 박는 장치다</li>
 *   <li>PDF 1.4 로 내리고 비압축으로 저장한다 — 아래 설명 참고</li>
 *   <li>1.4 에 없는 구조(투명도 그룹 등)를 걷어낸다</li>
 * </ol>
 *
 * <p><b>비압축 저장이 선택이 아닌 이유</b> — PDFBox 3 의 {@code save(OutputStream)} 은 기본이
 * 압축 저장이고, 그러면 객체 스트림과 상호참조 스트림이 생긴다. 둘 다 PDF 1.5 에서 도입된 구조라
 * 헤더에 1.4 를 써 놔도 PDF/A-1b 검증에서 걸린다. PDFBox 2 에서 3 으로 올라오며 기본값이 바뀐
 * 부분이라 2.x 예제를 그대로 옮기면 여기서 조용히 실패한다.
 *
 * <p><b>왜 서명 전인가</b> — 서명은 파일 바이트를 고정한다. 서명 후 메타데이터를 넣으면 서명이 깨진다.
 * 순서를 바꿀 수 없는 제약이고, 그래서 이 어댑터가 파이프라인에서 서명 앞에 온다.
 */
public class PdfBoxArchiveAdapter implements PdfArchivePort {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxArchiveAdapter.class);

    private static final float PDFA_1B_VERSION = 1.4f;
    private static final ZoneId DOCUMENT_ZONE = ZoneId.of("Asia/Seoul");
    private static final String COLOR_CONDITION = "sRGB IEC61966-2.1";

    private final byte[] iccProfile;
    private final PdfArtifactFactory artifactFactory;
    private final boolean tempFileCache;

    public PdfBoxArchiveAdapter(Resource iccProfile, PdfArtifactFactory artifactFactory, boolean tempFileCache) {
        this.iccProfile = readFully(iccProfile);
        this.artifactFactory = artifactFactory;
        this.tempFileCache = tempFileCache;
    }

    @Override
    public PdfArtifact toArchivalPdf(PdfArtifact source, ArchiveMetadata metadata) throws IOException {
        return artifactFactory.create(out -> {
            try (PDDocument document = PdfDocuments.load(source, tempFileCache)) {
                Calendar documentDate = toCalendar(metadata);

                document.setVersion(PDFA_1B_VERSION);
                applyDocumentInformation(document, metadata, documentDate);
                applyXmpMetadata(document, metadata, documentDate);
                applyOutputIntent(document);
                applyDeterministicId(document, metadata);
                stripPdf14Violations(document);

                document.save(out, CompressParameters.NO_COMPRESSION);
            }
        });
    }

    private void applyDocumentInformation(PDDocument document, ArchiveMetadata metadata, Calendar date) {
        PDDocumentInformation info = new PDDocumentInformation();
        info.setTitle(metadata.title());
        info.setAuthor(metadata.author());
        info.setSubject(metadata.subject());
        info.setKeywords(metadata.keywords());
        info.setCreator(metadata.creatorTool());
        info.setProducer(metadata.producer());
        info.setCreationDate(date);
        info.setModificationDate(date);
        // 렌더러가 남긴 값을 이어받지 않고 통째로 교체한다. 남은 키 하나가 XMP 와 어긋나면 위반이다.
        document.setDocumentInformation(info);
    }

    private void applyXmpMetadata(PDDocument document, ArchiveMetadata metadata, Calendar date) {
        try {
            XMPMetadata xmp = XMPMetadata.createXMPMetadata();

            DublinCoreSchema dc = xmp.createAndAddDublinCoreSchema();
            dc.setTitle(metadata.title());
            dc.addCreator(metadata.author());
            dc.setDescription(metadata.subject());

            AdobePDFSchema pdf = xmp.createAndAddAdobePDFSchema();
            pdf.setProducer(metadata.producer());
            pdf.setKeywords(metadata.keywords());

            XMPBasicSchema basic = xmp.createAndAddXMPBasicSchema();
            basic.setCreatorTool(metadata.creatorTool());
            basic.setCreateDate(date);
            basic.setModifyDate(date);
            // MetadataDate 도 같은 값으로 둔다. now() 를 쓰면 재발급 때 바이트가 달라진다.
            basic.setMetadataDate(date);

            PDFAIdentificationSchema pdfaId = xmp.createAndAddPDFAIdentificationSchema();
            pdfaId.setPart(1);
            pdfaId.setConformance("B");

            ByteArrayOutputStream serialized = new ByteArrayOutputStream();
            new XmpSerializer().serialize(xmp, serialized, true);

            PDMetadata pdMetadata = new PDMetadata(document);
            pdMetadata.importXMPMetadata(serialized.toByteArray());
            document.getDocumentCatalog().setMetadata(pdMetadata);
        } catch (IOException e) {
            throw new UncheckedIOException("XMP 메타데이터 생성 실패", e);
        } catch (Exception e) {
            throw new IllegalStateException("XMP 메타데이터 직렬화 실패", e);
        }
    }

    private void applyOutputIntent(PDDocument document) {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        if (!catalog.getOutputIntents().isEmpty()) {
            // PDF/A-1b 는 OutputIntent 를 하나만 허용한다. 렌더러가 이미 넣었다면 덮지 않는다.
            return;
        }
        try (InputStream icc = new ByteArrayInputStream(iccProfile)) {
            PDOutputIntent intent = new PDOutputIntent(document, icc);
            intent.setInfo(COLOR_CONDITION);
            intent.setOutputCondition(COLOR_CONDITION);
            intent.setOutputConditionIdentifier(COLOR_CONDITION);
            intent.setRegistryName("http://www.color.org");
            catalog.addOutputIntent(intent);
        } catch (IOException e) {
            throw new UncheckedIOException("OutputIntent 설정 실패", e);
        }
    }

    /**
     * trailer 의 /ID 를 결정적으로 고정한다.
     *
     * <p>PDFBox 는 /ID 가 비어 있으면 저장 시각과 문서정보를 MD5 로 말아 만든다. 그대로 두면 같은
     * 계약을 두 번 발급할 때 바이트가 달라지고, contentHash 멱등성이 성립하지 않는다.
     */
    private void applyDeterministicId(PDDocument document, ArchiveMetadata metadata) {
        byte[] seed = digest(metadata.deterministicIdSeed());
        COSString id = new COSString(Arrays.copyOf(seed, 16));
        COSArray idArray = new COSArray();
        idArray.add(id);
        idArray.add(id);
        idArray.setDirect(true);
        document.getDocument().setDocumentID(idArray);
    }

    /**
     * PDF 1.4 에 없는 구조를 걷어낸다.
     *
     * <p>지금 지우는 것은 페이지의 투명도 그룹(/Group &lt;&lt;/S /Transparency&gt;&gt;)이다. 렌더러가
     * 관행적으로 붙이는데 PDF/A-1 은 투명도를 허용하지 않는다. 페이지 내용이 실제로 투명도를 쓰지
     * 않는다면 이 키를 지우는 것만으로 해결되고, 실제로 쓴다면 여기서 지워도 검증에서 다시 걸린다 —
     * 그래서 템플릿 CSS 에서 opacity/rgba/box-shadow 를 금지한다.
     */
    private void stripPdf14Violations(PDDocument document) {
        int stripped = 0;
        for (PDPage page : document.getPages()) {
            COSDictionary pageDict = page.getCOSObject();
            COSDictionary group = pageDict.getCOSDictionary(COSName.GROUP);
            if (group != null && COSName.TRANSPARENCY.equals(group.getCOSName(COSName.S))) {
                pageDict.removeItem(COSName.GROUP);
                stripped++;
            }
        }
        if (stripped > 0) {
            log.debug("투명도 그룹 {}개를 제거했습니다 (PDF/A-1b 제약)", stripped);
        }
    }

    private static Calendar toCalendar(ArchiveMetadata metadata) {
        return GregorianCalendar.from(metadata.documentInstant().atZone(DOCUMENT_ZONE));
    }

    private static byte[] digest(String seed) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 지원하지 않는 JVM 입니다", e);
        }
    }

    private static byte[] readFully(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("ICC 프로파일을 읽을 수 없습니다: " + resource.getDescription(), e);
        }
    }
}
