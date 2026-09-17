package kr.co.teambrain.marvelrun.admin.common.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisService {

    private final StringRedisTemplate
            redisTemplate;


    public void saveBlacklistAccessToken(
            String accessToken,
            long duration,
            TimeUnit unit
    ) {

        redisTemplate
                .opsForValue()
                .set(
                        "blacklist:access:"
                                + accessToken,
                        "true",
                        duration,
                        unit
                );
    }


    public boolean isBlacklisted(
            String accessToken
    ) {

        return Boolean.TRUE
                .toString()
                .equals(
                        redisTemplate
                                .opsForValue()
                                .get(
                                        "blacklist:access:"
                                                + accessToken
                                )
                );
    }


    public void saveWhitelistRefreshToken(
            String adminId,
            String refreshToken,
            long duration,
            TimeUnit unit
    ) {

        redisTemplate
                .opsForValue()
                .set(
                        "refresh:"
                                + adminId,
                        refreshToken,
                        duration,
                        unit
                );
    }


    public boolean isValidRefreshToken(
            String adminId,
            String providedToken
    ) {

        String savedToken =
                redisTemplate
                        .opsForValue()
                        .get(
                                "refresh:"
                                        + adminId
                        );


        return providedToken.equals(
                savedToken
        );
    }


    public void deleteRefreshToken(
            String adminId
    ) {

        redisTemplate.delete(
                "refresh:"
                        + adminId
        );
    }
}