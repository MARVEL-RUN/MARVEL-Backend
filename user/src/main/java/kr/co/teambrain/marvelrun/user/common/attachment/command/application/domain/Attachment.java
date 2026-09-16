package kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.AttachmentBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.AttachmentStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.AttachmentStorageType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "attachment")
@SuperBuilder
@NoArgsConstructor(access = PROTECTED)
public class Attachment extends AttachmentBase {

    public static Attachment createPending(
            AttachmentStorageType storageType,
            String storageKey,
            String originalName,
            String contentType,
            long fileSizeBytes
    ) {
        return Attachment.builder()
                .storageType(storageType)
                .storageKey(storageKey)
                .originalName(originalName)
                .contentType(contentType)
                .fileSizeBytes(fileSizeBytes)
                .checksumSha256(null)
                .status(AttachmentStatus.PENDING)
                .build();
    }

    public void completeUpload(
            String checksumSha256
    ) {
        this.checksumSha256 =
                checksumSha256;

        activate();
    }
    public void migrateStorage(
            AttachmentStorageType storageType
    ) {
        updateStorageType(storageType);
    }
}