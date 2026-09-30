package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import java.util.List;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;

/** 입력·실제 처리 건수와 독립적으로 커밋 또는 롤백된 신청별 결과를 반환한다. */
public record UnpaidRegistrationBatchResponse(
        int requestedCount,
        int targetCount,
        int successCount,
        int failureCount,
        List<Success> successes,
        List<Failure> failures
) {
    /** 결과 목록을 복사하여 응답 생성 이후의 변경을 방지한다. */
    public UnpaidRegistrationBatchResponse {
        successes = List.copyOf(successes);
        failures = List.copyOf(failures);
    }

    /** 검증을 통과한 신규 취소와 이미 취소된 신청의 재요청을 구분한다. */
    public record Success(
            String registrationId,
            boolean alreadyCanceled
    ) {
    }

    /** 같은 대회에서 조회한 실패 신청 정보와 공개 가능한 업무 오류만 전달한다. */
    public record Failure(
            String registrationId,
            String name,
            String phNum,
            String birth,
            String eventCategoryName,
            ErrorCode errorCode,
            String message
    ) {
        /** 대상 부재·대회 불일치·조회 실패 시 개인정보 없이 원래 오류를 유지한다. */
        public static Failure withoutRegistrationDetails(
                String registrationId,
                ErrorCode errorCode
        ) {
            return new Failure(
                    registrationId,
                    null,
                    null,
                    null,
                    null,
                    errorCode,
                    errorCode.getMessage()
            );
        }
    }
}
