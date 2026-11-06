package kr.co.teambrain.marvelrun.user.common.security.config;

import kr.co.teambrain.marvelrun.user.common.security.filter.PublicRegistrationAccessInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final PublicRegistrationAccessInterceptor publicRegistrationAccessInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 검사 대상은 인터셉터가 메서드 표시로 고르므로 경로를 제한하지 않는다.
        registry.addInterceptor(publicRegistrationAccessInterceptor);
    }
}
