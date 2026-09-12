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

    USER_CANNOT_CHANGE_OWN_INFO_IN_REGISTRATION_PATCH(HttpStatus.BAD_REQUEST, "가입된 사용자의 정보 변경은 회원 정보 변경에서만 가능합니다. 회원정보를 변경 후 시도해주세요."),
    EVENT_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다"),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "대회를 찾을 수 없습니다");


    private final HttpStatus httpStatus;
    private final String message;
}

