package kr.co.teambrain.marvelrun.common.json_object;


import jakarta.validation.constraints.NotBlank;

public record SouvenirJson (

    @NotBlank
    String souvenirId,

    @NotBlank
    String selectedSize
) {}
