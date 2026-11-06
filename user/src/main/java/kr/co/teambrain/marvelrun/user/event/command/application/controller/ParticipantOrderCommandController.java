package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.common.security.util.ParticipantPrincipal;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Order;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.ParticipantAdditionalOrderRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.ParticipantAdditionalOrderService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationCancellationCommandService;
import kr.co.teambrain.marvelrun.user.payment.command.application.PaymentRetryPreparationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 주문자의 추가 주문·주문 취소·결제 재시도.
 * 취소·재시도는 공개 진입점과 같은 처리부를 부르고, 본인확인 대신 주문자 소유를 본다.
 * 결제 승인은 기존 공통 승인 API(POST /v1/public/payments/confirm)로 한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/participant/registrations")
@PreAuthorize("hasRole('PARTICIPANT')")
public class ParticipantOrderCommandController {

    private final ParticipantAdditionalOrderService additionalOrders;
    private final RegistrationCancellationCommandService cancellations;
    private final PaymentRetryPreparationService retries;

    @Operation(summary = "추가 주문", description = "로그인한 주문자가 같은 대회에 주문을 하나 더 합니다. 상품(items)과 이 주문의 배송지·보호자·약관 동의만 보냅니다. "
            + "신청자 정보·비밀번호는 기존 주문 값을 그대로 씁니다. 같은 신청자 주문 여러 건을 허용하지 않는 대회는 REGISTRATION_ALREADY_EXISTS. "
            + "응답은 개인 신청과 같고 결제는 공통 승인 API로 합니다.")
    @PostMapping
    public ResponseEntity<RegistrationCreateResponse> order(@Valid @RequestBody ParticipantAdditionalOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(additionalOrders.order(ParticipantPrincipal.ordererKey(), request));
    }

    @Operation(summary = "주문 전체 취소", description = "공개 개인 취소와 같은 규칙(환불 기간·외부 결제·진행 중 결제)으로 주문 전체를 취소하고 환불 결과를 돌려줍니다.")
    @PostMapping("/{registrationId}/cancellation")
    public ResponseEntity<RegistrationModificationSettlementResult> cancel(@PathVariable("registrationId") String registrationId) {
        return ResponseEntity.ok(cancellations.cancelOwnedPersonal(registrationId, ParticipantPrincipal.ordererKey()));
    }

    @Operation(summary = "결제 재시도", description = "실패·무효가 된 주문의 결제창 정보를 다시 만듭니다. 진행 중 상향이 있으면 상향 결제(.../upgrade/payment)로 해야 합니다.")
    @PostMapping("/{registrationId}/payments/{paymentId}/retry")
    public ResponseEntity<Order> retry(@PathVariable("registrationId") String registrationId,
                                       @PathVariable("paymentId") String paymentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(retries.prepareOwnedPersonal(registrationId, paymentId, ParticipantPrincipal.ordererKey()));
    }
}
