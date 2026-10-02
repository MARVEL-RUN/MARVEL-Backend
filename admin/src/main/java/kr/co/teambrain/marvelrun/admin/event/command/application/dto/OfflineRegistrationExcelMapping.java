package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** 관리자 제출 파일의 가격과 선택 목록이며 온라인 가격 정책을 대체하지 않는다. */
public record OfflineRegistrationExcelMapping(String eventId, LocalDate childCutoff,
        String souvenirId, String souvenirName, Map<String, Price> prices,
        Set<String> adultSizes, Set<String> childSizes) {
    /** 매핑 설정을 한 요청 내에서 불변으로 유지한다. */
    public OfflineRegistrationExcelMapping {
        prices = Map.copyOf(prices);
        adultSizes = Set.copyOf(adultSizes);
        childSizes = Set.copyOf(childSizes);
    }

    /** 어린이 금액이 null이면 해당 종목의 어린이 신청을 허용하지 않는다. */
    public record Price(String categoryId, BigDecimal adultAmount, BigDecimal childAmount) { }
}
