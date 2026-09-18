package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.CapacityType;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityHoldRequest;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 신청 생성 흐름과 Capacity 확보 처리를 연결한다.
 *
 * 대회 잠금, 어린이 정원 대상 판정, 개인·단체 자원 확보,
 * 전체 정원 도달에 따른 대회 마감을 담당한다.
 *
 * 신청 생성 서비스의 트랜잭션에 참여하며,
 * 자체적인 별도 트랜잭션이나 외부 결제 호출은 수행하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationCapacityService {

    private final EventCommandRepository eventRepository;
    private final CapacityCommandRepository capacityRepository;
    private final CapacityHoldService capacityHoldService;

    /**
     * 신규 신청의 검증과 저장에 앞서 대회 행을 잠근다.
     *
     * 이후 기존 Validator가 같은 트랜잭션에서 대회를 조회하고,
     * 최신 신청 상태와 기간을 검증하도록 한다.
     *
     * @param eventId 신청 대상 대회
     * @throws CustomException 대회가 존재하지 않는 경우
     */
    public void lockEvent(String eventId) {
        eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(
                        () -> new CustomException(ErrorCode.EVENT_NOT_FOUND)
                );
    }

    /**
     * 검증과 저장을 마친 신청들의 자원을 확보하고,
     * 전체 정원 도달 시 접수를 마감한다.
     *
     * 대회 및 신청의 필수 정보는 앞선 검증 결과를 신뢰한다.
     * 처리 대상이 없는 경우만 차단한다.
     *
     * 자원 확보와 신청 저장, 최초 결제 생성은 동일 트랜잭션에서 처리한다.
     */
    public void holdAndCloseIfFull(
            Event event,
            List<Registration> registrations,
            LocalDateTime now
    ) {
        if (registrations.isEmpty()) {
            throw new CustomException(
                    ErrorCode.INVALID_RESERVATION_ARGUMENT,
                    " 자원 확보 대상 신청 목록이 비어 있습니다."
            );
        }

        LocalDate eventDate = event.getStartDate().toLocalDate();

        List<CapacityHoldRequest> holdRequests =
                new ArrayList<>(registrations.size());

        for (Registration registration : registrations) {
            holdRequests.add(
                    new CapacityHoldRequest(
                            registration,
                            isChild(registration.getBirth(), eventDate)
                    )
            );
        }

        capacityHoldService.holdAll(
                event.getId(),
                holdRequests,
                now
        );

        long fullTotalCount =
                capacityRepository.countFullTotalCapacities(
                        event.getId(),
                        CapacityType.EVENT_TOTAL
                );

        if (fullTotalCount > 0) {
            event.closeRegistrationForCapacity();
        }
    }

    /**
     * 검증된 생년월일을 기준으로 대회일에 만 12세 이하인지 판정한다.
     *
     * 13번째 생일 당일부터 어린이 정원을 적용하지 않는다.
     * 생년월일 형식과 신청 가능 여부는 기존 Validator에서 검증한다.
     */
    private boolean isChild(String birthText, LocalDate eventDate) {
        LocalDate birth = LocalDate.parse(birthText);
        return eventDate.isBefore(birth.plusYears(13));
    }
}