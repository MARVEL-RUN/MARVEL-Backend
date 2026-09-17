package kr.co.teambrain.marvelrun.admin.community.command.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class NoticeCreate {

    private String categoryId;

    private String title;

    private String content;
}
