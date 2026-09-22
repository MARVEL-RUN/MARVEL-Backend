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
            String classification,
            Map<String, Long> courseCounts,
            long totalCount,
            long cardCount, // 카드
            long easyPayCount,              // 간편결제 카운트 추가
            long unpaidCount, // 미결제
            long personalCount,
            long groupCount
    ) {}
}