package kr.co.teambrain.marvelrun.admin.event.command.application.context;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 형식과 정책 검증을 통과한 참가자별 저장 입력이며 최종 DB 충돌 검증은 별도로 수행한다. */
public record OfflineRegistrationContext(int rowNumber, String name, LocalDate birth,
        String phone, GenderClass gender, String address, String addressDetail,
        String categoryId, List<SouvenirJson> souvenirs, String guardianRelationship,
        String guardianName, String guardianPhone, boolean guardianConsent,
        boolean marketingConsent, String email, String note, BigDecimal amount,
        LocalDateTime approvedAt, boolean child) {
    /** 기념품 선택 목록을 불변으로 보존한다. */
    public OfflineRegistrationContext {
        souvenirs = List.copyOf(souvenirs);
    }
}
