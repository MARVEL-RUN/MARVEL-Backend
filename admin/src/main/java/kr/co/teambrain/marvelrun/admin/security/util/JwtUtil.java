package kr.co.teambrain.marvelrun.admin.security.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.AuthErrorCode;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.JwtAuthenticationException;
import kr.co.teambrain.marvelrun.admin.common.redis.RedisService;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.security.Key;

@Component
public class JwtUtil {

    private final JwtParser jwtParser;

    private final RedisService redisService;


    public JwtUtil(

            @Value("${token.secret}")
            String secretKey,

            RedisService redisService
    ) {

        byte[] keyBytes =
                Decoders.BASE64.decode(
                        secretKey
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