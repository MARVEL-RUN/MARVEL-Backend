package kr.co.teambrain.marvelrun.admin.event.command.repository;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 외부결제 적재 트랜잭션의 최종 flush 후 신규 행의 자동 생성 시간만 현장 KST로 보정한다. */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OfflineRegistrationTimestampRepository {
    private final NamedParameterJdbcTemplate jdbc;

    /** 대회와 신규 원장 ID로 범위를 제한하며 보정 실패는 전체 적재 트랜잭션을 롤백한다. */
    public void correctOfflineRegistrationTimestamps(String eventId, List<TimestampTarget> targets) {
        // JDBC 배치도 100명 단위로 제한하며 중간 커밋은 하지 않는다.
        for (int start = 0; start < targets.size(); start += 100) {
            SqlParameterSource[] parameters = targets.subList(start, Math.min(start + 100, targets.size()))
                    .stream().map(target -> new MapSqlParameterSource()
                            .addValue("eventId", eventId)
                            .addValue("registrationId", target.registrationId())
                            .addValue("paymentId", target.paymentId())
                            .addValue("reservationId", target.reservationId())
                            .addValue("paidAtKst", Timestamp.valueOf(target.paidAtKst())))
                    .toArray(SqlParameterSource[]::new);

            // 수정시각은 업로드 시점을 유지하고 신청일시만 현장 시각으로 보정한다.
            validateUpdatedOfflineRows(jdbc.batchUpdate("""
                    UPDATE registration SET registration_date = :paidAtKst
                    WHERE id = :registrationId AND event_id = :eventId AND external_payment = 1
                    """, parameters));

            // 일반 JPA의 updatable=false 및 자동 시각 생성을 우회하되 신규 외부결제 원장만 갱신한다.
            validateUpdatedOfflineRows(jdbc.batchUpdate("""
                    UPDATE payment p JOIN registration r ON r.id = p.registration_id
                    SET p.created_at = :paidAtKst, p.updated_at = :paidAtKst
                    WHERE p.id = :paymentId AND r.id = :registrationId
                      AND r.event_id = :eventId AND r.external_payment = 1
                    """, parameters));
            validateUpdatedOfflineRows(jdbc.batchUpdate("""
                    UPDATE reservation rv JOIN registration r ON r.id = rv.registration_id
                    SET rv.created_at = :paidAtKst, rv.updated_at = :paidAtKst
                    WHERE rv.id = :reservationId AND r.id = :registrationId
                      AND r.event_id = :eventId AND r.external_payment = 1
                    """, parameters));
        }
    }

    /** JDBC 드라이버가 건수를 생략한 성공은 허용하고 대상 누락 또는 다중 갱신은 거절한다. */
    private void validateUpdatedOfflineRows(int[] counts) {
        // 신규 ID에 대응하는 한 행만 보정되어야 한다.
        for (int count : counts) {
            if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
            }
        }
    }

    /** 이번 트랜잭션에서 생성한 세 원장의 ID와 참가자별 현장 결제시각이다. */
    public record TimestampTarget(String registrationId, String paymentId,
            String reservationId, LocalDateTime paidAtKst) { }
}
