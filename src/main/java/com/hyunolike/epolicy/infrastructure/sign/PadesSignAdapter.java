package com.hyunolike.epolicy.infrastructure.sign;

import com.hyunolike.epolicy.application.port.out.DocumentSignPort;
import com.hyunolike.epolicy.application.port.out.PdfArtifactFactory;
import com.hyunolike.epolicy.application.port.out.SignRequest;
import com.hyunolike.epolicy.application.port.out.TimestampPort;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSTypedData;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 파이프라인 [6]단계 어댑터. PAdES 전자서명.
 *
 * <p>증분 저장(incremental save)으로 붙인다. 원본 바이트는 그대로 두고 뒤에 개정을 덧붙이므로 앞
 * 단계에서 만든 PDF/A 구조가 보존된다. ByteRange 는 서명값 자리(/Contents)만 도려낸 나머지 전체를
 * 가리키게 되고, 그래서 파일 어디를 1바이트 고쳐도 검증이 깨진다.
 *
 * <p><b>signingTime 을 서명 속성에서 빼는 이유</b> — PAdES 는 서명 시각의 근거를 CMS 속성이 아니라
 * 서명 딕셔너리의 /M 과 타임스탬프 토큰에서 찾는다. CMS signingTime 은 서명자가 제 손으로 적는
 * 값이라 증명력이 없고, 두 곳에 다른 시각이 적히면 검증기마다 다르게 해석한다.
 *
 * <p><b>서명 후에도 PDF/A 를 유지하려면</b> 서명 위젯 주석이 PDF/A-1b 의 주석 규칙을 지켜야 한다.
 * Print 플래그가 서 있어야 하고, 외관 스트림(AP)이 있어야 한다. 보이지 않는 서명이라 그릴 것은
 * 없지만 빈 외관이라도 있어야 검증을 통과한다.
 */
public class PadesSignAdapter implements DocumentSignPort {

    private static final Logger log = LoggerFactory.getLogger(PadesSignAdapter.class);

    /** ETSI PAdES 서브필터. Adobe 고유 값(adbe.pkcs7.detached)이 아니라 표준 쪽을 쓴다. */
    private static final COSName ETSI_CADES_DETACHED = COSName.getPDFName("ETSI.CAdES.detached");

    /** 서명값 자리 예약 크기. TSA 토큰이 붙으면 인증서까지 들어가 훨씬 커진다. */
    private static final int SIGNATURE_SIZE_PLAIN = 0x2500;
    private static final int SIGNATURE_SIZE_WITH_TSA = 0x6000;

    private final SignerMaterial signer;
    private final TimestampPort timestampPort;
    private final PdfArtifactFactory artifactFactory;
    private final BouncyCastleProvider provider = new BouncyCastleProvider();

    public PadesSignAdapter(SignerMaterial signer, TimestampPort timestampPort,
                            PdfArtifactFactory artifactFactory) {
        this.signer = signer;
        this.timestampPort = timestampPort;
        this.artifactFactory = artifactFactory;
    }

    @Override
    public PdfArtifact sign(PdfArtifact source, SignRequest request) throws IOException {
        boolean withTimestamp = request.withTimestamp() && timestampPort.isAvailable();
        if (request.withTimestamp() && !withTimestamp) {
            log.warn("TSA 를 사용할 수 없어 타임스탬프 없이(PAdES-B-B) 서명합니다");
        }
        // 서명은 문서 전체에 대한 랜덤 액세스가 필요해서 이 단계만은 바이트를 들고 간다.
        byte[] original = source.readAllBytes();
        return artifactFactory.create(out -> {
            // SignatureOptions 는 서명값 자리를 잡기 위한 임시 버퍼를 들고 있다. try-with-resources
            // 밖에서 close 하면 서명 중 예외가 났을 때 그 버퍼가 회수되지 않는다.
            try (PDDocument document = Loader.loadPDF(original);
                 SignatureOptions options = new SignatureOptions()) {
                options.setPreferredSignatureSize(
                        withTimestamp ? SIGNATURE_SIZE_WITH_TSA : SIGNATURE_SIZE_PLAIN);

                document.addSignature(buildSignatureDictionary(request),
                        new CmsSigner(withTimestamp), options);
                makeSignatureWidgetPdfACompliant(document);
                document.saveIncremental(out);
            }
        });
    }

    private PDSignature buildSignatureDictionary(SignRequest request) {
        PDSignature signature = new PDSignature();
        signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
        signature.setSubFilter(ETSI_CADES_DETACHED);
        signature.setName(signer.signerCertificate().getSubjectX500Principal().getName());
        signature.setReason(request.reason());
        signature.setLocation(request.location());
        signature.setContactInfo(request.contactInfo());
        Calendar signedAt = new GregorianCalendar(TimeZone.getTimeZone("Asia/Seoul"));
        signedAt.setTimeInMillis(request.signedAt().toEpochMilli());
        signature.setSignDate(signedAt);
        return signature;
    }

    /**
     * 서명 위젯 주석을 PDF/A-1b 주석 규칙에 맞춘다.
     *
     * <p>PDFBox 가 만들어 주는 보이지 않는 서명 필드는 Rect 가 [0 0 0 0] 이고 외관 스트림이 없다.
     * PDF/A-1b 는 Popup/Link 가 아닌 모든 주석에 AP 를 요구하고 Print 플래그를 켜라고 하므로,
     * 그대로 두면 서명 직후 PDF/A 검증이 깨진다.
     */
    private void makeSignatureWidgetPdfACompliant(PDDocument document) throws IOException {
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
        if (acroForm == null) {
            return;
        }
        // PDF/A-1b 는 NeedAppearances 가 true 인 것을 허용하지 않는다.
        acroForm.setNeedAppearances(false);
        // PDFBox 가 서명 필드를 만들 때 AcroForm 기본 리소스(/DR)에 표준 14폰트 Helv, ZaDb 를 넣는다.
        // PDF/A 는 폰트 임베딩을 요구하므로 PDFBox 는 이 둘을 시스템 폰트(LiberationSans 등)로
        // 대체해 파일에 박아 넣는다. 결과가 셋 다 나쁘다 — 서명 파일이 실행 머신의 설치 폰트에
        // 의존하고, 문서당 수십 KB 가 붙고, 폰트가 없는 최소 컨테이너에서는 서명이 실패한다.
        // 보이지 않는 서명이라 위젯에 빈 외관(AP)을 직접 넣어 두었으므로 /DR 은 필요 없다.
        acroForm.setDefaultResources(new PDResources());
        for (PDField field : acroForm.getFieldTree()) {
            if (!(field instanceof PDSignatureField signatureField)) {
                continue;
            }
            for (PDAnnotationWidget widget : signatureField.getWidgets()) {
                widget.setPrinted(true);
                widget.setHidden(false);
                widget.setNoView(false);
                if (widget.getAppearance() == null) {
                    widget.setAppearance(emptyAppearance(document));
                }
            }
        }
    }

    private PDAppearanceDictionary emptyAppearance(PDDocument document) {
        PDAppearanceStream stream = new PDAppearanceStream(document);
        stream.setBBox(new PDRectangle(0, 0, 0, 0));
        stream.setResources(new PDResources());
        PDAppearanceDictionary appearance = new PDAppearanceDictionary();
        appearance.setNormalAppearance(stream);
        return appearance;
    }

    /** ByteRange 가 가리키는 내용에 대해 CMS(detached) 서명을 만든다. */
    private final class CmsSigner implements SignatureInterface {

        private final boolean withTimestamp;

        private CmsSigner(boolean withTimestamp) {
            this.withTimestamp = withTimestamp;
        }

        @Override
        public byte[] sign(InputStream content) throws IOException {
            try {
                byte[] toSign = content.readAllBytes();
                CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
                generator.addSignerInfoGenerator(signerInfoGenerator());
                generator.addCertificates(new JcaCertStore(signer.chain()));

                CMSTypedData typedData = new org.bouncycastle.cms.CMSProcessableByteArray(toSign);
                CMSSignedData signedData = generator.generate(typedData, false);
                if (withTimestamp) {
                    signedData = addSignatureTimestamp(signedData);
                }
                return signedData.getEncoded();
            } catch (OperatorCreationException | CMSException | CertificateEncodingException e) {
                throw new IOException("CMS 서명 생성 실패", e);
            }
        }

        private SignerInfoGenerator signerInfoGenerator()
                throws OperatorCreationException, CertificateEncodingException {
            ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(provider)
                    .build(signer.privateKey());
            return new JcaSignerInfoGeneratorBuilder(
                    new JcaDigestCalculatorProviderBuilder().setProvider(provider).build())
                    .setSignedAttributeGenerator(padesSignedAttributes())
                    .build(contentSigner, signer.signerCertificate());
        }

        /**
         * PAdES 서명 속성.
         *
         * <p>signing-certificate-v2 를 넣어 "이 서명은 이 인증서로 한 것"을 서명 대상에 포함시킨다.
         * 없으면 인증서 바꿔치기 공격이 가능하다. 반대로 signing-time 은 빼낸다.
         */
        private DefaultSignedAttributeTableGenerator padesSignedAttributes()
                throws CertificateEncodingException {
            ASN1EncodableVector attributes = new ASN1EncodableVector();
            attributes.add(signingCertificateV2Attribute());
            return new DefaultSignedAttributeTableGenerator(new AttributeTable(attributes)) {
                @Override
                protected Hashtable createStandardAttributeTable(Map parameters) {
                    Hashtable standard = super.createStandardAttributeTable(parameters);
                    standard.remove(CMSAttributes.signingTime);
                    return standard;
                }
            };
        }

        private Attribute signingCertificateV2Attribute() throws CertificateEncodingException {
            byte[] certHash = sha256(signer.signerCertificate().getEncoded());
            ESSCertIDv2 certId = new ESSCertIDv2(
                    new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256), certHash);
            SigningCertificateV2 signingCertificate = new SigningCertificateV2(new ESSCertIDv2[]{certId});
            return new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                    new DERSet(signingCertificate));
        }

        /**
         * 서명값에 대한 RFC 3161 타임스탬프를 unsigned attribute 로 붙여 PAdES-B-T 를 만든다.
         *
         * <p>서명 대상이 아니라 서명값 자체에 찍는다는 점이 중요하다. "이 서명이 이 시각 이전에
         * 존재했다"를 제3자가 보증하는 구조다.
         */
        private CMSSignedData addSignatureTimestamp(CMSSignedData signedData) throws IOException, CMSException {
            List<SignerInformation> updated = new ArrayList<>();
            for (SignerInformation signerInfo : signedData.getSignerInfos().getSigners()) {
                byte[] token = timestampPort.timestamp(sha256(signerInfo.getSignature()));
                Attribute timestampAttribute = new Attribute(
                        PKCSObjectIdentifiers.id_aa_signatureTimeStampToken,
                        new DERSet(org.bouncycastle.asn1.ASN1Primitive.fromByteArray(token)));
                AttributeTable unsigned = signerInfo.getUnsignedAttributes();
                Hashtable<ASN1ObjectIdentifier, Attribute> table =
                        unsigned == null ? new Hashtable<>() : unsigned.toHashtable();
                table.put(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken, timestampAttribute);
                updated.add(SignerInformation.replaceUnsignedAttributes(
                        signerInfo, new AttributeTable(table)));
            }
            return CMSSignedData.replaceSigners(signedData, new SignerInformationStore(updated));
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 지원하지 않는 JVM 입니다", e);
        }
    }

}
