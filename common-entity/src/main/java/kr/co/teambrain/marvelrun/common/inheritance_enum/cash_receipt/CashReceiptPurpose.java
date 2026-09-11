package kr.co.teambrain.marvelrun.common.inheritance_enum.cash_receipt;

public enum CashReceiptPurpose {
    INCOME_DEDUCTION("소득공제"),
    EXPENSE_PROOF("지출증빙");

    private final String label;

    CashReceiptPurpose(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}