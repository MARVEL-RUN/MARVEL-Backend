package kr.co.teambrain.marvelrun.admin.event.command.application.context;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/** 형식과 정책 검증을 통과한 참가자별 저장 입력이며 최종 DB 충돌 검증은 별도로 수행한다. */
public record OfflineRegistrationContext(int rowNumber, String name, LocalDate birth,
        String phone, GenderClass gender, String address, String addressDetail,
        String categoryId, List<SouvenirJson> souvenirs, String guardianRelationship,
        String guardianName, String guardianPhone, boolean guardianConsent,
        boolean marketingConsent, String email, String note, BigDecimal amount,
        LocalDateTime paidAtKst, boolean child) {
    /** 기념품 선택 목록을 불변으로 보존한다. */
    public OfflineRegistrationContext {
        souvenirs = List.copyOf(souvenirs);
    }

    /** 현장 결제시각을 UTC로 변환한 뒤 DB 계약에 맞게 오프셋을 제거한다. */
    public LocalDateTime approvedAtUtc() {
        // 현장 날짜 경계를 포함하여 동일한 순간을 UTC로 표현한다.
        return paidAtKst.atZone(ZoneId.of("Asia/Seoul"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
