package kr.co.teambrain.marvelrun.common.inheritance_enum;

// 상품(종목)이 참가 항목을 몇 개 만드는지 정한다. NULL이면 참가 항목을 만들지 않는다(기존 대회).
public enum ProductType {
    SINGLE,  // 종목 하나. 구매 줄마다 받는 사람을 입력받아 참가 항목 1개
    PACKAGE, // parent_category_id로 이 종목을 가리키는 하위 종목들. 종목마다 받는 사람을 따로 입력
    OPTION,  // 옵션 상품. 단독으로 신청하지 않는다
}
