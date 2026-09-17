package kr.co.teambrain.marvelrun.user.common.attachment.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Local Attachment 저장소의 설정값.
 *
 * rootPath는 애플리케이션 컨테이너에서 바라보는
 * Attachment 전용 persistent directory를 의미한다.
 *
 * 실제 운영 환경에서는 해당 경로를
 * Docker host의 persistent directory와 volume mount한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "attachment.storage.local")
public class AttachmentStorageProperties {

    private String rootPath;
}