package kr.co.teambrain.marvelrun.admin.event.query.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.teambrain.marvelrun.admin.discount.query.ReferenceFilterSummaryResponse;

import lombok.Builder;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 서버 신청 목록 조회 응답 DTO
 * 개인의 이름과 단체명을 각각 분리하여 전달합니다.
 * 그룹은 표시 코드와 미선택 여부를 제공하며, 수정용 그룹 ID는 상세 응답에서 제공합니다.
 */
@Builder
public record RegistrationListResponse(
        ReferenceFilterSummaryResponse referenceFilter,
        String registrationId,    // 상세 조회 등을 위한 신청 PK
        Long listNumber,          // 번호: 페이징 정보를 기반으로 계산된 내림차순 번호
        String type,              // 유형: "개인" 또는 "단체"
        String name,              // 이름: 신청건의 실제 이름 (개인/단체 불문)
        String orgName,           // 단체명: 단체 신청인 경우 단체명 (대표자명 제외), 개인인 경우 null
        String birth,             // 생년월일
        String gender,            // 성별: "남성" 또는 "여성"
        String courseName,        // 코스: EventCategory의 name 필드
        String souvenirName,      // 기념품: 임시로 첫 번째 기념품 ID 노출
        String phoneNumber,       // 연락처: AES 복호화가 완료된 평문 전화번호
        String marketingConsent,  // 마케팅동의: 임시로 activeUniqueInfo 필드를 활용해 "Y" 또는 "N" 표기
        String status,            // 상태: 결제완료, 대기, 환불완료 등의 진행 상태
        LocalDateTime createdAt,  // 신청일시: registrationDate 필드
        String startGroupCode,
        boolean startGroupUnselected,
        @Schema(description = "배번. 버추얼 대회는 그 행(참가 항목)의 배번 하나, 그 외는 발급 배번(하위 종목 정렬 순). 없으면 빈 목록")
        List<String> bibNumbers,
        // 아래는 버추얼 대회 참가자 기준 목록에만 채운다. 오프라인 대회는 null이다.
        @Schema(description = "참가 항목 ID. 버추얼 대회에서 같은 주문의 행을 구분한다. 참가 항목이 없거나 오프라인 대회면 null")
        String entryId,
        @Schema(description = "구매 줄 ID. 참가 항목이 없거나 오프라인 대회면 null")
        String itemId,
        @Schema(description = "상품명: 구매 줄의 상품. 구매 줄이 없으면 신청 종목명. 오프라인 대회는 null")
        String productName,
        @Schema(description = "주문자 이름. 오프라인 대회는 null")
        String ordererName,
        @Schema(description = "주문자 연락처. 오프라인 대회는 null")
        String ordererPhoneNumber
) {

}
