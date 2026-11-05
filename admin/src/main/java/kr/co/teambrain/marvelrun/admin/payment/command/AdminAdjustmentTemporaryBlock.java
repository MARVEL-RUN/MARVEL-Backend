package kr.co.teambrain.marvelrun.admin.payment.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.List;
import java.util.Objects;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityRequirementInput;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;

/**
 * 2026-09-23 관리자 신청 조정의 임시 운영 차단이다. 개인·단체 및 두 조정 URL에 공통 적용한다.
 * TODO: 기념품 정책/관리자 화면 및 추가 결제 검증 후 아래 스위치를 각각 false로 바꾸고 재배포한다.
 * 영구 참가 정책이나 사용자 결제 준비를 변경하지 않는다.
 */
/** 테스트 서버 검증 재개를 위해 두 임시 차단을 해제한 상태이다. 배포 환경별 자동 분기는 없다. */
public final class AdminAdjustmentTemporaryBlock {
    /** 기념품 정책 재적용 및 관리자 화면 대응까지 어른↔어린이 전환을 중단한다. */
    private static final boolean BLOCK_AGE_GROUP_CHANGE = false;
    /** 종목 변경으로 변경 전 계약금액보다 금액이 커지는 경우만 중단한다. */
    private static final boolean BLOCK_CATEGORY_PRICE_INCREASE = false;
    /** 참가 항목이 있는 상품의 종목 변경은 항목을 다시 만들 흐름이 없어 막는다. */
    private static final boolean BLOCK_PRODUCT_CATEGORY_CHANGE = true;

    /** 인스턴스 생성이 필요 없는 공통 가드이다. */
    private AdminAdjustmentTemporaryBlock() { }

    /** 기존 Capacity와 동일하게 대회일 기준 만 13세 미만 여부를 비교한다. 기념품 검증 전에 실행한다. */
    public static void validateBirthTransition(String previousBirth, String nextBirth, LocalDate eventDate) {
        if (!BLOCK_AGE_GROUP_CHANGE) { return; }
        try {
            boolean previousChild = CapacityRequirementInput.fromCandidate(
                    "", previousBirth, eventDate, List.of()).child();
            boolean nextChild = CapacityRequirementInput.fromCandidate(
                    "", nextBirth, eventDate, List.of()).child();
            if (previousChild != nextChild) {
                throw new CustomException(ErrorCode.ADMIN_ADJUSTMENT_AGE_GROUP_TEMPORARILY_BLOCKED);
            }
        } catch (DateTimeException | NullPointerException exception) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_BIRTH);
        }
    }

    /** 납부액이 아닌 변경 전 계약금액과 서버가 계산한 변경 후 계약금액을 비교한다. */
    public static void validateCategoryPriceIncrease(String previousCategoryId, String nextCategoryId,
            BigDecimal previousContractAmount, BigDecimal nextContractAmount) {
        if (BLOCK_CATEGORY_PRICE_INCREASE && !Objects.equals(previousCategoryId, nextCategoryId)
                && nextContractAmount.compareTo(previousContractAmount) > 0) {
            throw new CustomException(ErrorCode.ADMIN_ADJUSTMENT_CATEGORY_PRICE_INCREASE_TEMPORARILY_BLOCKED);
        }
    }

    /** 상품 종류가 있는 종목에서, 또는 그런 종목으로 바꾸면 막는다. 기존 종목끼리는 그대로 둔다. */
    public static void validateProductCategoryChange(EventCategory previousCategory, EventCategory nextCategory) {
        if (BLOCK_PRODUCT_CATEGORY_CHANGE && !Objects.equals(previousCategory.getId(), nextCategory.getId())
                && (previousCategory.getProductType() != null || nextCategory.getProductType() != null)) {
            throw new CustomException(ErrorCode.ADMIN_ADJUSTMENT_PRODUCT_CATEGORY_TEMPORARILY_BLOCKED);
        }
    }
}
