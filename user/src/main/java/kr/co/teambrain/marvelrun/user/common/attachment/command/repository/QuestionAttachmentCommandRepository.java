package kr.co.teambrain.marvelrun.user.common.attachment.command.repository;

import kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain.QuestionAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuestionAttachmentCommandRepository
        extends JpaRepository<QuestionAttachment, String> {


    List<QuestionAttachment>
    findAllByQuestion_IdOrderByDisplayOrderAsc(
            String questionId
    );
}