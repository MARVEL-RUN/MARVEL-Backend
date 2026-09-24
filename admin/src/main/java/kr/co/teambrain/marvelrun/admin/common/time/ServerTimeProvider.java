package kr.co.teambrain.marvelrun.admin.common.time;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
public class ServerTimeProvider {

    private final Clock clock;

    public ServerTimeProvider(Clock clock) {
        this.clock = clock;
    }

    /**
     * 서버의 업무 처리 및 정책 검증에 사용할 기준시각을 반환한다.
     *
     * 시간대는 ServerTimeConfig에서 설정한 Asia/Seoul을 사용하며,
     * 업무 코드에 공급하는 시간 자료형은 LocalDateTime으로 통일한다.
     *
     * Service는 작업 시작 시 이 메서드를 한 번 호출하고,
     * 반환받은 동일한 기준시각을 Validator와 후속 처리에 전달한다.
     *
     * 날짜만 필요한 검증은 전달받은 기준시각에서 toLocalDate()로
     * 날짜를 추출하여 사용하고, 현재 시각을 다시 조회하지 않는다.
     */
    public LocalDateTime currentDateTime() {
        return LocalDateTime.now(clock);
    }

    public String timeZone() {return clock.getZone().toString(); }
}