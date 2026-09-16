package kr.co.teambrain.marvelrun.user.common.attachment.storage.upload;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.EnumSet;
import java.util.Set;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "attachment.upload")
public class AttachmentUploadProperties {

    private int maxCount = 3;

    private DataSize maxFileSize =
            DataSize.ofMegabytes(5);

    private DataSize maxTotalSize =
            DataSize.ofMegabytes(15);

    private long maxPixels =
            40_000_000L;

    private Set<AttachmentFileType> allowedTypes =
            EnumSet.of(
                    AttachmentFileType.JPEG,
                    AttachmentFileType.PNG
            );
}