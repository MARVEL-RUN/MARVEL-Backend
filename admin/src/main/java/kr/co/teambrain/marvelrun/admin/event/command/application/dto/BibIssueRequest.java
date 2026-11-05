package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** 배번을 발급할 차수의 마감 시각(KST)이다. */
public record BibIssueRequest(@NotNull LocalDateTime deadline) { }
