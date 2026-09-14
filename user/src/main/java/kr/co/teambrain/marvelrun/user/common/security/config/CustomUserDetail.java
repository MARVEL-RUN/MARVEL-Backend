package kr.co.teambrain.marvelrun.user.common.security.config;

import kr.co.teambrain.marvelrun.user.userinfo.command.application.domain.User;
import lombok.Data;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Data
public class CustomUserDetail implements UserDetails {

    private final User user;

    // 일반 로그인
    public CustomUserDetail(User user) {
        this.user = user;
    }

    // 해당 User가 임시 사용자 / 일반 사용자 / 단체장 사용자 중 무엇인지 확인
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {

        String roleName = "ROLE_" + user.getAuth();
        return List.of(new SimpleGrantedAuthority(roleName)); // 오버라이드로 재사용하는거라 Collection 형식으로 반환할 뿐, 본 서비스 상 실제로 여러 값이 들어가는 것은 아님.

    }

    @Override
    public String getPassword() {
        return user.getAccountPassword();
    }

    // Returns the username used to authenticate -> 사용자 명이나 닉네임이 아닌, PK 값 반환 목적.
    @Override
    public String getUsername() {
        return String.valueOf(user.getId());
    }
}