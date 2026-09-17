package kr.co.teambrain.marvelrun.user.common.attachment.storage.upload;

import kr.co.teambrain.marvelrun.common.inheritance_enum.AttachmentStorageType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "attachment.storage")
public class AttachmentStoragePolicyProperties {

    private AttachmentStorageType defaultType =
            AttachmentStorageType.LOCAL;
}