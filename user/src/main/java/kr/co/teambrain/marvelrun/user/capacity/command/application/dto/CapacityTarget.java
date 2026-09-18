package kr.co.teambrain.marvelrun.user.capacity.command.application.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.CapacityType;

/**
 * 신청에 적용할 Capacity를 결정하기 위한 조회 결과이다.
 *
 * 카운터는 가져오지 않으며, 실제 확보 가능 여부는
 * 조건부 UPDATE 실행 결과로 판단한다.
 *
 * @param capacityId 확보 대상 식별자
 * @param type 제한 종류
 * @param souvenirId 기념품 식별자. 정원 제한이면 null
 * @param size 단일 사이즈. 사이즈 구분이 없으면 빈 문자열
 */
public record CapacityTarget(
        String capacityId,
        CapacityType type,
        String souvenirId,
        String size
) {
}