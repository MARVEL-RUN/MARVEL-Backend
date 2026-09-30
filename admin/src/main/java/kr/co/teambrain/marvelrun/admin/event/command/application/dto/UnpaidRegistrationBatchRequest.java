package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import java.util.List;

/** 미결제 삭제 대상 원본 목록을 받아 중복 제거 전 건수 검증에 사용한다. */
public record UnpaidRegistrationBatchRequest(
        List<String> registrationIds
) {
}
