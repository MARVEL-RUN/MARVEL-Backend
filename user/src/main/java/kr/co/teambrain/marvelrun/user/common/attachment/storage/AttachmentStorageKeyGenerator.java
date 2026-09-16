package kr.co.teambrain.marvelrun.user.common.attachment.storage;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Attachment의 저장소 내부 논리 식별자인 storageKey를 생성한다.
 *
 * 실제 filesystem root 또는 Object Storage bucket 정보는 포함하지 않는다.
 *
 * 생성 예:
 * 2026/09/16/550e8400-e29b-41d4-a716-446655440000.jpg
 *
 * Local/Object Storage가 동일한 storageKey 체계를 사용하도록 함으로써
 * 저장소 변경 시 DB 데이터 변경을 최소화한다.
 */
@Component
public class AttachmentStorageKeyGenerator {

    private static final Pattern SAFE_EXTENSION =
            Pattern.compile("^[a-z0-9]{1,10}$");

    /*
     * 서버 운영 기준 timezone.
     * 프로젝트 전체 TimeZone 정책이 있다면 추후 해당 설정을 사용해도 됨.
     */
    private static final ZoneId ZONE_ID =
            ZoneId.of("Asia/Seoul");


    public String generate(
            String extension
    ) {

        String normalizedExtension =
                normalizeExtension(extension);

        LocalDate now =
                LocalDate.now(ZONE_ID);

        return "%04d/%02d/%02d/%s.%s"
                .formatted(
                        now.getYear(),
                        now.getMonthValue(),
                        now.getDayOfMonth(),
                        UUID.randomUUID(),
                        normalizedExtension
                );
    }


    private String normalizeExtension(
            String extension
    ) {

        if (extension == null
                || extension.isBlank()) {

            throw new IllegalArgumentException(
                    "Attachment extension must not be blank."
            );
        }

        String normalized =
                extension
                        .replace(".", "")
                        .toLowerCase(Locale.ROOT);

        if (!SAFE_EXTENSION.matcher(normalized).matches()) {

            throw new IllegalArgumentException(
                    "Invalid attachment extension."
            );
        }

        return normalized;
    }
}