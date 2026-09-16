package kr.co.teambrain.marvelrun.admin.community.command.application.domain;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.admin.event.command.domain.Event;
import kr.co.teambrain.marvelrun.admin.user.command.domain.User;
import kr.co.teambrain.marvelrun.common.entity.QuestionBase;

// 아래 두 import만 현재 Admin 프로젝트의 실제 경로 사용


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@Table(name = "question")
@SuperBuilder
@NoArgsConstructor(access = PROTECTED)
public class Question
        extends QuestionBase<User, Event> {


    /**
     * Answer 생성 완료.
     */
    public void resolveQuestion() {

        this.isAnswered = true;
    }


    /**
     * Answer 삭제 후 다시 미답변 상태로 변경.
     */
    public void reopenQuestion() {

        this.isAnswered = false;
    }


    /**
     * Admin에서 문의 비밀번호 변경 시 사용.
     */
    public void updatePassword(
            String encodedPassword
    ) {

        this.password = encodedPassword;
    }
}