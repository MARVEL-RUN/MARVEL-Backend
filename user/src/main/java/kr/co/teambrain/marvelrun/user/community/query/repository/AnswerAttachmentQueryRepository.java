package kr.co.teambrain.marvelrun.user.community.query.repository;

import kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain.AnswerAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AnswerAttachmentQueryRepository
        extends JpaRepository<AnswerAttachment, String> {

    Optional<AnswerAttachment>
    findByAnswerIdAndAttachmentId(
            String answerId,
            String attachmentId
    );
}