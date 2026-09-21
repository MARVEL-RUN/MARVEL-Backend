package kr.co.teambrain.marvelrun.admin.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

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
    INVALID_PASSWORD_LENGTH(HttpStatus.BAD_REQUEST,"신청 비밀번호는 6자리 이상이어야 합니다.")



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