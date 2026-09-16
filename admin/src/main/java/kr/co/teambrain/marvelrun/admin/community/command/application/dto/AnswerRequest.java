package kr.co.teambrain.marvelrun.admin.community.command.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AnswerRequest {

    private String title;

    private String content;
}