package kr.co.teambrain.marvelrun.admin.payment.command.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.payment.command.dto.*;
import kr.co.teambrain.marvelrun.admin.payment.command.batch.AdminRefundBatchModels.*;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

/** 접수 커밋 후 현재 HTTP 스레드에서 순차 실행한다. 동일 요청 재전송은 조회만 한다. */
@Service
@RequiredArgsConstructor
public class AdminRefundBatchService {
    private final AdminRefundBatchStore store;
    private final AdminRefundBatchSelection selection;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final AdminRefundBatchWorker worker;

    /** 개인·단체원·단체 전체 선택을 정규화한다. */
    public Response full(String eventId, String adminId, AdminPaymentRefundRequest request) {
        validate(request);
        AdminPaymentRefundRequest normalized = new AdminPaymentRefundRequest(request.requestId(), request.reason(),
                request.registrationIds().stream().distinct().sorted().toList(), request.organizationIds().stream().distinct().sorted().toList());
        String fingerprint = fingerprint(Operation.FULL, normalized);
        String id = store.existing(eventId, request.requestId(), fingerprint, adminId);
        if (id != null) { return store.response(eventId, id); }
        return createAndExecute(eventId, request.requestId(), adminId, request.reason(), Operation.FULL, fingerprint, selection.full(eventId, normalized));
    }
    /** 생년월일·종목·기념품 외 일반 개인정보 및 임의 금액은 요청 DTO에 없다. */
    public Response partial(String eventId, String adminId, AdminPaymentPartialRefundRequest request) {
        validate(request);
        List<AdminPaymentPartialRefundTarget> targets = request.targets().stream()
                .sorted(Comparator.comparing(AdminPaymentPartialRefundTarget::registrationId))
                .map(t -> new AdminPaymentPartialRefundTarget(t.registrationId(),t.eventCategoryId(),
                        t.selectedSouvenirList().stream().sorted(Comparator.comparing(SouvenirJson::souvenirId)
                                .thenComparing(SouvenirJson::selectedSize,Comparator.nullsFirst(Comparator.naturalOrder()))).toList(),
                        t.birth(),t.keepParticipationWhenZero())).toList();
        AdminPaymentPartialRefundRequest normalized = new AdminPaymentPartialRefundRequest(request.requestId(), request.reason(), targets);
        String fingerprint = fingerprint(Operation.PARTIAL, normalized);
        String id = store.existing(eventId, request.requestId(), fingerprint, adminId);
        if (id != null) { return store.response(eventId, id); }
        return createAndExecute(eventId, request.requestId(), adminId, request.reason(), Operation.PARTIAL, fingerprint, selection.partial(eventId,normalized));
    }
    /** 중복 INSERT 롤백 후 기존 요청을 조회하며, 경쟁에서 진 호출은 실행하지 않는다. */
    private Response createAndExecute(String eventId, String requestId, String adminId, String reason,
            Operation operation, String fingerprint, List<Target> targets) {
        String id;
        try {
            id = store.create(eventId, requestId, adminId, reason, operation, fingerprint, targets);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            id = store.existing(eventId, requestId, fingerprint, adminId);
            if (id == null) { throw duplicate; }
            return store.response(eventId, id);
        }
        // 엔티티가 아닌 제한된 접수 DTO만 남고, 매 대상의 준비/결과 트랜잭션은 종료된다.
        for (int i = 0; i < targets.size(); i++) {
            if (!worker.processOne(id)) { break; }
        }
        return store.response(eventId, id);
    }

    /** 유효성 검사 실패는 어떤 대상도 접수하지 않는다. */
    private void validate(Object request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("배치 접수는 외부 트랜잭션 없이 호출해야 합니다."); }
        if (request == null || !validator.validate(request).isEmpty()) { throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT); }
    }
    /** 본문 원문이나 개인정보를 요청 중복 키로 노출하지 않는다. */
    private String fingerprint(Operation operation, Object request) {
        try {
            byte[] bytes = (operation.name()+"\n"+mapper.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) { throw new IllegalStateException("요청 비교값 생성 실패",error); }
    }
}
