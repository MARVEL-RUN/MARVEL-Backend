package kr.co.teambrain.marvelrun.admin.event.command.application.valid.dto;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;

import java.util.List;

/**
 * 한 참가자의 공통 정책검증 완료 결과다.
 *
 * 검증된 종목과 정규화된 기념품 선택을 전달한다.
 * 가격 계산과 Entity 변경은 수행하지 않는다.
 */
public record RegistrationPolicyValidationResult(
        EventCategory eventCategory,
        List<SouvenirJson> souvenirJsons
) {

    /**
     * 검증 완료된 기념품 목록을 외부 변경으로부터 보호한다.
     */
    public RegistrationPolicyValidationResult {
        souvenirJsons = List.copyOf(souvenirJsons);
    }
}