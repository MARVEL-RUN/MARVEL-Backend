package kr.co.teambrain.marvelrun.user.event.query.dto;

/** 현재 선택한 기념품의 식별자·표기명·사이즈·수량이다. */
public record RegistrationSouvenirResponse(
        String souvenirId, String name, String selectedSize, int quantity
) { }
