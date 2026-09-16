package kr.co.teambrain.marvelrun.user.common.attachment.storage.upload;

import kr.co.teambrain.marvelrun.user.common.attachment.storage.exception.AttachmentFileValidationException;

import java.util.Arrays;

public enum AttachmentFileType {

    JPEG(
            "image/jpeg",
            "jpg"
    ),

    PNG(
            "image/png",
            "png"
    );


    private final String contentType;
    private final String extension;


    AttachmentFileType(
            String contentType,
            String extension
    ) {
        this.contentType = contentType;
        this.extension = extension;
    }


    public String getContentType() {
        return contentType;
    }


    public String getExtension() {
        return extension;
    }


    public static AttachmentFileType detect(
            byte[] header
    ) {

        if (isJpeg(header)) {
            return JPEG;
        }

        if (isPng(header)) {
            return PNG;
        }

        throw new AttachmentFileValidationException(
                "Unsupported image format."
        );
    }


    private static boolean isJpeg(
            byte[] header
    ) {

        return header.length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF;
    }


    private static boolean isPng(
            byte[] header
    ) {

        if (header.length < 8) {
            return false;
        }

        byte[] pngSignature = {
                (byte) 0x89,
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A
        };

        return Arrays.equals(
                Arrays.copyOf(header, 8),
                pngSignature
        );
    }
}