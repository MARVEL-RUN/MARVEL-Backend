package kr.co.teambrain.marvelrun.user.common.attachment.storage.exception;

public class AttachmentFileValidationException
        extends RuntimeException {

    public AttachmentFileValidationException(
            String message
    ) {
        super(message);
    }

    public AttachmentFileValidationException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}