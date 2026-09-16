package kr.co.teambrain.marvelrun.user.common.attachment.command.valid;

import kr.co.teambrain.marvelrun.user.common.attachment.storage.upload.AttachmentUploadProperties;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AttachmentRelationValidator {

    private final AttachmentUploadProperties properties;


    public void validatePatch(
            Collection<String> existingAttachmentIds,
            List<String> deletedAttachmentIds,
            int newFileCount
    ) {

        List<String> deleteIds =
                deletedAttachmentIds == null
                        ? List.of()
                        : deletedAttachmentIds;


        Set<String> requestedDeleteIds =
                new HashSet<>(
                        deleteIds
                );


        /*
         * 동일 attachmentId가 두 번 이상 들어온 경우.
         */
        if (requestedDeleteIds.size()
                != deleteIds.size()) {

            throw new CustomException(
                    ErrorCode.INVALID_ATTACHMENT_DELETE_TARGET
            );
        }


        Set<String> existingIds =
                new HashSet<>(
                        existingAttachmentIds
                );


        /*
         * 다른 Question/Answer의 attachmentId 또는
         * 존재하지 않는 attachmentId 삭제 요청.
         */
        if (!existingIds.containsAll(
                requestedDeleteIds
        )) {

            throw new CustomException(
                    ErrorCode.INVALID_ATTACHMENT_DELETE_TARGET
            );
        }


        int finalCount =
                existingIds.size()
                        - requestedDeleteIds.size()
                        + newFileCount;


        if (finalCount < 0) {

            throw new CustomException(
                    ErrorCode.INVALID_ATTACHMENT_DELETE_TARGET
            );
        }


        if (finalCount
                > properties.getMaxCount()) {

            throw new CustomException(
                    ErrorCode.ATTACHMENT_COUNT_EXCEEDED
            );
        }
    }
}