package kr.co.teambrain.marvelrun.admin.event.query.dto.response;

import lombok.Builder;
import java.util.Map;

@Builder
public record EventStatisticsResponse(
        long totalRegistrations,
        long totalCompletedPayments,
        long personalRegistrations,
        long personalCompletedPayments,
        Map<String, Long> genderStats,
        Map<String, Long> ageGroupStats
) {
}