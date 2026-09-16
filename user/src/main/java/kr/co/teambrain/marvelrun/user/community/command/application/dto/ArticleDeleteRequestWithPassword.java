package kr.co.teambrain.marvelrun.user.community.command.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ArticleDeleteRequestWithPassword {

    private String password;
}