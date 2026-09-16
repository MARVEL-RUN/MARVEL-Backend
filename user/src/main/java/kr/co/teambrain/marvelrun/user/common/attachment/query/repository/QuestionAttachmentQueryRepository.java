package kr.co.teambrain.marvelrun.user.common.attachment.query.repository;

import kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain.QuestionAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QuestionAttachmentQueryRepository
        extends JpaRepository<QuestionAttachment, String> {

    List<QuestionAttachment>
    findAllByQuestion_IdOrderByDisplayOrderAsc(
            String questionId
    );


    Optional<QuestionAttachment>
    findByQuestion_IdAndAttachment_Id(
            String questionId,
            String attachmentId
    );
}