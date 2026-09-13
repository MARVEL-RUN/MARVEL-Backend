package kr.co.teambrain.marvelrun.common.inheritance_enum;

import java.util.List;

public enum DeviceType {
    PC, MOBILE, BOTH;

    public static DeviceType normalize(String raw) {
        if (raw == null) return PC;                // 기본값
        try {
            return DeviceType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return PC; // 유효하지 않으면 PC로 강제
        }
    }

    /** 요청 디바이스에 해당해 노출 가능한 DB device 값 목록 */
    public List<String> acceptedDbValues() {
        return switch (this) {
            case PC     -> List.of("PC", "BOTH");
            case MOBILE -> List.of("MOBILE", "BOTH");
            case BOTH   -> List.of("PC", "MOBILE", "BOTH");
        };
    }
}
