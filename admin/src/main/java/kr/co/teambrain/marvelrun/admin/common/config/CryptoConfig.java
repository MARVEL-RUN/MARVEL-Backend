package kr.co.teambrain.marvelrun.admin.common.config;

import kr.co.teambrain.marvelrun.common.crypto.CryptoUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfig {

    @Value("${crypto.aes.secret-key}")
    private String aesSecretKey;

    @Value("${phone-hmac-secret}")
    private String hmacSecretKey;

    @Bean
    public CryptoUtils cryptoUtils() {
        // 순수 Java 객체를 생성하여 스프링 컨테이너에 등록
        return new CryptoUtils(aesSecretKey, hmacSecretKey);
    }
}