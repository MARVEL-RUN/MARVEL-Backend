package kr.co.teambrain.marvelrun.user.common.attachment.storage.exception;

/**
 * storageKey가 Local Storage root 외부를 가리키거나
 * 허용되지 않은 경로 형식을 포함할 때 발생한다.
 *
 * "../", absolute path 등의 path traversal 공격을 방지하기 위해 사용한다.
 */
public class AttachmentInvalidStorageKeyException
        extends AttachmentStorageException {

    public AttachmentInvalidStorageKeyException(
            String storageKey
    ) {
        super(
                "Invalid attachment storage key. storageKey="
                        + storageKey
        );
    }
}