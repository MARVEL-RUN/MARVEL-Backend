package kr.co.teambrain.marvelrun.user.common.attachment.storage;


import jakarta.annotation.PostConstruct;
import kr.co.teambrain.marvelrun.common.inheritance_enum.AttachmentStorageType;
import kr.co.teambrain.marvelrun.user.common.attachment.storage.exception.AttachmentFileNotFoundException;
import kr.co.teambrain.marvelrun.user.common.attachment.storage.exception.AttachmentInvalidStorageKeyException;
import kr.co.teambrain.marvelrun.user.common.attachment.storage.exception.AttachmentStorageException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.UUID;

/**
 * Local filesystem을 사용하는 AttachmentStorage 구현체.
 *
 * AttachmentStorageProperties.rootPath 하위에
 * storageKey를 상대경로로 결합하여 실제 파일을 저장한다.
 *
 * 파일은 Docker container 내부 ephemeral filesystem이 아니라
 * Host persistent directory와 volume mount된 경로에 저장하는 것을 전제로 한다.
 *
 * 저장 중 장애로 불완전한 파일이 노출되지 않도록
 * 임시 파일에 먼저 기록한 뒤 최종 파일로 이동한다.
 */
@Component
public class LocalAttachmentStorage
        implements AttachmentStorage {

    private final Path rootPath;


    public LocalAttachmentStorage(
            AttachmentStorageProperties properties
    ) {

        if (properties.getRootPath() == null
                || properties.getRootPath().isBlank()) {

            throw new IllegalArgumentException(
                    "attachment.storage.local.root-path must not be blank."
            );
        }

        this.rootPath =
                Paths.get(
                                properties.getRootPath()
                        )
                        .toAbsolutePath()
                        .normalize();
    }


    /**
     * 애플리케이션 시작 시 Local Storage root directory를 확인하고,
     * 존재하지 않는 경우 생성한다.
     */
    @PostConstruct
    void initialize() {

        try {

            Files.createDirectories(rootPath);

        } catch (IOException e) {

            throw new AttachmentStorageException(
                    "Failed to initialize local attachment storage. rootPath="
                            + rootPath,
                    e
            );
        }
    }


    @Override
    public AttachmentStorageType storageType() {

        return AttachmentStorageType.LOCAL;
    }


    @Override
    public void store(
            AttachmentStoreCommand command
    ) {

        Path target =
                resolveSafePath(
                        command.storageKey()
                );

        Path parent =
                target.getParent();

        if (parent == null) {

            throw new AttachmentInvalidStorageKeyException(
                    command.storageKey()
            );
        }


        Path temporaryFile = null;

        try {

            Files.createDirectories(parent);

            /*
             * 이미 동일 storageKey가 존재한다면 덮어쓰지 않는다.
             *
             * UUID 기반 key를 사용하므로 정상 상황에서는
             * 충돌하면 안 된다.
             */
            if (Files.exists(target)) {

                throw new AttachmentStorageException(
                        "Attachment file already exists. storageKey="
                                + command.storageKey()
                );
            }


            /*
             * 최종 파일을 바로 쓰지 않고
             * 같은 directory에 temporary file을 생성한다.
             */
            temporaryFile =
                    parent.resolve(
                            ".uploading-"
                                    + UUID.randomUUID()
                    );


            long copiedBytes =
                    Files.copy(
                            command.inputStream(),
                            temporaryFile
                    );


            /*
             * 전달받은 예상 크기와 실제 저장된 크기가 다르면
             * 불완전한 업로드로 간주한다.
             */
            if (copiedBytes
                    != command.fileSizeBytes()) {

                throw new AttachmentStorageException(
                        "Attachment file size mismatch. expected="
                                + command.fileSizeBytes()
                                + ", actual="
                                + copiedBytes
                );
            }


            moveToTarget(
                    temporaryFile,
                    target
            );

            /*
             * 최종 파일로 이동 완료되었으므로
             * finally에서 temporary file을 삭제하지 않아도 됨.
             */
            temporaryFile = null;

        } catch (AttachmentStorageException e) {

            throw e;

        } catch (IOException e) {

            throw new AttachmentStorageException(
                    "Failed to store attachment. storageKey="
                            + command.storageKey(),
                    e
            );

        } finally {

            deleteTemporaryFileQuietly(
                    temporaryFile
            );
        }
    }


    @Override
    public InputStream openStream(
            String storageKey
    ) {

        Path target =
                resolveSafePath(storageKey);

        if (!Files.isRegularFile(target)) {

            throw new AttachmentFileNotFoundException(
                    storageKey
            );
        }

        try {

            return Files.newInputStream(
                    target,
                    StandardOpenOption.READ
            );

        } catch (IOException e) {

            throw new AttachmentStorageException(
                    "Failed to open attachment stream. storageKey="
                            + storageKey,
                    e
            );
        }
    }


    @Override
    public boolean exists(
            String storageKey
    ) {

        Path target =
                resolveSafePath(storageKey);

        return Files.isRegularFile(target);
    }


    @Override
    public void delete(
            String storageKey
    ) {

        Path target =
                resolveSafePath(storageKey);

        try {

            /*
             * 삭제는 멱등적으로 처리한다.
             *
             * 이미 실제 파일이 없는 상황에서 재시도되더라도
             * 추가 오류를 발생시키지 않는다.
             */
            Files.deleteIfExists(target);

        } catch (IOException e) {

            throw new AttachmentStorageException(
                    "Failed to delete attachment. storageKey="
                            + storageKey,
                    e
            );
        }
    }


    /**
     * storageKey를 Local filesystem의 실제 Path로 변환한다.
     *
     * normalize 이후에도 반드시 rootPath 하위인지 검증하여
     * "../" 등을 이용한 path traversal을 차단한다.
     */
    private Path resolveSafePath(
            String storageKey
    ) {


        if (storageKey == null
                || storageKey.isBlank()) {

            throw new AttachmentInvalidStorageKeyException(
                    storageKey
            );
        }

        if (storageKey.contains("\\")) {

            throw new AttachmentInvalidStorageKeyException(
                    storageKey
            );
        }

        if (storageKey.startsWith("/")) {

            throw new AttachmentInvalidStorageKeyException(
                    storageKey
            );
        }


        Path relativePath;

        try {

            relativePath =
                    Paths.get(storageKey);

        } catch (InvalidPathException e) {

            throw new AttachmentInvalidStorageKeyException(
                    storageKey
            );
        }


        /*
         * 절대 경로 입력 금지.
         *
         * ex)
         * /etc/passwd
         * C:\...
         */
        if (relativePath.isAbsolute()) {

            throw new AttachmentInvalidStorageKeyException(
                    storageKey
            );
        }


        Path resolved =
                rootPath
                        .resolve(relativePath)
                        .normalize();


        /*
         * ex)
         *
         * storageKey:
         * ../../etc/passwd
         *
         * normalize 결과가 rootPath 밖으로 나가면 거부.
         */
        if (!resolved.startsWith(rootPath)) {

            throw new AttachmentInvalidStorageKeyException(
                    storageKey
            );
        }

        return resolved;
    }


    /**
     * 가능하면 atomic move로 임시 파일을 최종 파일로 교체한다.
     *
     * 파일시스템이 atomic move를 지원하지 않는 경우에는
     * 일반 move로 fallback한다.
     */
    private void moveToTarget(
            Path temporaryFile,
            Path target
    ) throws IOException {

        try {

            Files.move(
                    temporaryFile,
                    target,
                    StandardCopyOption.ATOMIC_MOVE
            );

        } catch (AtomicMoveNotSupportedException e) {

            Files.move(
                    temporaryFile,
                    target
            );
        }
    }


    /**
     * 저장 실패 시 남은 임시 파일을 정리한다.
     *
     * cleanup 자체의 실패 때문에 원래 발생한 예외를
     * 덮어쓰지는 않는다.
     */
    private void deleteTemporaryFileQuietly(
            Path temporaryFile
    ) {

        if (temporaryFile == null) {
            return;
        }

        try {

            Files.deleteIfExists(
                    temporaryFile
            );

        } catch (IOException ignored) {
        }
    }
}