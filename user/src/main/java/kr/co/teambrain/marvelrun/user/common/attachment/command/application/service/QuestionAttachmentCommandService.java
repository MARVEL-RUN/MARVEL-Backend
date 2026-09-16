package kr.co.teambrain.marvelrun.user.common.attachment.command.application.service;

import kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain.Attachment;
import kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain.QuestionAttachment;
import kr.co.teambrain.marvelrun.user.common.attachment.command.repository.QuestionAttachmentCommandRepository;
import kr.co.teambrain.marvelrun.user.common.attachment.command.valid.AttachmentRelationValidator;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.community.command.application.domain.Question;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuestionAttachmentCommandService {

    private final AttachmentCommandService attachmentCommandService;

    private final QuestionAttachmentCommandRepository
            questionAttachmentCommandRepository;

    private final AttachmentRelationValidator
            attachmentRelationValidator;


    /**
     * Question에 첨부파일을 연결한다.
     *
     * Question 생성 트랜잭션 내부에서만 호출되어야 한다.
     *
     * 실제 binary 저장 및 Attachment 생성은 AttachmentCommandService가 담당하고,
     * 본 서비스는 Question과 Attachment 사이의 관계와 표시 순서만 관리한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<QuestionAttachment> attach(
            Question question,
            MultipartFile[] files
    ) {

        List<MultipartFile> fileList =
                files == null
                        ? List.of()
                        : Arrays.asList(files);


        List<Attachment> attachments =
                attachmentCommandService.upload(
                        fileList
                );


        if (attachments.isEmpty()) {
            return List.of();
        }


        List<QuestionAttachment> questionAttachments =
                new ArrayList<>(
                        attachments.size()
                );


        for (int i = 0;
             i < attachments.size();
             i++) {

            QuestionAttachment questionAttachment =
                    QuestionAttachment.create(
                            question,
                            attachments.get(i),
                            i
                    );

            questionAttachments.add(
                    questionAttachment
            );
        }


        questionAttachmentCommandRepository.saveAll(
                questionAttachments
        );


        return List.copyOf(
                questionAttachments
        );
    }

    /** 이미지 수정을 빙자한 기존 파일 삭제 + 신규파일 저장*/
    @Transactional(
            propagation = Propagation.MANDATORY
    )
    public void patchAttachments(
            Question question,
            List<String> deletedAttachmentIds,
            MultipartFile[] newFiles
    ) {

        List<String> deleteIds =
                deletedAttachmentIds == null
                        ? List.of()
                        : deletedAttachmentIds;


        List<MultipartFile> newFileList =
                newFiles == null
                        ? List.of()
                        : Arrays.asList(newFiles);


        List<QuestionAttachment> existing =
                questionAttachmentCommandRepository
                        .findAllByQuestion_IdOrderByDisplayOrderAsc(
                                question.getId()
                        );


        List<String> existingAttachmentIds =
                existing.stream()
                        .map(
                                relation ->
                                        relation
                                                .getAttachment()
                                                .getId()
                        )
                        .toList();


        attachmentRelationValidator.validatePatch(
                existingAttachmentIds,
                deleteIds,
                newFileList.size()
        );


        Set<String> deleteIdSet =
                new HashSet<>(
                        deleteIds
                );


        List<QuestionAttachment> deleteRelations =
                existing.stream()
                        .filter(
                                relation ->
                                        deleteIdSet.contains(
                                                relation
                                                        .getAttachment()
                                                        .getId()
                                        )
                        )
                        .toList();


        List<Attachment> deleteAttachments =
                deleteRelations.stream()
                        .map(
                                QuestionAttachment::getAttachment
                        )
                        .toList();


        /*
         * 관계 row부터 제거.
         */
        questionAttachmentCommandRepository
                .deleteAll(
                        deleteRelations
                );


        /*
         * Attachment metadata 삭제.
         *
         * 실제 binary 삭제는 DB commit 성공 후 실행된다.
         */
        attachmentCommandService.deleteAll(
                deleteAttachments
        );


        /*
         * 살아남은 기존 관계.
         */
        List<QuestionAttachment> survivors =
                existing.stream()
                        .filter(
                                relation ->
                                        !deleteIdSet.contains(
                                                relation
                                                        .getAttachment()
                                                        .getId()
                                        )
                        )
                        .toList();


        /*
         * displayOrder 재정렬.
         */
        for (int i = 0;
             i < survivors.size();
             i++) {

            survivors.get(i)
                    .changeDisplayOrder(i);
        }


        /*
         * 신규 파일 저장.
         *
         * 신규 binary는 transaction rollback 시
         * AttachmentCommandService의 rollback cleanup으로 제거된다.
         */
        List<Attachment> uploadedAttachments =
                attachmentCommandService.upload(
                        newFileList
                );


        List<QuestionAttachment> newRelations =
                new ArrayList<>(
                        uploadedAttachments.size()
                );


        int startOrder =
                survivors.size();


        for (int i = 0;
             i < uploadedAttachments.size();
             i++) {

            QuestionAttachment relation =
                    QuestionAttachment.create(
                            question,
                            uploadedAttachments.get(i),
                            startOrder + i
                    );

            newRelations.add(
                    relation
            );
        }


        questionAttachmentCommandRepository.saveAll(
                newRelations
        );
    }

    @Transactional(
            propagation = Propagation.MANDATORY
    )
    public void deleteAllByQuestion(
            Question question
    ) {

        List<QuestionAttachment> relations =
                questionAttachmentCommandRepository
                        .findAllByQuestion_IdOrderByDisplayOrderAsc(
                                question.getId()
                        );


        if (relations.isEmpty()) {
            return;
        }


        List<Attachment> attachments =
                relations.stream()
                        .map(
                                QuestionAttachment::getAttachment
                        )
                        .toList();


        /*
         * Attachment FK를 보유한 관계 row부터 제거.
         */
        questionAttachmentCommandRepository
                .deleteAll(
                        relations
                );


        questionAttachmentCommandRepository
                .flush();


        /*
         * Attachment metadata 삭제.
         *
         * physical binary는 commit 후 삭제.
         */
        attachmentCommandService.deleteAll(
                attachments
        );
    }
}