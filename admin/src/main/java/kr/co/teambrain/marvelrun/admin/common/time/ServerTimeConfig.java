package kr.co.teambrain.marvelrun.admin.common.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class ServerTimeConfig {

    @Bean
    public Clock serverClock() {
        return Clock.system(
                ZoneId.of("Asia/Seoul")
        );
    }
}