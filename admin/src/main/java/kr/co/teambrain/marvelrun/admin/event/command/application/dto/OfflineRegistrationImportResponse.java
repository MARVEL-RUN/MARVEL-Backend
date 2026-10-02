package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import java.math.BigDecimal;
import java.util.List;

/** 업로드 결과와 원본 엑셀 행에 대응하는 오류를 반환한다. */
public record OfflineRegistrationImportResponse(String code, int savedCount, int canceledCount,
                                                List<Success> registrations, List<Failure> errors) {
    /** 응답 목록을 불변으로 보존한다. */
    public OfflineRegistrationImportResponse {
        registrations = List.copyOf(registrations);
        errors = List.copyOf(errors);
    }

    /** 정상 저장된 신청과 결제의 식별자를 반환한다. */
    public record Success(int rowNumber, String registrationId, String paymentId, BigDecimal amount) { }

    /** 개인정보 원문을 포함하지 않는 필드별 오류다. 파일 오류의 행 번호는 0이다. */
    public record Failure(int rowNumber, String field, String code, String message) { }
}
