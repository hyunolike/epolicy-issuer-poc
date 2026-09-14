package com.hyunolike.epolicy.infrastructure.sign;

import com.hyunolike.epolicy.application.port.out.SignatureVerification;
import com.hyunolike.epolicy.application.port.out.SignatureVerificationPort;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * 서명 검증.
 *
 * <p>세 가지를 각각 본다.
 * <ol>
 *   <li><b>암호학적 무결성</b> — CMS 서명이 ByteRange 가 가리키는 내용과 맞는가</li>
 *   <li><b>서명 범위</b> — ByteRange 가 /Contents 를 뺀 파일 전체를 덮는가</li>
 *   <li><b>타임스탬프 존재</b> — unsigned attribute 에 TST 가 있는가 (B-T 여부)</li>
 * </ol>
 *
 * <p>2번을 따로 보는 이유는, 서명이 유효해도 파일 끝에 서명 범위 밖 내용을 덧붙여 다른 문서처럼
 * 보이게 만드는 공격이 가능하기 때문이다. 암호학적 검증만 통과했다고 안심하면 놓친다.
 *
 * <p>신뢰 체인(인증서가 믿을 만한 CA 에서 나왔는가)은 보지 않는다. 자체 서명 테스트 인증서를 쓰므로
 * 여기서 체인을 따지면 항상 실패한다. 이 PoC 의 검증 대상은 <b>변조 탐지</b>다.
 */
public class PdfBoxSignatureVerificationAdapter implements SignatureVerificationPort {

    private final BouncyCastleProvider provider = new BouncyCastleProvider();

    @Override
    public List<SignatureVerification> verify(PdfArtifact document) throws IOException {
        byte[] bytes = document.readAllBytes();
        List<SignatureVerification> results = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            List<PDSignature> signatures = pdf.getSignatureDictionaries();
            if (signatures.isEmpty()) {
                return List.of(SignatureVerification.failed("서명이 없는 문서입니다"));
            }
            for (PDSignature signature : signatures) {
                results.add(verifyOne(signature, bytes));
            }
        }
        return results;
    }

    private SignatureVerification verifyOne(PDSignature signature, byte[] fileBytes) {
        List<String> problems = new ArrayList<>();
        boolean coversWholeDocument = coversWholeDocument(signature, fileBytes, problems);
        try {
            byte[] signedContent = signature.getSignedContent(fileBytes);
            byte[] contents = signature.getContents(fileBytes);

            CMSSignedData signedData =
                    new CMSSignedData(new CMSProcessableByteArray(signedContent), contents);
            Collection<SignerInformation> signers = signedData.getSignerInfos().getSigners();
            if (signers.isEmpty()) {
                return new SignatureVerification(false, coversWholeDocument, null, null, false,
                        List.of("CMS 에 서명자 정보가 없습니다"));
            }

            SignerInformation signerInfo = signers.iterator().next();
            Collection<X509CertificateHolder> matches =
                    signedData.getCertificates().getMatches(signerInfo.getSID());
            if (matches.isEmpty()) {
                return new SignatureVerification(false, coversWholeDocument, null, null, false,
                        List.of("서명자 인증서를 CMS 에서 찾을 수 없습니다"));
            }

            X509Certificate certificate = new JcaX509CertificateConverter()
                    .setProvider(provider)
                    .getCertificate(matches.iterator().next());
            boolean valid = signerInfo.verify(
                    new JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(certificate));
            if (!valid) {
                problems.add("서명값이 문서 내용과 일치하지 않습니다 (변조 가능성)");
            }

            return new SignatureVerification(
                    valid,
                    coversWholeDocument,
                    certificate.getSubjectX500Principal().getName(),
                    signedAt(signature),
                    hasTimestamp(signerInfo),
                    problems);
        } catch (Exception e) {
            // 변조된 문서에서는 CMS 파싱 단계에서 터지는 경우도 많다. 예외 자체가 검증 실패다.
            problems.add("서명 검증 실패: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
            return new SignatureVerification(false, coversWholeDocument, null,
                    signedAt(signature), false, problems);
        }
    }

    /**
     * ByteRange 가 파일 전체(서명값 자리 제외)를 덮는지 본다.
     *
     * <p>ByteRange 는 [시작1, 길이1, 시작2, 길이2] 이고, 두 구간 사이의 구멍이 /Contents 자리다.
     * 마지막 구간의 끝이 파일 끝과 같지 않다면 서명 범위 밖에 무언가 덧붙어 있다는 뜻이다.
     */
    private boolean coversWholeDocument(PDSignature signature, byte[] fileBytes, List<String> problems) {
        int[] byteRange = signature.getByteRange();
        if (byteRange == null || byteRange.length != 4) {
            problems.add("ByteRange 가 올바르지 않습니다");
            return false;
        }
        long end = (long) byteRange[2] + byteRange[3];
        if (byteRange[0] != 0) {
            problems.add("ByteRange 가 파일 시작부터 덮지 않습니다");
            return false;
        }
        if (end != fileBytes.length) {
            problems.add("서명 범위 밖에 %d바이트가 남아 있습니다".formatted(fileBytes.length - end));
            return false;
        }
        return true;
    }

    private static Instant signedAt(PDSignature signature) {
        return signature.getSignDate() == null ? null : signature.getSignDate().toInstant();
    }

    private static boolean hasTimestamp(SignerInformation signerInfo) {
        AttributeTable unsigned = signerInfo.getUnsignedAttributes();
        return unsigned != null
                && unsigned.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken) != null;
    }
}
