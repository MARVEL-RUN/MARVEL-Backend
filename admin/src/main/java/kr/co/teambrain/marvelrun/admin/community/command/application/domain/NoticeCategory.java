package kr.co.teambrain.marvelrun.admin.community.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.NoticeCategoryBase;
import lombok.Getter;

@Getter
@Entity
@Table(name = "notice_category")
public class NoticeCategory extends NoticeCategoryBase {

}
