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
     * 단체 신청(대표 및 모든 소속 구성원) 비밀번호 초기화
     */
    public void resetOrganizationPassword(String organizationId, PasswordResetRequest request) {

        Organization organization = organizationCommandRepository.findById(organizationId)
                .orElseThrow(() -> new CustomException(ErrorCode.ORGANIZATION_NOT_FOUND)); // 해당 에러코드 추가 필요

        if (request.newPassword().length() < 6) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD_LENGTH);
        }


        // 1. 단체 자체의 비밀번호 변경
        organization.resetPasswordByAdmin(request.newPassword());

        // 2. 단체에 속한 모든 참가자(Registration) 조회 및 비밀번호 일괄 변경
        List<Registration> registrations = registrationCommandRepository.findAllByOrganization_Id(organizationId);
        for (Registration registration : registrations) {
            registration.resetPasswordByAdmin(request.newPassword());
        }
    }

    @Transactional(readOnly = true)
    public OrgLoginIdExistResponse checkExistsGroupInfo(String loginId, String eventId) {
        return OrgLoginIdExistResponse.fromRawValue(
                loginId,
                organizationCommandRepository.existsByLoginIdAndEventId(loginId, eventId)
        );
    }


}
