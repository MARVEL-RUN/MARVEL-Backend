package kr.co.teambrain.marvelrun.admin.payment.command;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.admin.payment.command.dto.AdminItemCancellationRequest;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** 관리자 상품 하나 취소(부분 취소). 사용자 마이페이지의 상품 하나 취소와 같은 규칙이고 차수 마감은 보지 않는다. */
@RestController
@RequestMapping("/v1/admin/registrations/{registrationId}/items/{itemId}")
@RequiredArgsConstructor
public class AdminItemCancellationController {

    private final AdminItemCancellationService service;

    @Operation(summary = "상품 하나 취소(부분 취소)",
            description = "결제를 마친 개인 주문에서 구매 줄 하나를 취소하고 그 금액만 환불합니다. 패키지는 하위 종목까지 통째로 취소합니다. "
                    + "남은 마지막 상품이면 주문 전체 환불과 같습니다(orderCanceled=true). "
                    + "진행 중 결제·환불이 있으면 409, 상향 대기 중이면 ADMIN_PACKAGE_UPGRADE_PENDING, "
                    + "없는 상품은 REGISTRATION_ITEM_NOT_FOUND, 이미 취소한 상품은 REGISTRATION_ITEM_ALREADY_CANCELED.")
    @PostMapping("/cancellation")
    public ResponseEntity<AdminItemCancellationService.Result> cancel(
            @PathVariable("registrationId") String registrationId,
            @PathVariable("itemId") String itemId,
            @Valid @RequestBody AdminItemCancellationRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.cancel(registrationId, itemId, adminId(), request.reason()));
    }

    /** 일반 사용자 Principal이나 익명 인증으로 관리자 환불을 요청할 수 없다(AdminRefundBatchController와 같다). */
    private String adminId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof CustomAdminDetail detail)) {
            throw new AccessDeniedException("관리자 인증이 필요합니다.");
        }
        return detail.getUsername();
    }
}
