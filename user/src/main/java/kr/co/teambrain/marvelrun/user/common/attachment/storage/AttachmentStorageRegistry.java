package kr.co.teambrain.marvelrun.user.common.attachment.storage;

import kr.co.teambrain.marvelrun.common.inheritance_enum.AttachmentStorageType;
import kr.co.teambrain.marvelrun.user.common.attachment.storage.exception.AttachmentStorageNotFoundException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;


/**
 * AttachmentStorageType별 실제 AttachmentStorage 구현체를 관리하는 Registry.
 *
 * Spring에 등록된 AttachmentStorage 구현체들을 수집하여
 * LOCAL, OBJECT_STORAGE 등의 storageType에 맞는 구현체를 반환한다.
 *
 * 상위 서비스가 LocalAttachmentStorage 또는 ObjectStorageAttachmentStorage를
 * 직접 의존하지 않도록 하며,
 * 로컬 저장소와 Object Storage가 동시에 존재하는 마이그레이션 상황도 지원한다.
 */
@Component
public class AttachmentStorageRegistry {

    private final Map<
            AttachmentStorageType,
            AttachmentStorage
            > storageMap;


    public AttachmentStorageRegistry(
            List<AttachmentStorage> storageList
    ) {

        Map<
                AttachmentStorageType,
                AttachmentStorage
                > map =
                new EnumMap<>(AttachmentStorageType.class);

        for (AttachmentStorage storage : storageList) {

            AttachmentStorage duplicated =
                    map.put(
                            storage.storageType(),
                            storage
                    );

            if (duplicated != null) {
                throw new IllegalStateException(
                        "AttachmentStorage duplicated. type="
                                + storage.storageType()
                );
            }
        }

        this.storageMap = Map.copyOf(map);
    }


    public AttachmentStorage get(
            AttachmentStorageType storageType
    ) {

        AttachmentStorage storage =
                storageMap.get(storageType);

        if (storage == null) {
            throw new AttachmentStorageNotFoundException(
                    storageType
            );
        }

        return storage;
    }
}