package kr.co.teambrain.marvelrun.user.common.attachment.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.AttachmentStorageType;
import kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain.Attachment;
import kr.co.teambrain.marvelrun.user.common.attachment.command.dto.AttachmentDeleteTarget;
import kr.co.teambrain.marvelrun.user.common.attachment.storage.*;
import kr.co.teambrain.marvelrun.user.common.attachment.storage.exception.AttachmentStorageException;
import kr.co.teambrain.marvelrun.user.common.attachment.storage.upload.AttachmentFileValidator;
import kr.co.teambrain.marvelrun.user.common.attachment.storage.upload.AttachmentStoragePolicyProperties;
import kr.co.teambrain.marvelrun.user.common.attachment.command.repository.AttachmentCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentCommandService {

    private final AttachmentFileValidator attachmentFileValidator;

    private final AttachmentStorageKeyGenerator storageKeyGenerator;

    private final AttachmentStorageRegistry storageRegistry;

    private final AttachmentStoragePolicyProperties storagePolicyProperties;

    private final AttachmentCommandRepository attachmentCommandRepository;


    @Transactional
    public List<Attachment> upload(
            List<MultipartFile> files
    ) {

        List<AttachmentValidatedFile> validatedFiles =
                attachmentFileValidator.validate(files);

        if (validatedFiles.isEmpty()) {
            return List.of();
        }


        AttachmentStorageType storageType =
                storagePolicyProperties
                        .getDefaultType();

        AttachmentStorage storage =
                storageRegistry.get(
                        storageType
                );


        /*
         * DB Transaction rollback 발생 시
         * 이미 저장된 실제 파일을 제거하기 위한 목록.
         */
        List<String> storedKeys =
                new ArrayList<>();

        registerRollbackCleanup(
                storage,
                storedKeys
        );


        List<Attachment> attachments =
                new ArrayList<>(
                        validatedFiles.size()
                );


        for (AttachmentValidatedFile file
                : validatedFiles) {

            Attachment attachment =
                    uploadOne(
                            file,
                            storageType,
                            storage,
                            storedKeys
                    );

            attachments.add(
                    attachment
            );
        }


        /*
         * SQL 제약조건 오류 등을 가능한 한
         * method 내부에서 확인한다.
         */
        attachmentCommandRepository.flush();


        return List.copyOf(
                attachments
        );
    }

    @Transactional(
            propagation = Propagation.MANDATORY
    )
    public void deleteAll(
            List<Attachment> attachments
    ) {

        if (attachments == null
                || attachments.isEmpty()) {

            return;
        }


        List<AttachmentDeleteTarget> deleteTargets =
                attachments.stream()
                        .map(
                                attachment ->
                                        new AttachmentDeleteTarget(
                                                attachment.getStorageType(),
                                                attachment.getStorageKey()
                                        )
                        )
                        .toList();


        attachmentCommandRepository.deleteAll(
                attachments
        );


        registerAfterCommitDelete(
                deleteTargets
        );
    }


    private Attachment uploadOne(
            AttachmentValidatedFile file,
            AttachmentStorageType storageType,
            AttachmentStorage storage,
            List<String> storedKeys
    ) {

        String storageKey =
                storageKeyGenerator.generate(
                        file.fileType()
                                .getExtension()
                );


        Attachment attachment =
                Attachment.createPending(
                        storageType,
                        storageKey,
                        file.originalName(),
                        file.fileType()
                                .getContentType(),
                        file.fileSizeBytes()
                );


        /*
         * PENDING 상태로 영속화.
         *
         * 실제 DB INSERT 시점은 transaction/flush에 따라
         * 뒤로 밀릴 수 있다.
         */
        attachmentCommandRepository.save(
                attachment
        );


        /*
         * 저장 실패 또는 이후 DB rollback 시
         * cleanup 후보에 포함한다.
         *
         * delete()는 멱등적으로 구현했으므로
         * 실제 파일이 생기기 전이어도 문제없다.
         */
        storedKeys.add(
                storageKey
        );


        String checksum =
                storeFile(
                        file,
                        storageKey,
                        storage
                );


        attachment.completeUpload(
                checksum
        );


        return attachment;
    }


    private String storeFile(
            AttachmentValidatedFile file,
            String storageKey,
            AttachmentStorage storage
    ) {

        MessageDigest messageDigest =
                createSha256Digest();


        try (
                InputStream rawInputStream =
                        file.multipartFile()
                                .getInputStream();

                DigestInputStream digestInputStream =
                        new DigestInputStream(
                                rawInputStream,
                                messageDigest
                        )
        ) {

            storage.store(
                    new AttachmentStoreCommand(
                            storageKey,
                            digestInputStream,
                            file.fileSizeBytes(),
                            file.fileType()
                                    .getContentType()
                    )
            );


        } catch (IOException e) {

            throw new AttachmentStorageException(
                    "Failed to read attachment upload stream.",
                    e
            );
        }


        return HexFormat.of()
                .formatHex(
                        messageDigest.digest()
                );
    }


    private MessageDigest createSha256Digest() {

        try {

            return MessageDigest.getInstance(
                    "SHA-256"
            );

        } catch (NoSuchAlgorithmException e) {

            /*
             * SHA-256은 표준 JDK 구현에서 제공되므로
             * 정상 환경에서는 발생하면 안 된다.
             */
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available.",
                    e
            );
        }
    }


    /**
     * DB Transaction이 rollback 되었는데
     * filesystem 파일만 남는 orphan 상태를 방지한다.
     */
    private void registerRollbackCleanup(
            AttachmentStorage storage,
            List<String> storedKeys
    ) {

        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {

            throw new IllegalStateException(
                    "Attachment upload requires active transaction."
            );
        }


        TransactionSynchronizationManager
                .registerSynchronization(
                        new TransactionSynchronization() {

                            @Override
                            public void afterCompletion(
                                    int status
                            ) {

                                if (status
                                        != STATUS_ROLLED_BACK) {

                                    return;
                                }

                                cleanupFiles(
                                        storage,
                                        storedKeys
                                );
                            }
                        }
                );
    }


    private void cleanupFiles(
            AttachmentStorage storage,
            List<String> storedKeys
    ) {

        for (String storageKey
                : storedKeys) {

            try {

                storage.delete(
                        storageKey
                );

            } catch (Exception e) {

                /*
                 * cleanup 실패가 원래 transaction 예외를
                 * 덮어쓰면 안 된다.
                 *
                 * 추후 orphan cleanup 대상이므로 반드시 로그를 남긴다.
                 */
                log.error(
                        "Attachment rollback cleanup failed. storageKey={}",
                        storageKey,
                        e
                );
            }
        }
    }

    /*
    *
    * 파일 삭제
    *
    * */

    private void registerAfterCommitDelete(
            List<AttachmentDeleteTarget> targets
    ) {

        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {

            throw new IllegalStateException(
                    "Attachment delete requires active transaction."
            );
        }


        TransactionSynchronizationManager
                .registerSynchronization(
                        new TransactionSynchronization() {

                            @Override
                            public void afterCommit() {

                                deleteStoredFiles(
                                        targets
                                );
                            }
                        }
                );
    }

    private void deleteStoredFiles(
            List<AttachmentDeleteTarget> targets
    ) {

        for (AttachmentDeleteTarget target
                : targets) {

            try {

                AttachmentStorage storage =
                        storageRegistry.get(
                                target.storageType()
                        );

                storage.delete(
                        target.storageKey()
                );

            } catch (Exception e) {

                log.error(
                        "Attachment storage delete failed after commit. " +
                                "storageType={}, storageKey={}",
                        target.storageType(),
                        target.storageKey(),
                        e
                );
            }
        }
    }
}