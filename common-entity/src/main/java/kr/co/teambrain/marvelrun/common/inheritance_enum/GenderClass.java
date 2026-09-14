package kr.co.teambrain.marvelrun.common.inheritance_enum;

public enum GenderClass {
    M, F;
    
    public static String toGenderStr(GenderClass genderClass) {

        if (genderClass == null) {
            throw new IllegalArgumentException("GenderClass.toGenderStr : 전달된 매개변수가 null입니다.");
        }

        return switch (genderClass) {
            case M -> "남성";
            case F -> "여성";
        };
    }
}
