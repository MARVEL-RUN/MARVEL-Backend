package kr.co.teambrain.marvelrun.admin.capacity.command.application.dto;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;

/*
 * 사용자 서버 참조 시각: 2026-10-02 17:18:56 KST
 * 참조 파일: user/src/main/java/kr/co/teambrain/marvelrun/user/capacity/command/application/dto/CapacityHoldRequest.java
 * 동일한 신청 및 어린이 정원 판정 입력을 사용한다.
 */
/** 정책 검증과 신청 저장을 마친 참가자의 자원 확보 입력이다. */
public record CapacityHoldRequest(Registration registration, boolean child) { }
