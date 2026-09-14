package kr.co.teambrain.marvelrun.common.inheritance_enum;

public enum PageType {
    EVENT_OUTLINE_PAGE,
    SOUVENIR_PAGE,
    MEETING_PLACE_PAGE,
    COURSE_PAGE,
    NOTICE_PAGE;

    public static String name(PageType pageType) {
        return switch (pageType) {
            case EVENT_OUTLINE_PAGE -> "대회 요강 페이지";
            case COURSE_PAGE -> "코스 페이지";
            case MEETING_PLACE_PAGE -> "집결 출발 페이지";
            case NOTICE_PAGE -> "유의 사항 페이지";
            case SOUVENIR_PAGE -> "기념품 페이지";
        };
    }
}
