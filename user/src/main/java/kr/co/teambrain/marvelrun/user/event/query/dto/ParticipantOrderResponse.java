package kr.co.teambrain.marvelrun.user.event.query.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이페이지 주문 한 건. order는 공개 신청 조회 응답과 같은 모양이라 프론트가 같은 파서를 쓸 수 있다.
 * 구매 줄·받는 사람·배번과 마감 시각은 마이페이지에만 있다.
 */
public record ParticipantOrderResponse(
        RegistrationQueryResponse order,
        @Schema(description = "취소한 주문이면 true. 환불 상태는 order.refundStatus로 본다")
        boolean canceled,
        @Schema(description = "신청일이 속한 차수 기준 정보 수정 마감 시각. 없으면 null. 화면 안내용이며 실제 가능 여부는 order.userPolicy로 본다")
        LocalDateTime modificationDeadline,
        @Schema(description = "신청일이 속한 차수 기준 환불 마감 시각. 없으면 null")
        LocalDateTime refundDeadline,
        @Schema(description = "구매 줄. 일반 종목 신청은 빈 목록")
        List<Item> items
) {

    public record Item(
            String itemId,
            String eventCategoryId,
            String eventCategoryName,
            ProductType productType,
            BigDecimal amount,
            List<SouvenirJson> selectedSouvenirList,
            List<Entry> entries
    ) {
    }

    public record Entry(
            @Schema(description = "받는 사람 수정(PATCH .../{registrationId}/entries)에 쓰는 id")
            String entryId,
            String eventCategoryId,
            String eventCategoryName,
            String name,
            String phNum,
            String birth,
            GenderClass gender,
            @Schema(description = "배번. 발급 전이면 null")
            String bibNumber
    ) {
    }
}
