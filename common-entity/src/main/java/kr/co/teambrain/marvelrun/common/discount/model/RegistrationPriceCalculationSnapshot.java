package kr.co.teambrain.marvelrun.common.discount.model;

import java.util.List;

/** 과거 정책 변경과 독립적으로 당시 적용 순서와 계산값을 보존하는 JSON 계약이다. */
public record RegistrationPriceCalculationSnapshot(
        int schemaVersion,
        List<DiscountModels.AppliedDiscount> appliedDiscounts
) {
    /** 전달 목록 변경이 이미 생성한 계산 근거에 반영되지 않도록 복사한다. */
    public RegistrationPriceCalculationSnapshot {
        appliedDiscounts = List.copyOf(appliedDiscounts);
    }

    /** 최초 생성과 사용자·관리자 가격 변경의 반영 원인을 구분한다. */
    public enum Reason {
        REGISTRATION_CREATED,
        REGISTRATION_MODIFIED,
        ADMIN_ADJUSTED
    }
}
