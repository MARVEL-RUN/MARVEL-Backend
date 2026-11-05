package kr.co.teambrain.marvelrun.user.capacity.command.application.dto;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;

import java.time.LocalDate;
import java.util.List;

/**
 * 참가자 한 명, 또는 상품 신청의 구매 줄 하나의 Capacity 필요량을 계산하는 입력이다.
 *
 * 저장된 Registration이나 수정 중인 Entity에 의존하지 않는다.
 * 종목과 기념품 선택은 앞선 정책검증을 통과한 값이어야 한다.
 *
 * @param participants 전체 정원(EVENT_TOTAL)에서 차감할 사람 수.
 *                     참가자 한 명·단품 줄은 1, 패키지 줄은 참가 항목 수(하위 종목 수)다.
 */
public record CapacityRequirementInput(
        String eventCategoryId,
        boolean child,
        List<SouvenirJson> souvenirs,
        int participants
) {

    /**
     * 계산 도중 외부에서 선택 목록이 변경되지 않도록 복사한다.
     */
    public CapacityRequirementInput {
        souvenirs = List.copyOf(souvenirs);
        if (participants < 1) {
            throw new CustomException(
                    ErrorCode.INVALID_RESERVATION_ARGUMENT,
                    " 전체 정원 차감 인원은 1 이상이어야 합니다. participants=" + participants
            );
        }
    }

    /** 참가자 한 명(전체 정원 1)의 입력이다. */
    public CapacityRequirementInput(
            String eventCategoryId,
            boolean child,
            List<SouvenirJson> souvenirs
    ) {
        this(eventCategoryId, child, souvenirs, 1);
    }

    /**
     * 정책검증을 통과한 수정 후보에서 필요량 입력을 구성한다.
     *
     * 어린이 정원은 대회일 기준 만 13세 미만에 적용한다.
     * 현재는 '특수 신청자' 정책으로 어린이만 계산중이므로 boolean을 이용해 작게 구성.
     * 추후 필요시 별도 참여자 구분 정책 구성 및 enum화 처리(ex. 20대 30대 등등)
     */
    public static CapacityRequirementInput fromCandidate(
            String eventCategoryId,
            String birth,
            LocalDate eventDate,
            List<SouvenirJson> souvenirs
    ) {
        return fromCandidate(eventCategoryId, birth, eventDate, souvenirs, 1);
    }

    /** 전체 정원 차감 인원을 함께 받는 구매 줄 후보의 입력이다. */
    public static CapacityRequirementInput fromCandidate(
            String eventCategoryId,
            String birth,
            LocalDate eventDate,
            List<SouvenirJson> souvenirs,
            int participants
    ) {
        boolean child =
                eventDate.isBefore(
                        LocalDate.parse(birth).plusYears(13)
                );

        return new CapacityRequirementInput(
                eventCategoryId,
                child,
                souvenirs,
                participants
        );
    }
}
