package kr.co.teambrain.marvelrun.admin.community.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.AnswerBase;

// 실제 Admin entity package로 변경
import kr.co.teambrain.marvelrun.admin.auth.command.application.domain.Admin;

import kr.co.teambrain.marvelrun.admin.community.command.application.dto.AnswerUpdate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(
        name = "answer",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_answer_question",
                        columnNames = "question_id"
                )
        }
)
@SuperBuilder
@NoArgsConstructor(access = PROTECTED)
public class Answer
        extends AnswerBase<Admin, Question> {


    public void updateAnswer(
            AnswerUpdate request
    ) {

        this.title =
                request.getTitle();

        this.content =
                request.getContent();
    }
}