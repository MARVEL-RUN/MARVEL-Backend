package kr.co.teambrain.marvelrun.user.discount.query;

/** 현재 구현된 서비스 내 할인 조건의 표시명과 설명 템플릿을 관리한다. 자격 판정은 계산 영역이 담당한다. */
public enum InternalDiscountConditionType {
    AGE("연령", "대회일 기준 만 %d세 이상 %d세 미만인 경우");

    private final String displayName;
    private final String descriptionTemplate;

    InternalDiscountConditionType(String displayName, String descriptionTemplate) {
        this.displayName = displayName;
        this.descriptionTemplate = descriptionTemplate;
    }

    /** 조건의 화면 표시명을 반환한다. */
    public String getDisplayName() {
        return displayName;
    }

    /** 동적 설명문에 사용하는 원본 템플릿을 반환한다. */
    public String getDescriptionTemplate() {
        return descriptionTemplate;
    }

    /** 정책에 저장된 연령 하한 포함·상한 제외 조건을 안내문으로 변환한다. */
    public String describeAgeRange(int minAgeInclusive, int maxAgeExclusive) {
        return descriptionTemplate.formatted(minAgeInclusive, maxAgeExclusive);
    }
}
