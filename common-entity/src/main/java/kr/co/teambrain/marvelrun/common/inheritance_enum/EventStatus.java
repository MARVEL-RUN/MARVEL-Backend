package kr.co.teambrain.marvelrun.common.inheritance_enum;

public enum EventStatus {
    PENDING, // 보류
    OPEN, // 오픈
    CLOSED, // 마감
    FINAL_CLOSED, // 내부 마감
    UPLOAD_APPLYING, // (신규) 지역대회 전용. 업로드 신청중
    ;


    public static String toUserViewString(EventStatus targetStatus) {
        String result = null;

        switch(targetStatus) {
            case PENDING -> result = "보류 중";
            case OPEN -> result = "접수 중";
            case CLOSED -> result = "접수 마감";
            case FINAL_CLOSED -> result = "내부 마감";
            case UPLOAD_APPLYING -> result = "신규 접수 중"; // 해당 명칭은 사용자 서버에서 표기될 수 없어야함. 추후 변경점이 발생할 수 있으므로 우선 기입.
        }

        return result;
    }
}
