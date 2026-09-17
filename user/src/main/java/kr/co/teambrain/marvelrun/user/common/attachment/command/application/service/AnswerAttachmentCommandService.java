package kr.co.teambrain.marvelrun.user.common.attachment.command.application.service;

import kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain.AnswerAttachment;
import kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain.Attachment;
import kr.co.teambrain.marvelrun.user.common.attachment.command.repository.AnswerAttachmentCommandRepository;
import kr.co.teambrain.marvelrun.user.common.attachment.command.valid.AttachmentRelationValidator;
import kr.co.teambrain.marvelrun.user.community.command.application.domain.Answer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AnswerAttachmentCommandService {

    private final AttachmentCommandService
            attachmentCommandService;

    private final AnswerAttachmentCommandRepository
            answerAttachmentCommandRepository;

    private final AttachmentRelationValidator
            attachmentRelationValidator;


    @Transactional(
            propagation = Propagation.MANDATORY
    )
    public List<AnswerAttachment> attach(
            Answer answer,
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


        List<AnswerAttachment> relations =
                new ArrayList<>(
                        attachments.size()
                );


        for (int i = 0;
             i < attachments.size();
             i++) {

            AnswerAttachment relation =
                    AnswerAttachment.create(
                            answer,
                            attachments.get(i),
                            i
                    );

            relations.add(
                    relation
            );
        }


        answerAttachmentCommandRepository.saveAll(
                relations
        );


        return List.copyOf(
                relations
        );
    }


    @Transactional(
            propagation = Propagation.MANDATORY
    )
    public void patchAttachments(
            Answer answer,
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


        List<AnswerAttachment> existing =
                answerAttachmentCommandRepository
                        .findAllByAnswer_IdOrderByDisplayOrderAsc(
                                answer.getId()
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


        List<AnswerAttachment> deleteRelations =
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
                                AnswerAttachment::getAttachment
                        )
                        .toList();


        if (!deleteRelations.isEmpty()) {

            answerAttachmentCommandRepository.deleteAll(
                    deleteRelations
            );


            /*
             * attachment FK를 가지고 있는 relation row를
             * 먼저 DB에 반영한다.
             */
            answerAttachmentCommandRepository.flush();


            attachmentCommandService.deleteAll(
                    deleteAttachments
            );
        }


        List<AnswerAttachment> survivors =
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


        for (int i = 0;
             i < survivors.size();
             i++) {

            survivors.get(i)
                    .changeDisplayOrder(i);
        }


        List<Attachment> uploadedAttachments =
                attachmentCommandService.upload(
                        newFileList
                );


        if (uploadedAttachments.isEmpty()) {
            return;
        }


        List<AnswerAttachment> newRelations =
                new ArrayList<>(
                        uploadedAttachments.size()
                );


        int startOrder =
                survivors.size();


        for (int i = 0;
             i < uploadedAttachments.size();
             i++) {

            AnswerAttachment relation =
                    AnswerAttachment.create(
                            answer,
                            uploadedAttachments.get(i),
                            startOrder + i
                    );

            newRelations.add(
                    relation
            );
        }


        answerAttachmentCommandRepository.saveAll(
                newRelations
        );
    }


    @Transactional(
            propagation = Propagation.MANDATORY
    )
    public void deleteAllByAnswer(
            Answer answer
    ) {

        List<AnswerAttachment> relations =
                answerAttachmentCommandRepository
                        .findAllByAnswer_IdOrderByDisplayOrderAsc(
                                answer.getId()
                        );


        if (relations.isEmpty()) {
            return;
        }


        List<Attachment> attachments =
                relations.stream()
                        .map(
                                AnswerAttachment::getAttachment
                        )
                        .toList();


        answerAttachmentCommandRepository.deleteAll(
                relations
        );


        answerAttachmentCommandRepository.flush();


        attachmentCommandService.deleteAll(
                attachments
        );
    }
}