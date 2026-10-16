package kr.co.teambrain.marvelrun.user.discount.query;

import java.util.List;

/** 기존 종목 옵션에 덧붙이는 서비스 내 할인 안내다. */
public record RegistrationPricingOptionsResponse(List<PriceOptionResponse> serviceDiscountOptions) {
}
