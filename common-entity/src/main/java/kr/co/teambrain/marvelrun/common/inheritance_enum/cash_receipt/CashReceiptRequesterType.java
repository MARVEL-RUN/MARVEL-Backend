package kr.co.teambrain.marvelrun.common.inheritance_enum.cash_receipt;

public enum CashReceiptRequesterType {
    INDIVIDUAL("개인"),
    BUSINESS("사업자");

    private final String label;

    CashReceiptRequesterType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}