package kr.co.teambrain.marvelrun.user.common.attachment.command.repository;

import kr.co.teambrain.marvelrun.user.common.attachment.command.application.domain.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AttachmentCommandRepository
        extends JpaRepository<Attachment, String> {

}
