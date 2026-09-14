package kr.co.teambrain.marvelrun.user.event.query.repository.projection;

import java.math.BigDecimal;

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