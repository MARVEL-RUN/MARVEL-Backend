package kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.entity.QuestionAttachmentBase;
import kr.co.teambrain.marvelrun.user.community.command.application.domain.Question;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(
        name = "question_attachment",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_question_attachment_attachment",
                        columnNames = "attachment_id"
                )
        },
        indexes = {
                @Index(
                        name = "idx_question_attachment_question_order",
                        columnList = "question_id, display_order"
                )
        }
)
@SuperBuilder
@NoArgsConstructor(access = PROTECTED)
public class QuestionAttachment
        extends QuestionAttachmentBase<Question, Attachment> {

    public static QuestionAttachment create(
            Question question,
            Attachment attachment,
            int displayOrder
    ) {
        return QuestionAttachment.builder()
                .question(question)
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