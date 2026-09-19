package kr.co.teambrain.marvelrun.user.event.command.application.valid.dto;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;

import java.time.LocalDate;
import java.util.List;

/**
 * 한 참가자의 공통 정책검증 완료 결과다.
 *
 * 검증된 종목, 파싱된 생년월일, 정규화된 기념품 선택을 전달한다.
 * Registration Entity 변경이나 가격 계산은 수행하지 않는다.
 */
public record RegistrationPolicyCandidateResult(
        EventCategory eventCategory,
        LocalDate birth,
        List<SouvenirJson> souvenirJsons
) {

    /**
     * 검증 완료된 선택 목록을 외부 변경으로부터 보호한다.
     */
    public RegistrationPolicyCandidateResult {
        souvenirJsons = List.copyOf(souvenirJsons);
    }
}