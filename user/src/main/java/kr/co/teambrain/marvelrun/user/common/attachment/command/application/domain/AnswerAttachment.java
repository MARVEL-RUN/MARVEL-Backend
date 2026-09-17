package kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.entity.AnswerAttachmentBase;
import kr.co.teambrain.marvelrun.user.community.command.application.domain.Answer;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(
        name = "answer_attachment",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_answer_attachment_attachment",
                        columnNames = "attachment_id"
                )
        },
        indexes = {
                @Index(
                        name = "idx_answer_attachment_answer_order",
                        columnList = "answer_id, display_order"
                )
        }
)
@SuperBuilder
@NoArgsConstructor(access = PROTECTED)
public class AnswerAttachment
        extends AnswerAttachmentBase<Answer, Attachment> {

    public static AnswerAttachment create(
            Answer answer,
            Attachment attachment,
            int displayOrder
    ) {
        return AnswerAttachment.builder()
                .answer(answer)
                .attachment(attachment)
                .displayOrder(displayOrder)
                .build();
    }

    public void changeDisplayOrder(
            int displayOrder
    ) {
        updateDisplayOrder(
                displayOrder
        );
    }
}