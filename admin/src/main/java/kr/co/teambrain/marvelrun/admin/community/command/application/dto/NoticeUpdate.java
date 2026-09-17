package kr.co.teambrain.marvelrun.admin.community.command.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@ToString
public class NoticeUpdate {

    private String title;

    private String content;

    private String categoryId;

    private List<String> deleteFileUrls;
}
