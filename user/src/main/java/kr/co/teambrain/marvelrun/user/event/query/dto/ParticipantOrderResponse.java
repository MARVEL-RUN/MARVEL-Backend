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
            @Schema(description = "구매 줄 id. 패키지 상향(itemId)·상품 하나 취소 경로에 쓴다")
            String itemId,
            @Schema(description = "구매 상품(단품·패키지) 종목 id")
            String eventCategoryId,
            @Schema(description = "구매 상품(단품·패키지) 종목 이름")
            String eventCategoryName,
            @Schema(description = "상품 종류(단품·패키지·옵션)")
            ProductType productType,
            @Schema(description = "구매 줄 금액")
            BigDecimal amount,
            @Schema(description = "이 구매 줄의 기념품. 패키지는 부문 키트 3개, 단품은 자기 부문 키트 1개")
            List<SouvenirJson> selectedSouvenirList,
            @Schema(description = "받는 사람. 패키지는 하위 종목 순서, 단품은 한 명. 취소한 상품의 받는 사람은 빠진다")
            List<Entry> entries,
            @Schema(description = "상품 하나 취소(부분 취소) 시각. null이면 살아 있는 상품이다. 취소한 상품도 목록에 남는다.")
            LocalDateTime canceledAt
    ) {
    }

    @Schema(name = "ParticipantOrderEntry")
    public record Entry(
            @Schema(description = "받는 사람 수정(PATCH .../{registrationId}/entries)에 쓰는 id")
            String entryId,
            @Schema(description = "참가 종목 id")
            String eventCategoryId,
            @Schema(description = "참가 종목 이름")
            String eventCategoryName,
            @Schema(description = "받는 사람 이름")
            String name,
            @Schema(description = "받는 사람 연락처")
            String phNum,
            @Schema(description = "받는 사람 생년월일(yyyy-MM-dd)")
            String birth,
            @Schema(description = "받는 사람 성별")
            GenderClass gender,
            @Schema(description = "배번. 발급 전이면 null")
            String bibNumber
    ) {
    }
}
