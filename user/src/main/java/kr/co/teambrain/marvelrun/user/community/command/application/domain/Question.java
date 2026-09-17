package kr.co.teambrain.marvelrun.user.community.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.QuestionBase;
import kr.co.teambrain.marvelrun.user.community.command.application.dto.ArticlePatchRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.userinfo.command.application.domain.User;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@Entity
@Table(name = "question")
public class Question extends QuestionBase<User, Event> {
    public Question() {
        super();
    }

    public void mappedDeleteuser(User deleteMappedUser) {
        this.user = deleteMappedUser;
    }

    public void patchContent(ArticlePatchRequest request) {
        this.title = request.getTitle();
        this.content = request.getContent();
        this.isSecret = request.isSecret();
    }
}