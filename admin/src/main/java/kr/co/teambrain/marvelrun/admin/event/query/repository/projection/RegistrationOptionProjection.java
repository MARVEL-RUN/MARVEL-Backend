package kr.co.teambrain.marvelrun.admin.event.query.repository.projection;

import java.math.BigDecimal;

/** 종목·기념품 연결 조회의 표시값을 전달하는 읽기 전용 projection이다. */
public interface RegistrationOptionProjection {

    Long getEventCategoryOrder();

    String getEventCategoryId();

    String getEventCategoryName();

    BigDecimal getEventCategoryAmount();

    Boolean getEventCategoryIsActive();


    Long getSouvenirOrder();

    String getSouvenirId();

    String getSouvenirName();

    String getSouvenirSizes();

    String getSouvenirIsActive();
}
