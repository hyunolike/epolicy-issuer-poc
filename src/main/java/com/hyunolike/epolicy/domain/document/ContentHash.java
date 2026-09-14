package com.hyunolike.epolicy.domain.document;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 해시.
 *
 * <p>같은 타입을 두 군데에서 쓴다.
 * <ul>
 *   <li>contentHash — 서명 직전 PDF. 재발급 멱등성 판단 기준</li>
 *   <li>fileHash — 서명 완료 파일. 보관 무결성 검증 기준</li>
 * </ul>
 * 서명에는 타임스탬프가 들어가 같은 내용이라도 파일 바이트가 매번 달라지므로, 멱등성을 fileHash 로
 * 판단하면 영원히 "다른 문서"가 된다. 자세한 근거는 docs/DESIGN.md 설계 결정 ③.
 */
public record ContentHash(String algorithm, String hex) {

    public static final String ALGORITHM = "SHA-256";

    public ContentHash {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException("해시 값은 비어 있을 수 없습니다");
        }
    }

    public static ContentHash of(byte[] bytes) {
        return new ContentHash(ALGORITHM, HexFormat.of().formatHex(newDigest().digest(bytes)));
    }

    /** 스트리밍 해시. 1만 건 배치에서 PDF 전체를 힙에 올리지 않기 위한 경로다. */
    public static ContentHash of(InputStream in) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[8192];
        try (DigestInputStream dis = new DigestInputStream(in, digest)) {
            while (dis.read(buffer) != -1) {
                // DigestInputStream 이 읽는 동안 해시를 갱신한다.
            }
        }
        return new ContentHash(ALGORITHM, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 지원하지 않는 JVM 입니다", e);
        }
    }

    public String shortHex() {
        return hex.substring(0, Math.min(12, hex.length()));
    }

    @Override
    public String toString() {
        return algorithm + ":" + hex;
    }
}
