package kr.co.teambrain.marvelrun.admin.security.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.AuthErrorCode;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.JwtAuthenticationException;
import kr.co.teambrain.marvelrun.admin.common.redis.RedisService;
import kr.co.teambrain.marvelrun.admin.security.config.TokenProperties;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantTokenRole;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Key;

@Component
public class JwtUtil {

    private final JwtParser jwtParser;

    private final RedisService redisService;


    public JwtUtil(
            TokenProperties tokenProperties,
            RedisService redisService
    ) {

        byte[] keyBytes =
                Decoders.BASE64.decode(
                        tokenProperties.secret()
                );


        Key key =
                Keys.hmacShaKeyFor(
                        keyBytes
                );


        this.jwtParser =
                Jwts.parserBuilder()
                        .setSigningKey(
                                key
                        )
                        .requireIssuer(
                                tokenProperties.issuer()
                        )
                        .build();


        this.redisService =
                redisService;
    }

    public Claims validateAccessToken(
            String accessToken
    ) {

        Claims claims =
                parseClaims(
                        accessToken,
                        true
                );


        validateTokenType(
                claims,
                JwtTokenProvider.ACCESS_TOKEN_TYPE
        );


        if (redisService.isBlacklisted(
                accessToken
        )) {

            throw new JwtAuthenticationException(
                    AuthErrorCode.BLACKLISTED_ACCESS_TOKEN
            );
        }


        return claims;
    }

    public Claims validateRefreshToken(
            String refreshToken
    ) {

        Claims claims =
                parseClaims(
                        refreshToken,
                        false
                );


        validateTokenType(
                claims,
                JwtTokenProvider.REFRESH_TOKEN_TYPE
        );


        return claims;
    }


    /**
     * 두 앱이 서명 키를 같이 쓰므로 사용자 앱이 발급한 참가자 토큰도 서명 검증을 통과한다.
     * role이 참가자 역할이면 거부한다.
     * 관리자 앱에서 토큰으로 인증하는 경로를 새로 만들면 반드시 이 메서드를 거친다.
     */
    public void requireAdminRole(
            Claims claims
    ) {

        if (ParticipantTokenRole.includes(
                claims.get(
                        "role",
                        String.class
                )
        )) {

            throw new JwtAuthenticationException(
                    AuthErrorCode.NOT_ADMIN_TOKEN
            );
        }
    }


    public Authentication getAuthentication(
            Claims claims
    ) {

        String adminId =
                claims.getSubject();

        String adminName =
                claims.get(
                        "name",
                        String.class
                );

        String role =
                claims.get(
                        "role",
                        String.class
                );


        if (adminId == null
                || adminId.isBlank()
                || adminName == null
                || role == null) {

            throw new JwtAuthenticationException(
                    AuthErrorCode.TOKEN_CLAIMS_EMPTY
            );
        }


        requireAdminRole(
                claims
        );


        CustomAdminDetail adminDetail =
                new CustomAdminDetail(
                        adminId,
                        adminName,
                        role
                );


        return new UsernamePasswordAuthenticationToken(
                adminDetail,
                "",
                adminDetail.getAuthorities()
        );
    }


    private Claims parseClaims(
            String token,
            boolean accessToken
    ) {

        try {

            return jwtParser
                    .parseClaimsJws(
                            token
                    )
                    .getBody();

        } catch (ExpiredJwtException e) {

            throw new JwtAuthenticationException(

                    accessToken
                            ? AuthErrorCode.EXPIRED_ACCESS_TOKEN
                            : AuthErrorCode.EXPIRED_REFRESH_TOKEN,

                    e
            );

        } catch (io.jsonwebtoken.security.SecurityException e) {

            throw new JwtAuthenticationException(
                    AuthErrorCode.INVALID_SIGNATURE,
                    e
            );

        } catch (MalformedJwtException e) {

            throw new JwtAuthenticationException(
                    AuthErrorCode.MALFORMED_TOKEN,
                    e
            );

        } catch (UnsupportedJwtException e) {

            throw new JwtAuthenticationException(
                    AuthErrorCode.UNSUPPORTED_TOKEN,
                    e
            );

        } catch (IllegalArgumentException e) {

            throw new JwtAuthenticationException(
                    AuthErrorCode.TOKEN_CLAIMS_EMPTY,
                    e
            );

        } catch (MissingClaimException | IncorrectClaimException e) {

        throw new JwtAuthenticationException(
                AuthErrorCode.INVALID_ISSUER,
                e
        );

    } catch (JwtException e) {

        throw new JwtAuthenticationException(
                AuthErrorCode.TOKEN_VALIDATE_FAILED,
                e
        );
    }
    }


    private void validateTokenType(
            Claims claims,
            String expectedType
    ) {

        String tokenType =
                claims.get(
                        JwtTokenProvider.TOKEN_TYPE_CLAIM,
                        String.class
                );


        if (!expectedType.equals(
                tokenType
        )) {

            throw new JwtAuthenticationException(
                    AuthErrorCode.INVALID_TOKEN_TYPE
            );
        }
    }
}