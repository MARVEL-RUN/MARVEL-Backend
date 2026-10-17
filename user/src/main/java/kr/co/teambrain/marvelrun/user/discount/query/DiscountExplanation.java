package kr.co.teambrain.marvelrun.user.discount.query;

/** 가격 안내 시나리오와 설명 출처를 관리한다. enum 이름은 기존 JSON 시나리오 값을 유지한다. */
public enum DiscountExplanation {
    STANDARD("모든 서비스 내 할인 조건에 해당하지 않는 경우", null),
    AGE_CONDITION(null, InternalDiscountConditionType.AGE);

    private final String fixedDescription;
    private final InternalDiscountConditionType conditionType;

    DiscountExplanation(String fixedDescription, InternalDiscountConditionType conditionType) {
        this.fixedDescription = fixedDescription;
        this.conditionType = conditionType;
    }

    /** 고정 설명문을 반환한다. 조건형 시나리오는 조건 enum의 템플릿을 사용한다. */
    public String getFixedDescription() {
        return fixedDescription;
    }

    /** 시나리오의 내부 할인 조건 종류를 반환한다. 일반가는 특정 조건에 종속되지 않는다. */
    public InternalDiscountConditionType getConditionType() {
        return conditionType;
    }
}
