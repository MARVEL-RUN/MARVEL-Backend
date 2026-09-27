package kr.co.teambrain.marvelrun.user.event.command.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 최초·추가 단체원의 기존 고정 비밀번호를 공통 정책으로 해시한다. */
@Component
@RequiredArgsConstructor
public class OrgParticipantPasswordEncoder {
    private static final String DEFAULT_PASSWORD = "%^MVP_ORG_T&*EM^&#P_PA$%SSWO@!RD";
    private final PasswordEncoder passwordEncoder;

    /** 참가자 생성마다 새 솔트의 해시를 만들며 평문이나 해시를 캐시하지 않는다. */
    public String encode() {
        return passwordEncoder.encode(DEFAULT_PASSWORD);
    }
}
