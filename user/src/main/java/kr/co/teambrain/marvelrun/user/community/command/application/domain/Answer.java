package kr.co.teambrain.marvelrun.user.community.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.AnswerBase;
import kr.co.teambrain.marvelrun.user.common.entities.Admin;
import lombok.Getter;

@Getter
@Entity
@Table(name = "answer", schema = "kma")
public class Answer extends AnswerBase<Admin, Question> {

}