package kr.co.teambrain.marvelrun.user.event.command.application.context;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;

import java.util.List;

public record RegistrationCreateContext(

        Event event,

        /** 신청 행에 넣을 종목. 상품 구매면 첫 줄의 상품(대표 상품)이다. */
        EventCategory eventCategory,

        /** 신청 행에 넣을 기념품. 상품 구매면 첫 줄의 기념품이다. */
        List<SouvenirJson> souvenirJsons,

        /** 저장할 구매 줄. 일반 종목 신청이면 빈 목록 */
        List<RegistrationItemDraft> items,

        /** 대회가 같은 신청자의 주문 여러 건을 허용하는지. 신청 행에 그대로 복사한다. */
        boolean duplicateAllowed
) {
}
