package kr.co.teambrain.marvelrun.admin.event.query.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationEntryListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationEntryQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 관리자가 받는 사람 수정에 쓸 신청의 참가 항목(entryId 포함)을 읽는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegistrationEntryQueryService {

    private final RegistrationQueryRepository registrationQueryRepository;
    private final RegistrationEntryQueryRepository registrationEntryQueryRepository;

    /** 삭제된 신청도 읽기 전용으로 돌려준다(신청 상세 조회와 같다). 오프라인 대회는 참가 항목이 없다. */
    public RegistrationEntryListResponse getEntries(String registrationId) {
        Registration registration = registrationQueryRepository.findById(registrationId)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_NOT_FOUND));
        ProductType productType = registration.getEventCategory().getProductType();
        boolean editable = productType == ProductType.PACKAGE;
        if (registrationQueryRepository.findEventFormatById(registration.getEvent().getId()) == EventFormat.OFFLINE) {
            return new RegistrationEntryListResponse(registrationId, productType, false, List.of());
        }
        List<RegistrationEntryListResponse.Entry> entries = registrationEntryQueryRepository
                .findByRegistrationIds(List.of(registrationId)).stream()
                .map(RegistrationEntryListResponse.Entry::from)
                .toList();
        return new RegistrationEntryListResponse(registrationId, productType, editable, entries);
    }
}
