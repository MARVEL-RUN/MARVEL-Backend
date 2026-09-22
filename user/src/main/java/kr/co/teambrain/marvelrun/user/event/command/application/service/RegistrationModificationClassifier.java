package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 접근 검증을 마친 기존 신청과 요청의 업무값만 비교한다.
 * 저장된 종목 식별자와 기념품 JSON을 사용하며 정책·가격·예약을 조회하지 않는다.
 * 인증·입력 제약·uniqueInfo 중복·수정 가능 여부·동시성 보호는 호출부 책임이다.
 * 개인정보 판정 후에도 각 수정 서비스의 접근·중복 검증과 동시성 보호를 통과해야 저장한다.
 */
@Component
public class RegistrationModificationClassifier {

    /** 기존 신청에 적용할 변경의 범위를 표현한다. */
    public enum Change {
        NONE, PERSONAL_INFORMATION, FULL
    }

    /**
     * 종목·생년월일·기념품 변경만 전체 수정으로 분류한다.
     * 기본정보와 보호자 정보 변경은 개인정보 수정으로 처리한다.
     */
    public Change classifyPersonal(
            Registration current,
            RegistrationModificationRequest request
    ) {
        if (current == null || request == null) {
            throw invalidArgument();
        }

        if (policyFieldsChanged(
                current,
                request.eventCategoryId(),
                request.birth(),
                request.selectedSouvenirList()
        )) {
            return Change.FULL;
        }

        boolean changed =
                !Objects.equals(current.getName(), request.name())
                        || !Objects.equals(current.getEmail(), request.email()) // 추가됨
                        || !Objects.equals(current.getPhNum(), request.phNum())
                        || current.getGender() != request.gender()
                        || !Objects.equals(current.getAddress(), request.address())
                        || !Objects.equals(
                        current.getAddressDetail(),
                        request.addressDetail()
                )
                        || !Objects.equals(
                        current.getGuardianName(),
                        normalizeGuardianName(request.guardianName())
                )
                        || !Objects.equals(
                        current.getGuardianPhNum(),
                        request.guardianPhNum()
                )
                        || !Objects.equals(
                        current.getGuardianRelationship(),
                        request.guardianRelationship()
                )
                        || current.isGuardianConsent() != request.guardianConsent();

        return changed ? Change.PERSONAL_INFORMATION : Change.NONE;
    }

    /**
     * 접근 검증된 활성 구성원 전체와 요청을 비교한다. 순서는 무시하되 중복 ID는 거부한다.
     * 추가·삭제 또는 한 명의 정책 영향 변경만 있어도 요청 전체를 FULL로 분류한다.
     * 단체 DTO에는 단체 자체의 변경 필드가 없으며 access는 인증에만 사용한다.
     */
    public Change classifyOrganization(List<Registration> currentMembers,
                                       OrgRegistrationModificationRequest request) {
        if (currentMembers == null || request == null || request.registrations() == null
                || request.registrations().isEmpty()) {
            throw invalidArgument();
        }
        Map<String, Registration> currentById = new HashMap<>();
        for (Registration current : currentMembers) {
            if (current == null || current.getId() == null || current.isSoftDeleted()
                    || currentById.putIfAbsent(current.getId(), current) != null) {
                throw invalidTarget();
            }
        }
        Set<String> requestedIds = new HashSet<>();
        boolean full = false;
        boolean changed = false;
        for (OrgRegistrationModificationParticipantRequest participant : request.registrations()) {
            if (participant == null) {
                throw invalidArgument();
            }
            // 신규 구성원이라도 기념품 중복을 목록 비교 과정에서 숨기지 않는다.
            souvenirSelections(participant.selectedSouvenirList());
            String id = participant.registrationId();
            if (id == null) {
                full = true;
                continue;
            }
            if (!requestedIds.add(id)) {
                throw new CustomException(ErrorCode.DUPLICATE_REGISTRATION_MODIFICATION_TARGET);
            }
            Registration current = currentById.get(id);
            if (id.isBlank() || current == null) {
                throw invalidTarget();
            }
            full |= policyFieldsChanged(current, participant.eventCategoryId(), participant.birth(),
                    participant.selectedSouvenirList());
            changed |= !Objects.equals(current.getName(), participant.name())
                    || !Objects.equals(current.getPhNum(), participant.phNum())
                    || current.getGender() != participant.gender();
        }
        if (full || !requestedIds.equals(currentById.keySet())) {
            return Change.FULL;
        }
        return changed ? Change.PERSONAL_INFORMATION : Change.NONE;
    }

    /**
     * 연령·가격·정원 입력을 비교한다. 현재 가격 또는 정책의 유효성은 다시 확인하지 않는다.
     * 호출부는 대상 신청과 종목 식별자를 확보한 동일 트랜잭션에서 판정해야 한다.
     */
    private boolean policyFieldsChanged(Registration current, String categoryId, String birth,
                                        List<SouvenirJson> requestedSouvenirs) {
        Map<String, String> requested = souvenirSelections(requestedSouvenirs);
        Map<String, String> stored = souvenirSelections(current.getSouvenirJson());
        return current.getEventCategory() == null
                || !Objects.equals(current.getEventCategory().getId(), categoryId)
                || !Objects.equals(current.getBirth(), birth)
                || !stored.equals(requested);
    }

    /**
     * 기념품 순서를 무시하고 ID·사이즈를 비교하되 중복 ID를 제거해서 허용하지 않는다.
     * 기존 trim만 적용한다. null/공백을 FREE로 추론하려고 현재 기념품 정책을 조회하지 않는다.
     * 저장값과 다른 미지정 사이즈는 전체 경로에서 기존 정규화·검증을 받는다.
     */
    private Map<String, String> souvenirSelections(List<SouvenirJson> souvenirs) {
        if (souvenirs == null || souvenirs.isEmpty()) {
            throw invalidArgument();
        }
        Map<String, String> selections = new HashMap<>();
        for (SouvenirJson souvenir : souvenirs) {
            if (souvenir == null || souvenir.souvenirId() == null || souvenir.souvenirId().isBlank()) {
                throw invalidArgument();
            }
            if (selections.containsKey(souvenir.souvenirId())) {
                throw new CustomException(ErrorCode.DUPLICATE_SOUVENIR_SELECTION);
            }
            selections.put(souvenir.souvenirId(),
                    souvenir.selectedSize() == null ? null : souvenir.selectedSize().trim());
        }
        return selections;
    }

    /** 개인 신청 적용 메서드의 기존 보호자 이름 strip·빈 값 null 변환을 따른다. */
    private String normalizeGuardianName(String name) {
        if (name == null || name.strip().isEmpty()) {
            return null;
        }
        return name.strip();
    }

    /** 기존 수정 입력 오류 계약으로 잘못된 비교 입력을 표현한다. */
    private CustomException invalidArgument() {
        return new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT);
    }

    /** 접근 검증된 현재 구성원 범위를 벗어난 비교 대상을 거부한다. */
    private CustomException invalidTarget() {
        return new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
    }
}
