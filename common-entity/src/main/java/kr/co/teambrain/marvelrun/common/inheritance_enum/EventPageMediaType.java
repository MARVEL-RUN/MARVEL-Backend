package kr.co.teambrain.marvelrun.common.inheritance_enum;

public enum EventPageMediaType {
    IMAGE, VIDEO_LINK;

    public static String name(EventPageMediaType mediaType) {
        return switch (mediaType) {
            case IMAGE -> "이미지";
            case VIDEO_LINK -> "동영상 링크";
        };
    }
}
