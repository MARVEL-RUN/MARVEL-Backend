package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.AttachmentStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.AttachmentStorageType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Getter
@MappedSuperclass
@SuperBuilder
@NoArgsConstructor(access = PROTECTED)
public abstract class AttachmentBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "id",
            nullable = false,
            length = 40
    )
    protected String id;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "storage_type",
            nullable = false,
            length = 30
    )
    protected AttachmentStorageType storageType;

    @Column(
            name = "storage_key",
            nullable = false,
            unique = true,
            length = 500
    )
    protected String storageKey;

    @Column(
            name = "original_name",
            nullable = false,
            length = 255
    )
    protected String originalName;

    @Column(
            name = "content_type",
            nullable = false,
            length = 100
    )
    protected String contentType;

    @Column(
            name = "file_size_bytes",
            nullable = false
    )
    protected Long fileSizeBytes;

    @Column(
            name = "checksum_sha256",
            length = 64
    )
    protected String checksumSha256;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    protected AttachmentStatus status;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    protected LocalDateTime createdAt;


    protected void activate() {
        this.status = AttachmentStatus.ACTIVE;
    }

    protected void updateStorageType(
            AttachmentStorageType storageType
    ) {
        this.storageType = storageType;
    }
}