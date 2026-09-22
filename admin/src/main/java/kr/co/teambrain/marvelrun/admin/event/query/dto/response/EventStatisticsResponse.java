package kr.co.teambrain.marvelrun.admin.event.query.dto.response;

import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record EventStatisticsResponse(
        List<String> courseHeaders,       // 코스명 헤더 (동적 컬럼 렌더링용)
        List<StatRowDto> genderStats,     // 성별 통계 (남/여/합계)
        List<StatRowDto> ageGroupStats,   // 연령대 통계 (10대~60대/합계)
        List<StatRowDto> childStats       // 아동여부 통계 (일반/아동/합계)
) {
    @Builder
    public record StatRowDto(
            String classification,          // 구분 (예: "신청자(남)", "입금자(20대)")
            Map<String, Long> courseCounts, // 코스별 인원
            long totalCount,                // 인원 합계
            long cardCount,                 // 카드 결제
            long transferCount,             // 계좌 이체
            long freeCount,                 // 무료
            long personalCount,             // 개인
            long groupCount                 // 단체
    ) {}
}