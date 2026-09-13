package kr.co.teambrain.marvelrun.user.common.exception;


import kr.co.teambrain.marvelrun.user.common.exception.in_service.AuthErrorCode;
import lombok.Getter;
import org.springframework.security.core.AuthenticationException;

@Getter
public class JwtAuthenticationException extends AuthenticationException {

    private final AuthErrorCode authErrorCode;

    public JwtAuthenticationException(AuthErrorCode authErrorCode) {
        super(authErrorCode.getMessage());
        this.authErrorCode = authErrorCode;
    }

    public JwtAuthenticationException(AuthErrorCode authErrorCode, Throwable cause) {
        super(authErrorCode.getMessage(), cause);
        this.authErrorCode = authErrorCode;
    }
}