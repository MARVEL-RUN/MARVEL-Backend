package kr.co.teambrain.marvelrun.user.common.attachment.command.repository;

import kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain.AnswerAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnswerAttachmentCommandRepository
        extends JpaRepository<AnswerAttachment, String> {

    List<AnswerAttachment>
    findAllByAnswer_IdOrderByDisplayOrderAsc(
            String answerId
    );
}