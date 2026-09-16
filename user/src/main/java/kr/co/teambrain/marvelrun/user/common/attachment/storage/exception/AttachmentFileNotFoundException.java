package kr.co.teambrain.marvelrun.user.common.attachment.storage.exception;

/**
 * DB에는 Attachment metadata가 존재하지만
 * 실제 Storage에서 해당 binary를 찾을 수 없는 경우 발생한다.
 *
 * DB/File 불일치, 파일 유실, 잘못된 storageKey 등을 구분하기 위한
 * Storage 계층 전용 예외이다.
 */
public class AttachmentFileNotFoundException
        extends AttachmentStorageException {

    public AttachmentFileNotFoundException(
            String storageKey
    ) {
        super(
                "Attachment file not found. storageKey="
                        + storageKey
        );
    }
}