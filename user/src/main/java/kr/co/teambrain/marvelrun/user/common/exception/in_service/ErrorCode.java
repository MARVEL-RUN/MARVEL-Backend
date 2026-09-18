package kr.co.teambrain.marvelrun.user.common.exception.in_service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    ORGANIZATION_LEADER_BIRTH_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "단체장 생년월일을 입력해주세요."
    ),

    ORGANIZATION_LEADER_MUST_BE_ADULT(
            HttpStatus.BAD_REQUEST,
            "단체장은 신청일 기준 만 19세 이상이어야 합니다."
    ),
    REGISTRATION_POLICY_CONFIGURATION_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "참가신청 정책 설정을 확인할 수 없습니다. 관리자에게 문의해주세요."
    ),

    INVALID_REGISTRATION_BIRTH(
            HttpStatus.BAD_REQUEST,
            "생년월일은 오늘 이전의 유효한 날짜를 yyyy-MM-dd 형식으로 입력해주세요."
    ),

    REGISTRATION_CATEGORY_BIRTH_NOT_ALLOWED(
            HttpStatus.BAD_REQUEST,
            "참가자의 연령 조건으로는 해당 종목을 신청할 수 없습니다."
    ),

    REGISTRATION_SOUVENIR_SIZE_NOT_ALLOWED(
            HttpStatus.BAD_REQUEST,
            "참가자의 연령 조건으로는 해당 기념품 사이즈를 선택할 수 없습니다."
    ),

    GUARDIAN_CONSENT_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "해당 참가자는 법정대리인 동의가 필요합니다."
    ),

    GUARDIAN_NAME_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "해당 참가자는 보호자 이름을 입력해야 합니다."
    ),

    EVENT_PAYMENT_CLOSED(
            HttpStatus.BAD_REQUEST,
            "대회 결제가 마감되었습니다."
    ),

    PAYMENT_POLICY_CONFIGURATION_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "대회 결제 정책 설정을 확인해주세요."
    ),

    //
    // Capacity & Resevation
    //

    /**
     * 정원·예약 처리에 필요한 내부 참조 또는 수량이 잘못된 경우.
     */
    INVALID_RESERVATION_ARGUMENT(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "예약 처리에 필요한 정보가 올바르지 않습니다."
    ),

    /**
     * 필수 정원 설정이 없거나 적용 대상 설정이 잘못된 경우.
     */
    CAPACITY_CONFIGURATION_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "정원 또는 기념품 재고 설정을 확인해주세요."
    ),

    /**
     * 신규 확보가 차단되었거나 남은 수량이 부족한 경우.
     */
    CAPACITY_ACQUIRE_FAILED(
            HttpStatus.CONFLICT,
            "선택한 종목 또는 기념품의 확보 가능한 수량이 부족하거나 접수가 중단되었습니다."
    ),

    /**
     * 예약 내역과 실제 카운터가 일치하지 않아 수량을 이동할 수 없는 경우.
     */
    CAPACITY_COUNTER_MISMATCH(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "예약 수량 처리 중 오류가 발생했습니다."
    ),

    /**
     * 신청에 대응하는 예약이 존재하지 않는 경우.
     */
    RESERVATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "신청의 예약 정보를 찾을 수 없습니다."
    ),

    /**
     * 현재 예약 상태에서 요청한 처리를 수행할 수 없는 경우.
     */
    RESERVATION_STATE_CONFLICT(
            HttpStatus.CONFLICT,
            "현재 예약 상태에서는 요청한 처리를 진행할 수 없습니다."
    ),

    /**
     * 다른 요청이 동일 데이터를 먼저 변경하여 현재 작업을 완료할 수 없는 경우. (낙관적 잠금 충돌)
     */
    CONCURRENT_MODIFICATION(
            HttpStatus.CONFLICT,
            "다른 요청에서 상태가 변경되었습니다. 현재 상태를 다시 확인해주세요."
    ),

    // =========================================================
    // Question
    // =========================================================

    QUESTION_PASSWORD_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "문의글 비밀번호를 입력해주세요."
    ),

    INVALID_QUESTION_PASSWORD(
            HttpStatus.FORBIDDEN,
            "문의글 비밀번호가 일치하지 않습니다."
    ),

    QUESTION_ALREADY_ANSWERED(
            HttpStatus.CONFLICT,
            "답변이 완료된 문의글은 수정할 수 없습니다."
    ),

    QUESTION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "문의글을 찾을 수 없습니다."
    ),


    // =========================================================
    // Attachment
    // =========================================================

    ATTACHMENT_COUNT_EXCEEDED(
            HttpStatus.BAD_REQUEST,
            "첨부파일은 최대 허용 개수를 초과할 수 없습니다."
    ),

    INVALID_ATTACHMENT_DELETE_TARGET(
            HttpStatus.BAD_REQUEST,
            "삭제할 수 없는 첨부파일이 포함되어 있습니다."
    ),

    FILE_LENGTH_EXCEEDED(
            HttpStatus.BAD_REQUEST,
            "개별 파일 용량을 초과합니다. 개별 파일의 크기는 5MB여야 합니다."
    ),


    // =========================================================
    // 기존 코드들
    // =========================================================


    EVENT_REGISTRATION_NOT_STARTED(
            HttpStatus.BAD_REQUEST,
            "대회 접수 시작 전입니다."
    ),

    EVENT_REGISTRATION_CLOSED(
            HttpStatus.BAD_REQUEST,
            "대회 접수가 마감되었습니다."
    ),

    INVALID_EVENT_CATEGORY(
            HttpStatus.BAD_REQUEST,
            "해당 대회에 속하지 않은 종목입니다."
    ),

    INACTIVE_EVENT_CATEGORY(
            HttpStatus.BAD_REQUEST,
            "현재 신청할 수 없는 종목입니다."
    ),

    PAYMENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "결제 정보를 찾을 수 없습니다."
    ),

    PAYMENT_AMOUNT_MISMATCH(
            HttpStatus.BAD_REQUEST,
            "결제 금액이 일치하지 않습니다."
    ),

    PAYMENT_NOT_CONFIRMABLE(
            HttpStatus.CONFLICT,
            "현재 승인할 수 없는 결제입니다."
    ),

    PAYMENT_CONFIRM_FAILED(
            HttpStatus.BAD_REQUEST,
            "결제 승인에 실패했습니다."
    ),

    PAYMENT_CONFIRM_UNKNOWN(
            HttpStatus.SERVICE_UNAVAILABLE,
            "결제 결과를 확인하고 있습니다."
    ),

    EVENT_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다"),
    EVENT_CATEGORY_NOT_ACTIVE(HttpStatus.CONFLICT, "품절 또는 신청이 불가한 종목입니다"),

    INVALID_EVENT_CATEGORY_SOUVENIR(HttpStatus.CONFLICT, "해당 기념품은 해당 종목 내에서 선택할 수 없습니다."),
    SOUVENIR_NOT_ACTIVE(HttpStatus.CONFLICT, "품절 또는 신청이 불가한 기념품입니다"),
    INVALID_SOUVENIR_SIZE(HttpStatus.CONFLICT, "선택된 사이즈는 해당 기념품 내에서 선택할 수 없습니다."),
    DUPLICATE_SOUVENIR_SELECTION(HttpStatus.CONFLICT, "같은 기념품을 중복하여 선택할 수 없습니다."),

    USER_CANNOT_CHANGE_OWN_INFO_IN_REGISTRATION_PATCH(HttpStatus.BAD_REQUEST, "가입된 사용자의 정보 변경은 회원 정보 변경에서만 가능합니다. 회원정보를 변경 후 시도해주세요."),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "대회를 찾을 수 없습니다"),
    EVENT_NOT_OPEN(HttpStatus.CONFLICT, "아직 신청이 불가능한 대회입니다"),
    REGISTRATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 참여한 대회에 다시 신청할 수 없습니다."), 
    MUST_NEED_DELETE_MAP_USER(HttpStatus.INTERNAL_SERVER_ERROR, "'삭제된 사용자' user가 db내에 존재하지않음"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"), 
    NOT_OWNER_QUESTION_ARTICLE(HttpStatus.UNAUTHORIZED, "해당 질문글의 소유자가 아닙니다"),
    ALREADY_ANSWERED_QUESTION(HttpStatus.CONFLICT, "이미답변이 완료된 질문은 수정이 불가능합니다"),
    ANSWER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "답변을 찾을 수 없습니다."
    ),
    MUST_NEED_PASSWORD(HttpStatus.FORBIDDEN,"올바른 비밀번호 입력이 필요합니다"),
    MUST_NEED_GUEST_NAMED_USER(HttpStatus.INTERNAL_SERVER_ERROR, "question 매핑 목적의 '비회원' user가 db내에 존재하지않음"), 
    NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 공지를 찾을 수 없습니다");
    
    private final HttpStatus httpStatus;
    private final String message;
}

