package kr.co.teambrain.marvelrun.user.event.query.repository.projection;

import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;

import java.math.BigDecimal;

public interface RegistrationOptionProjection {

    Long getEventCategoryOrder();

    String getEventCategoryId();

    String getEventCategoryName();

    BigDecimal getEventCategoryAmount();

    Boolean getEventCategoryIsActive();

    ProductType getEventCategoryProductType();

    String getEventCategoryParentCategoryId();


    Long getSouvenirOrder();

    String getSouvenirId();

    String getSouvenirName();

    String getSouvenirSizes();

    String getSouvenirIsActive();
}