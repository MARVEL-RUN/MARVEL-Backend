package kr.co.teambrain.marvelrun.user.event.command.application.valid.dto;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

import java.util.List;
import java.util.Objects;

/**
 * 생성·수정에서 공통으로 사용하는 참가 정책검증 입력이다.
 *
 * 인증정보나 저장 대상 Entity를 포함하지 않으며,
 * 참가자 정책 입력과 종목·기념품 선택만 전달한다.
 */
public record RegistrationPolicyCandidateRequest(
        String eventCategoryId,
        List<SouvenirJson> selectedSouvenirList,
        RegistrationPolicyInput participant
) {

    /**
     * 호출 이후 선택 목록이 변경되지 않도록 복사한다.
     *
     * HTTP 입력의 필수값 검증은 기존 Request의 Bean Validation이 담당한다.
     */
    public RegistrationPolicyCandidateRequest {
        Objects.requireNonNull(eventCategoryId);
        Objects.requireNonNull(participant);
        selectedSouvenirList = List.copyOf(selectedSouvenirList);
    }
}