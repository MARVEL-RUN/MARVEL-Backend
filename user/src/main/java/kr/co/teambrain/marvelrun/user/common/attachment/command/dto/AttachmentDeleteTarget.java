package kr.co.teambrain.marvelrun.user.common.attachment.command.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.AttachmentStorageType;

public record AttachmentDeleteTarget(

        AttachmentStorageType storageType,

        String storageKey
) {
}