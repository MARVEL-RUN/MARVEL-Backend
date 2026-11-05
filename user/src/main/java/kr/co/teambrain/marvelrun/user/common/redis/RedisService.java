package kr.co.teambrain.marvelrun.user.common.redis;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
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


    /*
     * 참가자 refresh. 관리자 refresh(refresh:{adminId})와 겹치지 않게 접두어를 이곳에서만 붙인다.
     */
    private static final String PARTICIPANT_REFRESH_PREFIX = "refresh:participant:";

    /** 저장된 값이 expected일 때만 새 값과 만료로 바꾼다. 비교와 교체 사이에 로그아웃·다른 재발급이 끼지 못한다. */
    private static final RedisScript<Long> COMPARE_AND_SET_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) return 1 "
                    + "else return 0 end",
            Long.class
    );

    public void saveParticipantRefreshToken(
            String registrationId,
            String refreshToken,
            long duration,
            TimeUnit unit
    ) {

        redisTemplate
                .opsForValue()
                .set(
                        PARTICIPANT_REFRESH_PREFIX
                                + registrationId,
                        refreshToken,
                        duration,
                        unit
                );
    }


    public boolean isValidParticipantRefreshToken(
            String registrationId,
            String providedToken
    ) {

        return providedToken.equals(
                redisTemplate
                        .opsForValue()
                        .get(
                                PARTICIPANT_REFRESH_PREFIX
                                        + registrationId
                        )
        );
    }


    public boolean replaceParticipantRefreshToken(
            String registrationId,
            String expectedToken,
            String newToken,
            long durationMillis
    ) {

        Long replaced =
                redisTemplate.execute(
                        COMPARE_AND_SET_SCRIPT,
                        List.of(
                                PARTICIPANT_REFRESH_PREFIX
                                        + registrationId
                        ),
                        expectedToken,
                        newToken,
                        String.valueOf(durationMillis)
                );

        return Long.valueOf(1).equals(replaced);
    }


    public void deleteParticipantRefreshToken(
            String registrationId
    ) {

        redisTemplate.delete(
                PARTICIPANT_REFRESH_PREFIX
                        + registrationId
        );
    }
}