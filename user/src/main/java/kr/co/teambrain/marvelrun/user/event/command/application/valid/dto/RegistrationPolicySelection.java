package kr.co.teambrain.marvelrun.user.event.command.application.valid.dto;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

import java.util.List;

/**
 * 생성·수정에서 공통 참가 정책검증에 전달하는 선택값이다.
 *
 * 생성 Request나 수정 Request에 의존하지 않으며,
 * 인증정보와 비밀번호는 포함하지 않는다.
 *
 * 선택 목록의 필수 여부와 중복은 기존 공통 검증 메서드가 검사한다.
 * 이 객체 자체는 입력값을 정규화하거나 Entity를 변경하지 않는다.
 */
public record RegistrationPolicySelection(
        String eventCategoryId,
        List<SouvenirJson> selectedSouvenirList,
        RegistrationPolicyInput participant
) {
}