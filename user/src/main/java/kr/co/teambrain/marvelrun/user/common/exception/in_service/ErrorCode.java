package kr.co.teambrain.marvelrun.user.common.exception.in_service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {

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
    REGISTRATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 참여한 대회에 다시 신청할 수 없습니다.");


    private final HttpStatus httpStatus;
    private final String message;
}

