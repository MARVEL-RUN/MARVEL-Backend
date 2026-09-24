package kr.co.teambrain.marvelrun.admin.event.query.report;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RegistrationExcelFileNames {

    PARENT_CHILD_PAYMENT_CONFIRMED("아동유무별결제자신청자집계");


    private String fileName;

}
