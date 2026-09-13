package kr.co.teambrain.marvelrun.user.common.security.service;

import kr.co.teambrain.marvelrun.user.common.security.config.CustomUserDetail;
import kr.co.teambrain.marvelrun.user.userinfo.command.Repository.UserCommandRepository;
import kr.co.teambrain.marvelrun.user.userinfo.command.application.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomUserDetailService implements UserDetailsService {
    private final UserCommandRepository userCommandRepository;

    // Security의 UserName == 로그인 목적의 아이디를 의미
    // 오인 하지않기! account는 unique로 설계되어 후보키이기에 사용자 특정 가능
    /** 사용자 아이디 기반 검색하여 CustomUserDetail 반환 */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String accountId) throws UsernameNotFoundException {

        User user = userCommandRepository.findByAccount(accountId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found by accountId : " + accountId)); // UserDetailsService의 Exception은 CustomError 등으로 래핑할 경우 Spring이 “인증 제공자 내부 오류”로 판단해 에러를 일으킴.


        return new CustomUserDetail(user);
    }

    /** 사용자 기본키 기반 검색하여 CustomUserDetail 반환 */
    @Transactional(readOnly = true)
    public UserDetails loadUserById(String id) throws UsernameNotFoundException {
        User user = userCommandRepository.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("User not found by id : " + id)); // UserDetailsService의 Exception은 CustomError 등으로 래핑할 경우 Spring이 “인증 제공자 내부 오류”로 판단해 에러를 일으킴.


        return new CustomUserDetail(user);
    }

}
