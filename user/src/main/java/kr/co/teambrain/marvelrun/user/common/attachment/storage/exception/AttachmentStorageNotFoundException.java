package kr.co.teambrain.marvelrun.user.common.attachment.storage.exception;

import kr.co.teambrain.marvelrun.common.inheritance_enum.AttachmentStorageType;


/**
 * 요청한 AttachmentStorageType에 대응하는
 * 저장소 구현체를 찾을 수 없을 때 발생하는 예외.
 *
 * 예를 들어 DB의 storageType은 OBJECT_STORAGE인데
 * 현재 애플리케이션에 해당 AttachmentStorage 구현체가 등록되어 있지 않은 경우 사용한다.
 */
public class AttachmentStorageNotFoundException
        extends AttachmentStorageException {

    public AttachmentStorageNotFoundException(
            AttachmentStorageType storageType
    ) {
        super(
                "Attachment storage implementation not found. type="
                        + storageType
        );
    }
}