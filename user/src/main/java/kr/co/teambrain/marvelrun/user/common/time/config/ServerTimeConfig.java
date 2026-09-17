package kr.co.teambrain.marvelrun.user.common.time.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * User 서버에서 공통으로 사용하는 시간 기준.
 *
 * 서버 OS의 기본 시간대와 무관하게 한국 시간을 사용한다.
 * 신청·결제 등 시간 기반 판단이 필요한 기능에서 Clock을 주입받는다.
 *
 * 테스트에서는 고정 Clock으로 경계 시각을 재현할 수 있다.
 */
@Configuration(proxyBeanMethods = false)
public class ServerTimeConfig {

    @Bean
    public Clock serverClock() {
        return Clock.system(
                ZoneId.of("Asia/Seoul")
        );
    }
}