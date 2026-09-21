package kr.co.teambrain.marvelrun.admin.event.query.dto;

import lombok.Builder;
import java.time.LocalDateTime;

/**
 * 관리자 서버 신청 목록 조회 응답 DTO
 * 개인의 이름과 단체명을 각각 분리하여 전달합니다.
 */
@Builder
public record RegistrationListResponse(
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
        LocalDateTime createdAt   // 신청일시: registrationDate 필드
) {

}