package kr.co.teambrain.marvelrun.admin.payment.command;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
/** 사용자와 동일한 Asia/Seoul 기준으로 준비 시각을 한 번 구한다. */
@Component
public class AdminRefundTime {
    private final Clock clock = Clock.system(ZoneId.of("Asia/Seoul"));
    public LocalDateTime now() { return LocalDateTime.now(clock); }
}
