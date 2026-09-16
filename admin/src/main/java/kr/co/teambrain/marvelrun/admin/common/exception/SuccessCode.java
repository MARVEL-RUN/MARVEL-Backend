package kr.co.teambrain.marvelrun.admin.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
@Getter
public enum SuccessCode {

    //SUCCESS(상태코드, "성공 문구");

    // 200
    SUCCESS(HttpStatus.OK, "OK"),
    LOGIN_SUCCESS(HttpStatus.OK, "로그인 성공"),
    LOGOUT_SUCCESS(HttpStatus.OK, "로그아웃 성공"),
    REFRESH_SUCCESS(HttpStatus.OK, "Refresh 요청 성공"),
    DEV_TOKEN_CREATE_SUCCESS(HttpStatus.OK, "개발자 전용 토큰 생성 성공"),

    UPDATE_SUCCESS(HttpStatus.OK, "수정 성공"),

    // 201
    CREATE_SUCCESS(HttpStatus.CREATED, "생성 완료"),
    BATCH_SUCCESS(HttpStatus.OK, "생성/삭제 완료"),

    // PAGE_REVISION_REVERT_SUCCESS(HttpStatus.OK, "대상 버전으로 되돌리기 성공");

    // 204
    NO_CONTENT(HttpStatus.NO_CONTENT, "요청 처리 완료. 반환 내역 없음."),
    DELETE_SUCCESS(HttpStatus.NO_CONTENT, "삭제 처리 완료.")
    ;

    private final HttpStatus httpStatus;
    private final String message;
}
