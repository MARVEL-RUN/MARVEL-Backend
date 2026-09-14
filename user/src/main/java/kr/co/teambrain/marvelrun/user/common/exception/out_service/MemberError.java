package kr.co.teambrain.marvelrun.user.common.exception.out_service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 배치 요청에서 한 항목(한 명)의 특정 필드 에러를 나타낸다. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberError {
    /** 입력 리스트 상의 행 번호(1부터 시작). 단건이면 null 가능 */
    private Integer row;

    /** requestBody의 어떠한 목적 범위에서 오류가 났는지 (예: "ORGANIZATION", "REGI_LIST")  */
    private String field;

    /** DTO 기준 어떤 필드에서 오류가 났는지 (예: "name", "phNum", "eventCategoryId") */
    private String target;

    /** 에러 코드(머신 리더블). 예: "REQUIRED", "DUPLICATE", "NOT_FOUND", "INVALID_FORMAT" */
    private String code;

    /** 사람이 읽을 수 있는 설명 */
    private String message;

    /** 거절된 값(있으면 포함) */
    private Object rejectedValue;
}