package kr.co.teambrain.marvelrun.admin.event.query.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
        String guardianPhoneNumber,   // 보호자 연락처
        String guardianRelationship,  // 보호자 관계
        LocalDateTime createdAt,      // 신청일시
        BigDecimal amount,            // 금액 (계약 금액)
        String orderId,               // 주문번호 (Payment 참조)
        String paymentMethod,         // 카드결제정보 대신 결제수단 (Payment 참조)[cite: 10]
        String paymentStatus,         // 결제여부
        String address,               // 주소
        String addressDetail          // 상세주소
) {
}