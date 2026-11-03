package kr.co.teambrain.marvelrun.user.common.security.config;

import kr.co.teambrain.marvelrun.user.common.security.filter.ParticipantAuthMethodInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final ParticipantAuthMethodInterceptor participantAuthMethodInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(participantAuthMethodInterceptor)
                .addPathPatterns(ParticipantAuthMethodInterceptor.PATH_PATTERNS);
    }
}
