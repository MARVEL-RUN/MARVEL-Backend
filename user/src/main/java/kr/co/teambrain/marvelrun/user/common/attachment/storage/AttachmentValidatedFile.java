package kr.co.teambrain.marvelrun.user.common.attachment.storage;

import kr.co.teambrain.marvelrun.user.common.attachment.storage.upload.AttachmentFileType;
import org.springframework.web.multipart.MultipartFile;

public record AttachmentValidatedFile(

        MultipartFile multipartFile,

        String originalName,

        AttachmentFileType fileType,

        long fileSizeBytes
) {
}