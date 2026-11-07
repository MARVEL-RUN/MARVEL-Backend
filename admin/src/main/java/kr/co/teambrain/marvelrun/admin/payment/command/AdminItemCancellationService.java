package kr.co.teambrain.marvelrun.admin.payment.command;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationItemCommandRepository;
import kr.co.teambrain.marvelrun.admin.payment.command.application.refund.AdminRefundExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 관리자 상품 하나 취소(부분 취소)의 진입점. 준비 트랜잭션을 커밋한 뒤 환불을 실행한다(기존 관리자 환불과 같은 순서).
 *
 * 남은 마지막 줄이면 주문 전체 환불(prepareFull)로 보낸다. 줄 수는 여기서 잠금 없이 세고,
 * 준비 트랜잭션이 잠근 뒤 다시 세어 달라졌으면 CONCURRENT_MODIFICATION으로 끝낸다.
 */
@Service
@RequiredArgsConstructor
public class AdminItemCancellationService {

    private final RegistrationCommandRepository registrations;
    private final RegistrationItemCommandRepository items;
    private final AdminRefundPreparationService preparation;
    private final AdminRefundExecutionService execution;

    public Result cancel(String registrationId, String itemId, String adminId, String reason) {
        Registration registration = registrations.findById(registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));
        // 상품은 개인 주문만 살 수 있다. 단체 구성원은 기존 단체 환불을 쓴다.
        if (registration.getOrganization() != null) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT, " 단체 구성원 registrationId=" + registrationId);
        }
        String eventId = registration.getEvent().getId();
        List<RegistrationItem> active = items.findActiveByRegistrationId(registrationId);
        boolean lastLine = active.size() == 1 && active.getFirst().getId().equals(itemId);

        AdminRefundCommandContext command = new AdminRefundCommandContext(UUID.randomUUID().toString(), adminId, reason);
        AdminRefundPrepared prepared = lastLine
                ? preparation.prepareFull(eventId, null, List.of(registrationId), command)
                : preparation.prepareItemCancellation(eventId, registrationId, itemId, command);
        return new Result(lastLine, prepared, execution.execute(prepared));
    }

    /**
     * @param orderCanceled 남은 마지막 상품이라 주문 전체를 취소했는지
     * @param preparation 준비 결과(계약금액·납부액·상태와 환불 시도)
     * @param execution 환불 실행 결과. 환불 완료 여부는 이 값의 결과로 확인한다
     */
    public record Result(boolean orderCanceled, AdminRefundPrepared preparation, AdminRefundExecutionService.Result execution) {
    }
}
