package kr.co.teambrain.marvelrun.admin.payment.command.evidence;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundTime;
import kr.co.teambrain.marvelrun.admin.payment.command.evidence.AdminRefundEvidenceModels.*;

/** 외부 GET 전후 스냅샷과 증거만 보존한다. 환불 실행기를 의존하지 않는다. */
@Service
@RequiredArgsConstructor
public class AdminRefundEvidenceService {
    private final AdminRefundEvidenceStore store;
    private final AdminRefundEvidenceClient client;
    private final AdminRefundEvidenceMatcher matcher;
    private final AdminRefundTime time;

    /** 단건 동기 조회다. 배치 상태·계약·정원·결제·취소 상태를 변경하지 않는다. */
    public Evidence check(String eventId,String cancelId,String adminId) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("증거 확인은 외부 트랜잭션 없이 호출해야 합니다."); }
        if(adminId==null || adminId.isBlank() || adminId.length()>64) { throw new IllegalArgumentException("관리자 식별자 오류"); }
        Snapshot before=store.snapshot(eventId,cancelId);
        LocalDateTime started=time.now();
        Lookup lookup=client.lookup(before.paymentKey());
        Decision decision=matcher.compare(before,lookup);
        Snapshot after=store.snapshot(eventId,cancelId);
        if(!before.equals(after)) {
            decision=new Decision(Verdict.LOCAL_CHANGED,"조회 중 서버 기록이 변경되었습니다. 이 증거만으로 현재 정합성을 확정하지 마세요.",decision.observation());
        }
        return store.append(new Evidence(UUID.randomUUID().toString(),eventId,cancelId,before.paymentId(),adminId,
                started,time.now(),lookup.httpStatus(),lookup.errorCode(),decision.verdict(),decision.reason(),
                local(before),local(after),decision.observation(),false,false));
    }
    /** 민감한 내부 키를 응답 스냅샷에서 제외한다. */
    private Local local(Snapshot s) { return new Local(s.localStatus(),s.cancelAmount(),s.transactionKeys().size(),s.transactionKeys().stream().map(AdminRefundEvidenceMatcher::hash).toList()); }
}
