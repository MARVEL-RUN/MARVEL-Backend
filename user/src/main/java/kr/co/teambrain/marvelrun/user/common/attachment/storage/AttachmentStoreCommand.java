package kr.co.teambrain.marvelrun.user.common.attachment.storage;

import java.io.InputStream;

/**
 * AttachmentStorage에 파일 저장을 요청할 때 사용하는 입력 데이터 객체.
 *
 * HTTP 계층의 MultipartFile에 Storage 계층이 직접 의존하지 않도록
 * 실제 저장에 필요한 정보만 전달한다.
 *
 * storageKey      : 저장소 내부의 논리적 파일 식별 경로
 * inputStream     : 저장할 실제 바이너리 데이터
 * fileSizeBytes   : 파일 크기(byte)
 * contentType     : MIME Type
 *
 * originalName과 같은 업무용 메타데이터는 Attachment 엔티티의 책임이며
 * 실제 Storage 저장에는 포함하지 않는다.
 */

public record AttachmentStoreCommand(

        String storageKey,

        InputStream inputStream,

        long fileSizeBytes,

        String contentType
) {
}