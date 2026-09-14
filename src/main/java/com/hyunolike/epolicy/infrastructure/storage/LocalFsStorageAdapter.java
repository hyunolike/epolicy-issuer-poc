package com.hyunolike.epolicy.infrastructure.storage;

import com.hyunolike.epolicy.application.port.out.DocumentStoragePort;
import com.hyunolike.epolicy.application.port.out.StoredLocation;
import com.hyunolike.epolicy.domain.document.ContentHash;
import com.hyunolike.epolicy.domain.document.PdfArtifact;
import com.hyunolike.epolicy.domain.document.PolicyDocument;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 로컬 파일시스템 보관소.
 *
 * <p><b>해시를 저장하면서 같이 계산한다</b> — 저장 후 다시 읽어 해시하면 1만 건에서 I/O 가 두 배가
 * 된다. {@link DigestOutputStream} 으로 쓰는 길에 얹는다.
 *
 * <p><b>임시파일에 쓰고 원자적으로 옮긴다</b> — 쓰는 도중 죽으면 반쪽짜리 PDF 가 정상 경로에 남는다.
 * 그 파일은 이력상 존재하고 해시는 기록돼 있으니, 나중에 검증에서야 발견된다. 완성된 뒤 move 하면
 * 그 상태가 아예 생기지 않는다.
 *
 * <p>경로는 {@code KB-2026/0001/KB-2026-0001-0042-r1.pdf} 처럼 두 단계로 쪼갠다. 1만 건을 한
 * 디렉터리에 넣으면 ext4 에서도 디렉터리 조회가 눈에 띄게 느려진다.
 */
public class LocalFsStorageAdapter implements DocumentStoragePort {

    private final Path root;

    public LocalFsStorageAdapter(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("보관 디렉터리를 만들 수 없습니다: " + root, e);
        }
    }

    @Override
    public StoredLocation store(PolicyDocument document, PdfArtifact artifact) throws IOException {
        String relativePath = relativePath(document);
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());

        Path temp = Files.createTempFile(target.getParent(), ".partial-", ".pdf");
        MessageDigest digest = newDigest();
        long size;
        try (InputStream in = artifact.openStream();
             OutputStream fileOut = Files.newOutputStream(temp);
             DigestOutputStream out = new DigestOutputStream(fileOut, digest)) {
            size = in.transferTo(out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        ContentHash fileHash = new ContentHash(ContentHash.ALGORITHM,
                HexFormat.of().formatHex(digest.digest()));
        return new StoredLocation(relativePath, size, fileHash);
    }

    @Override
    public Optional<PdfArtifact> load(String path) throws IOException {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(root.normalize())) {
            // 이력의 경로가 오염돼도 보관 루트 밖을 읽지 않는다.
            throw new IOException("보관 루트를 벗어난 경로입니다: " + path);
        }
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(new FileSystemPdfArtifact(file));
    }

    private static String relativePath(PolicyDocument document) {
        String[] parts = document.contractNo().value().split("-");
        return "%s-%s/%s/%s-r%d.pdf".formatted(
                parts[0], parts[1], parts[2], document.contractNo().value(), document.issueSequence());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ContentHash.ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 지원하지 않는 JVM 입니다", e);
        }
    }
}
