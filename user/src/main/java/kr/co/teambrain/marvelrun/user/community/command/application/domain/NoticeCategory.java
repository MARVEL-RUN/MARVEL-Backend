package kr.co.teambrain.marvelrun.user.community.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.NoticeBase;
import kr.co.teambrain.marvelrun.common.entity.NoticeCategoryBase;
import kr.co.teambrain.marvelrun.user.common.entities.Admin;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@Entity
@Table(name = "notice_category")
public class NoticeCategory extends NoticeCategoryBase {

}
