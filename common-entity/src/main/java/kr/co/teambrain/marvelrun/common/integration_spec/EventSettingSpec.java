package kr.co.teambrain.marvelrun.common.integration_spec;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import kr.co.teambrain.marvelrun.common.entity.EventSettingBase;

/** 1:1 엔티티이며 노출되어도 문제없는 수준의 테이블 구조이므로 request, response로 구분하지 않고 spec dto를 요청, 응답 등에서 그대로 사용 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EventSettingSpec {

    // 대상 대회에서 단체 신청 사용 여부
    private boolean groupRegistrationEnabled;

    // 대상 대회에서 개인 신청란 내 '전마협 아이디' 표출 여부
    private boolean individualLoginIdEnabled;

    // 본 dto가 response로서 동작할 때 사용
    public static EventSettingSpec responseFrom(EventSettingBase<?> eventSetting) {
        return new EventSettingSpec(
          eventSetting.isGroupRegistrationEnabled(),
          eventSetting.isIndividualLoginIdEnabled()
        );
    }

}
