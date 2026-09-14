package kr.co.teambrain.marvelrun.common.inheritance_enum;

public enum EventVisibleStatus {
    OPEN,
    TEST,
    CLOSE;

    // OPEN == 메인 페이지에 표기, 링크 상 진입 및 요청 가능
    // TEST == 메인 페이지에 미표기. 링크 상 진입 및 요청 가능
    // CLOSE == 메인 페이지에 미표기, 링크 상 진입 및 요청 불가(중요)

    public boolean isPublic() {

        boolean isPossibleToRequest = false;

        switch(this) {
            case OPEN, TEST -> {
                isPossibleToRequest = true;
            }
            case CLOSE -> {
                // 명시용
                isPossibleToRequest = false;
            }
        }

        return isPossibleToRequest;
    }
}
