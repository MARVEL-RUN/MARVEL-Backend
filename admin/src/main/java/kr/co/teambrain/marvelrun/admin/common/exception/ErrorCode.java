package kr.co.teambrain.marvelrun.admin.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // 사용자 금융 경로와 동일한 오류 식별자. 결제 상태 enum 추가가 아니다.
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "다른 요청이 처리 중입니다."),
    REGISTRATION_MODIFICATION_PAYMENT_CONFLICT(HttpStatus.CONFLICT, "관련 결제 처리가 완료되지 않았습니다."),
    PAYMENT_CANCEL_CONFLICT(HttpStatus.CONFLICT, "관련 환불 처리가 완료되지 않았습니다."),
    PAYMENT_CANCEL_INTEGRITY_ERROR(HttpStatus.CONFLICT, "환불 원장 정합성을 확인해야 합니다."),

    CAPACITY_ACQUIRE_FAILED(
            HttpStatus.CONFLICT,
            "선택한 종목 또는 기념품의 확보 가능한 수량이 부족하거나 접수가 중단되었습니다."
    ),
    CAPACITY_CONFIGURATION_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "정원 또는 기념품 재고 설정을 확인해주세요."
    ),
    CAPACITY_COUNTER_MISMATCH(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "예약 수량 처리 중 오류가 발생했습니다."
    ),
    DUPLICATE_SOUVENIR_SELECTION(HttpStatus.CONFLICT, "같은 기념품을 중복하여 선택할 수 없습니다."),
    EVENT_CATEGORY_NOT_ACTIVE(HttpStatus.CONFLICT, "품절 또는 신청이 불가한 종목입니다"),
    EVENT_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다"),
    EVENT_NOT_OPEN(HttpStatus.CONFLICT, "아직 신청이 불가능한 대회입니다"),
    EVENT_REGISTRATION_CLOSED(
            HttpStatus.BAD_REQUEST,
            "대회 접수가 마감되었습니다."
    ),
    EVENT_REGISTRATION_NOT_STARTED(
            HttpStatus.BAD_REQUEST,
            "대회 접수 시작 전입니다."
    ),
    GUARDIAN_CONSENT_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "해당 참가자는 법정대리인 동의가 필요합니다."
    ),
    GUARDIAN_NAME_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "해당 참가자는 보호자 이름을 입력해야 합니다."
    ),
    INVALID_EVENT_CATEGORY_SOUVENIR(HttpStatus.CONFLICT, "해당 기념품은 해당 종목 내에서 선택할 수 없습니다."),
    INVALID_REGISTRATION_BIRTH(
            HttpStatus.BAD_REQUEST,
            "생년월일은 오늘 이전의 유효한 날짜를 yyyy-MM-dd 형식으로 입력해주세요."
    ),
    INVALID_REGISTRATION_MODIFICATION_ARGUMENT(
            HttpStatus.BAD_REQUEST,
            "신청 수정 정보가 올바르지 않습니다."
    ),
    INVALID_RESERVATION_ARGUMENT(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "예약 처리에 필요한 정보가 올바르지 않습니다."
    ),
    INVALID_SOUVENIR_SIZE(HttpStatus.CONFLICT, "선택된 사이즈는 해당 기념품 내에서 선택할 수 없습니다."),
    ORGANIZATION_LEADER_BIRTH_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "단체장 생년월일을 입력해주세요."
    ),
    ORGANIZATION_LEADER_MUST_BE_ADULT(
            HttpStatus.BAD_REQUEST,
            "단체장은 대회일 기준 만 14세 이상이어야 합니다."
    ),
    PAYMENT_ALLOCATION_INTEGRITY_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "결제 금액 귀속 정보가 일치하지 않습니다."
    ),
    PAYMENT_CANCEL_AMOUNT_EXCEEDED(
            HttpStatus.CONFLICT,
            "환불 요청 금액이 환불 가능한 금액을 초과합니다."
    ),
    REGISTRATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 참여한 대회에 다시 신청할 수 없습니다."),
    REGISTRATION_CATEGORY_BIRTH_NOT_ALLOWED(
            HttpStatus.BAD_REQUEST,
            "참가자의 연령 조건으로는 해당 종목을 신청할 수 없습니다."
    ),
    REGISTRATION_FINANCIAL_STATE_INVALID(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "신청의 결제금액 상태를 확인해주세요."
    ),
    REGISTRATION_POLICY_CONFIGURATION_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "참가신청 정책 설정을 확인할 수 없습니다. 관리자에게 문의해주세요."
    ),
    REGISTRATION_SOUVENIR_SIZE_NOT_ALLOWED(
            HttpStatus.BAD_REQUEST,
            "참가자의 연령 조건으로는 해당 기념품 사이즈를 선택할 수 없습니다."
    ),
    RESERVATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "신청의 예약 정보를 찾을 수 없습니다."
    ),
    RESERVATION_STATE_CONFLICT(
            HttpStatus.CONFLICT,
            "현재 예약 상태에서는 요청한 처리를 진행할 수 없습니다."
    ),
    SOUVENIR_NOT_ACTIVE(HttpStatus.CONFLICT, "품절 또는 신청이 불가한 기념품입니다"),

    QUESTION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "문의글을 찾을 수 없습니다."
    ),
    REGISTRATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "해당 신청 내역을 찾을 수 없습니다."),
    ORGANIZATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "해당 단체 정보를 찾을 수 없습니다."
    ),
    ANSWER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "답변을 찾을 수 없습니다."
    ),

    QUESTION_ALREADY_ANSWERED(
            HttpStatus.CONFLICT,
            "이미 답변이 완료된 문의글입니다."
    ),

    ADMIN_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "관리자를 찾을 수 없습니다."
    ),

    AUTHENTICATION_FAILED(
            HttpStatus.UNAUTHORIZED,
            "아이디 또는 비밀번호가 올바르지 않습니다."
    ),

    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "서버 내부 오류가 발생했습니다."
    ), 
    NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "공지사항을 찾을 수 없습니다"), 
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "대회을 찾을 수 없습니다"),
    NOTICE_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "공지 카테고리를 찾을 수 없습니다"),
    INVALID_REGISTRATION_MODIFICATION_TARGET(HttpStatus.BAD_REQUEST, "단체 소속 신청건은 개별적으로 비밀번호를 변경할 수 없습니다."),
    INVALID_PASSWORD_LENGTH(HttpStatus.BAD_REQUEST,"신청 비밀번호는 6자리 이상이어야 합니다."),


    /** 임시 운영 차단: 기념품 정책과 관리자 화면 보완 후 별도 해제한다. */
    ADMIN_ADJUSTMENT_AGE_GROUP_TEMPORARILY_BLOCKED(HttpStatus.CONFLICT,
            "어른↔어린이 구분이 변경되는 관리자 수정은 현재 임시 중단되었습니다."),
    /** 임시 운영 차단: 종목 상향 변경의 추가 결제 흐름 검증 후 별도 해제한다. */
    ADMIN_ADJUSTMENT_CATEGORY_PRICE_INCREASE_TEMPORARILY_BLOCKED(HttpStatus.CONFLICT,
            "참가비가 올라가는 종목 변경은 현재 임시 중단되었습니다."),

    AGE_RESTRICTION_VIOLATION(HttpStatus.BAD_REQUEST, "만 12세 이하(2013년 11월 1일 이후 출생자)는 10Km 코스에 참가할 수 없습니다."),
    GUARDIAN_INFO_REQUIRED(HttpStatus.BAD_REQUEST, "만 12세 이하 참가자로 변경 시 보호자 정보 입력이 필수입니다."),
    PRICE_TIER_CHANGE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "생년월일 변경으로 참가비가 달라집니다. 신청 정보·금액 조정 기능으로 변경해 주세요. 차액은 환불 또는 추가 납부로 처리됩니다."),
    DUPLICATE_REGISTRATION(HttpStatus.BAD_REQUEST, "동일한 정보(이름, 연락처, 생년월일)를 가진 다른 활성 참가자가 이미 존재합니다."),
    DUPLICATE_GROUP_NAME(HttpStatus.BAD_REQUEST, "이미 해당 대회에 동일한 이름의 단체가 존재합니다."),
    ;


    private final HttpStatus httpStatus;
    private final String message;


    ErrorCode(
            HttpStatus httpStatus,
            String message
    ) {

        this.httpStatus = httpStatus;
        this.message = message;
    }
}