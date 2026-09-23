package kr.co.teambrain.marvelrun.admin.event.query.dto.response;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

/**
 * 관리자 서버 신청 상세 조회 응답 DTO
 */
@Builder
public record RegistrationDetailResponse(
        String name,                  // 성명
        String orgName,               // 단체명 (개인은 null 또는 "-")
        String courseName,            // 코스
        String souvenirName,          // 기념품 이름
        String souvenirSize,          // 사이즈
        String gender,                // 성별 (남성/여성)
        String birth,                 // 생년월일
        String phoneNumber,           // 연락처
        String email,                 // 이메일 (유저 또는 단체 테이블 참조)
        boolean guardianConsent,
        String guardianName,
        String guardianPhoneNumber,   // 보호자 연락처
        String guardianRelationship,  // 보호자 관계
        LocalDateTime createdAt,      // 신청일시
        BigDecimal amount,            // 금액 (계약 금액)
        String orderId,               // 주문번호 (Payment 참조)
        String paymentMethod,         // 카드결제정보 대신 결제수단 (Payment 참조)[cite: 10]
        String paymentStatus,         // 결제여부
        String address,               // 주소
        String addressDetail,          // 상세주소
        String organizationId,
        LeaderInfoResponse leaderInfo,

        boolean termsEssentialAgreed, // 필수동의여부
        boolean termsMarketingAgreed, // 마케팅동의여부
        boolean termsMarketingChannelAgreed, // 전송매체기반마케팅동의여부

        /** 현재 종목 식별자. 관리자 정보·금액 조정 폼의 초기값이다. */
        String eventCategoryId,

        /** 저장된 전체 기념품 선택 ID·사이즈. 표시명에서 ID를 추정하지 않는다. */
        List<SouvenirJson> selectedSouvenirList
        
) {
}