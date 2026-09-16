package kr.co.teambrain.marvelrun.user.common.attachment.storage;


import kr.co.teambrain.marvelrun.common.inheritance_enum.AttachmentStorageType;

import java.io.InputStream;

/**
 * 첨부파일의 실제 바이너리 저장소를 추상화한 인터페이스.
 *
 * 로컬 파일시스템, S3 계열 Object Storage 등 구체 저장 방식과
 * 상위 도메인/애플리케이션 로직을 분리하기 위한 계약이다.
 *
 * Attachment의 storageType과 storageKey를 기준으로
 * 저장, 내부 스트림 조회, 존재 여부 확인, 삭제 기능을 제공한다.
 *
 * 사용자에게 파일을 어떤 방식으로 전달할지
 * (예: X-Accel-Redirect, Presigned URL)는 이 인터페이스의 책임이 아니다.
 */
public interface AttachmentStorage {

    /**
     * 이 구현체가 담당하는 저장소 타입.
     */
    AttachmentStorageType storageType();


    /**
     * binary 저장.
     */
    void store(
            AttachmentStoreCommand command
    );


    /**
     * 내부 처리용 binary stream 조회.
     *
     * 사용자 이미지 응답 용도로 직접 사용하는 것을 기본으로 하지 않는다.
     * migration / checksum / 복구 등의 내부 작업용.
     */
    InputStream openStream(
            String storageKey
    );


    /**
     * 실제 binary 존재 여부.
     */
    boolean exists(
            String storageKey
    );


    /**
     * 실제 binary 삭제.
     */
    void delete(
            String storageKey
    );
}