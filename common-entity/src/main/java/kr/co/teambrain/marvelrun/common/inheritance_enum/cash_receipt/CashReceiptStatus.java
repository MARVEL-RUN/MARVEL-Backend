package kr.co.teambrain.marvelrun.common.inheritance_enum.cash_receipt;

/** 현금 영수증 처리 상태 */
public enum CashReceiptStatus {
    REQUESTED("처리 대기"), // 처리 대기중

    COMPLETED("발급 완료"), // 발급 완료
    CANCELED("발급 취소"); // 발급 취소

    private final String label;
    CashReceiptStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
