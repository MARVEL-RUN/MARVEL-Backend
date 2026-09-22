package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 9/22 MarvelRun MVP에서 참가 신청의 계약금액을 계산한다.
 *
 * 현재는 특정 MarvelRun Event에 한정하여 어린이 고정가격만 임시 적용한다.
 * 그 외 신청은 EventCategory의 기본 참가비를 그대로 사용한다.
 *
 * 향후 DB 기반 Pricing 정책이 도입되면 개인·단체·수정 흐름의 호출부는 유지하고,
 * 이 클래스 내부 계산 책임을 정식 Pricing 구조로 교체한다.
 */
@Service
public class RegistrationPricingService {

    /*
     * 어린이 고정가격을 적용할 검증용·운영용 MarvelRun Event ID 목록.
     *
     * 각 환경의 대회 데이터는 분리하고, 명시된 ID에 같은 가격 정책을 적용한다.
     * 다른 Event에는 어린이 고정가격을 적용하지 않는다.
     */
    private static final List<String> MVP_TARGET_EVENT_IDS =
            List.of("test-marvelrun", "marvelrun2026");

    private static final BigDecimal CHILD_FIXED_PRICE =
            new BigDecimal("40000");


    /**
     * 검증이 완료된 참가자의 계약금액을 계산한다.
     *
     * 대상 MarvelRun Event의 어린이 참가자는 40,000원 고정가격을 사용한다.
     * 그 외 Event 또는 성인 참가자는 EventCategory 기본금액을 그대로 사용한다.
     *
     * 생년월일 형식과 종목 참가 가능 여부는 이 메서드 호출 전에
     * 기존 Registration 정책 Validator에서 검증되어 있어야 한다.
     *
     * @param event 참가 대상 대회
     * @param eventCategory 참가 종목
     * @param birthText 검증된 참가자 생년월일 yyyy-MM-dd
     * @return 서버에서 확정한 계약금액
     */
    public BigDecimal calculateContractAmount(
            Event event,
            EventCategory eventCategory,
            String birthText
    ) {

        BigDecimal baseAmount =
                eventCategory.getAmount();

        if (MVP_TARGET_EVENT_IDS.stream().noneMatch(id -> id.equals(event.getId()))) {
            return baseAmount;
        }

        LocalDate birth =
                LocalDate.parse(birthText);

        LocalDate eventDate =
                event.getStartDate().toLocalDate();

        if (isChild(birth, eventDate)) {
            return CHILD_FIXED_PRICE;
        }

        return baseAmount;
    }


    /**
     * 참가자가 대회일 기준 만 12세 이하인지 판정한다.
     *
     * 13번째 생일 당일부터는 어린이로 판정하지 않는다.
     * 이 기준은 현재 Capacity의 어린이 판정 기준과 동일하다.
     *
     * @param birth 참가자 생년월일
     * @param eventDate 대회 개최일
     * @return 어린이 가격 적용 대상이면 true
     */
    private boolean isChild(
            LocalDate birth,
            LocalDate eventDate
    ) {
        return eventDate.isBefore(
                birth.plusYears(13)
        );
    }
}