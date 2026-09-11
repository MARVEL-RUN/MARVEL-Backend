package kr.co.teambrain.marvelrun.common.inheritance_enum.cash_receipt;

import java.util.List;

public enum CashReceiptIdentifierType {
    PHONE_NUMBER("휴대전화번호"),
    BUSINESS_REG_NO("사업자등록번호"),
    CASH_RECEIPT_CARD_NO("현금영수증카드번호");

    private final String label;

    CashReceiptIdentifierType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}