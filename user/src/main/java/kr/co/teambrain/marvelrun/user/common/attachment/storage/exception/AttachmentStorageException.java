package kr.co.teambrain.marvelrun.user.common.attachment.storage.exception;


/**
 * Attachment 저장소 처리 중 발생하는 예외의 공통 상위 예외.
 *
 * 파일 저장, 조회, 삭제 등 Storage 계층에서 발생하는
 * 저장소 관련 예외를 하나의 타입으로 묶기 위해 사용한다.
 *
 * 로컬 파일시스템 또는 Object Storage 구현체의 내부 예외를
 * 상위 계층에 그대로 노출하지 않고 Storage 도메인 예외로 변환하는 역할을 한다.
 */
public class AttachmentStorageException
        extends RuntimeException {

    public AttachmentStorageException(
            String message
    ) {
        super(message);
    }

    public AttachmentStorageException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}