package kr.co.teambrain.marvelrun.user.capacity.command.application.dto;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;

import java.util.List;

/**
 * 정책 검증과 신청 저장을 마친 신청의 자원 확보 입력.
 *
 * child는 클라이언트 입력을 직접 사용하지 않고,
 * 서버에서 대회일과 생년월일로 판정한 값을 전달한다.
 *
 * @param registration 동일 트랜잭션에서 저장한 신청
 * @param requirements 필요량 입력. 구매 줄이 없는 신청은 신청 행 하나, 상품 신청은 구매 줄마다 하나다.
 *                     같은 정원 행의 수량은 신청 안에서 합쳐 예약 상세 한 줄로 저장한다.
 */
public record CapacityHoldRequest(
        Registration registration,
        List<CapacityRequirementInput> requirements
) {

    public CapacityHoldRequest {
        requirements = List.copyOf(requirements);
    }

    /**
     * 구매 줄이 없는 신청(일반 종목·단체 구성원)은 신청 행의 종목·기념품으로 계산한다.
     *
     * @param child 어린이 정원 적용 대상 여부
     */
    public CapacityHoldRequest(
            Registration registration,
            boolean child
    ) {
        this(
                registration,
                List.of(new CapacityRequirementInput(
                        registration.getEventCategory().getId(),
                        child,
                        registration.getSouvenirJson()
                ))
        );
    }
}
