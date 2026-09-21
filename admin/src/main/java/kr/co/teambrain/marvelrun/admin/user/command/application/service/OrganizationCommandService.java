package kr.co.teambrain.marvelrun.admin.user.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.dto.request.PasswordResetRequest;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.admin.user.command.repository.OrganizationCommandRepository;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrgLoginIdExistResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OrganizationCommandService {

    private final OrganizationCommandRepository organizationCommandRepository;
    private final RegistrationCommandRepository registrationCommandRepository;

    /**
     * 단체 신청 비밀번호 초기화
     */
    public void resetOrganizationPassword(String organizationId, PasswordResetRequest request) {

        Organization organization = organizationCommandRepository.findById(organizationId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORGANIZATION_NOT_FOUND)); // 해당 에러코드 추가 필요

        if (request.newPassword().length() < 6) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD_LENGTH);
        }


        // 1. 단체 자체의 비밀번호 변경
        organization.resetPasswordByAdmin(request.newPassword());

        // 2. 단체에 속한 모든 참가자(Registration) 조회 및 비밀번호 일괄
        // 주석 처리 - 김경환 : 단체 신청에서의 개별 registration은 실제로 접근할 수 없어야함.
        // 접근 시 사용자 개개인이 자기자신의 payment 결제/환불이나 종목 수정 등을 시도할 수 있게되며,
        // 현재 결제 플로우는 단체장 개인이 결제하는것을 전제로 구현되어있으므로 주석처리합니다.
//        List<Registration> registrations = registrationCommandRepository.findAllByOrganization_Id(organizationId);
//        for (Registration registration : registrations) {
//            registration.resetPasswordByAdmin(request.newPassword());
//        }
    }

    public void resetOrganizationLoginId(String newLoginId, String organizationId) {
        Organization organization = organizationCommandRepository.findById(organizationId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORGANIZATION_NOT_FOUND)); // 해당 에러코드 추가 필요

        organization.resetLoginIdByAdmin(newLoginId);
    }

    @Transactional(readOnly = true)
    public OrgLoginIdExistResponse checkExistsGroupInfo(String loginId, String eventId) {
        return OrgLoginIdExistResponse.fromRawValue(
                loginId,
                organizationCommandRepository.existsByLoginIdAndEventId(loginId, eventId)
        );
    }

}
