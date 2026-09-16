package kr.co.teambrain.marvelrun.user.community.command.application.dto;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ArticlePatchRequestWrapperWithPassword {
    @Valid
    private ArticlePatchRequest patch;

    private String password;

}
