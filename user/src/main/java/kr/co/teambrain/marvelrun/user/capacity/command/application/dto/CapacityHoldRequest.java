package kr.co.teambrain.marvelrun.user.capacity.command.application.dto;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;

/**
 * 정책 검증과 신청 저장을 마친 참가자의 자원 확보 입력.
 *
 * child는 클라이언트 입력을 직접 사용하지 않고,
 * 서버에서 대회일과 생년월일로 판정한 값을 전달한다.
 *
 * @param registration 동일 트랜잭션에서 저장한 신청
 * @param child 어린이 정원 적용 대상 여부
 */
public record CapacityHoldRequest(
        Registration registration,
        boolean child
) {
}