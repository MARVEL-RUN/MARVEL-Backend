package kr.co.teambrain.marvelrun.admin.event.query.dto.report;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 배송 명단 조회와 판별 사이의 명시적인 데이터 계약을 모은다. ID는 내부 연결에만 사용한다. */
public final class RegistrationDeliveryReportModels {
    /** 상태를 보유하지 않는 데이터 계약 모음이다. */
    private RegistrationDeliveryReportModels() { }

    /** 파일 상단의 대회 정보다. */
    public record EventInfo(String id, String name, LocalDateTime startDate) { }

    /** 현재 신청과 최초 승인일의 조회 결과다. 승인일만 UTC이고 나머지 업무 일시는 KST다. */
    public record Candidate(String id, String status, boolean deleted, String name, String birth, String phone,
            String organizationId, String groupName, String categoryId, String categoryName, String souvenirsJson,
            String address, String addressDetail, LocalDateTime registrationAt, LocalDateTime firstApprovedUtc,
            BigDecimal contractAmount, BigDecimal paidAmount) { }

    /** 개별 신청에 귀속되는 결제·환불 증거다. 단체 전체 금액을 사용하지 않는다. */
    public record PaymentFact(String registrationId, String paymentId, String purpose, String processStatus,
            LocalDateTime approvedUtc, BigDecimal amount, BigDecimal refundedAmount, boolean refundUnsettled,
            boolean refundAttributionMissing, String tossStatus) { }

    /** 예약 상세와 필요할 때만 조회한 JSON 이력이다. */
    public record ReservationFact(String registrationId, String id, String status, String history,
            List<CapacityItem> items) { }

    /** 현재 또는 과거 확보 구성의 자원과 수량이다. */
    public record CapacityItem(String capacityId, int quantity) { }

    /** 자원 해석에 사용하는 현재 종목 연결과 기념품 정보다. */
    public record CapacityInfo(String id, String type, String souvenirId, String souvenirName, String size,
            List<String> categoryIds, List<String> categoryNames) { }

    /** 동일 순서의 기념품 이름과 사이즈를 출력하는 구성이다. */
    public record Selection(String category, String souvenirs, String sizes) {
        /** 판별할 근거가 없는 구성임을 명시한다. */
        public static Selection unknown() { return new Selection("판별 불가", "판별 불가", "판별 불가"); }
    }

    /** 한 신청의 명단 포함 여부와 불명확 사유다. */
    public record Classification(boolean excluded, List<String> reasons) {
        /** 불명확 사유가 있는 신청만 이력 해석 단계로 보낸다. */
        public boolean unclear() { return !excluded && !reasons.isEmpty(); }
    }

    /** 이력에서 복원한 기존 구성과 관리자가 확인할 근거다. */
    public record HistoryResult(Selection previous, String result, String reason, String text) { }

    /** 최종 엑셀 한 행의 자료다. */
    public record ExportRow(Candidate candidate, Selection current, Classification classification,
            HistoryResult history) { }
}
