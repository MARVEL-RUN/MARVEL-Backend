package kr.co.teambrain.marvelrun.common.inheritance_enum;


/** 현재 registration의 주소 베이스가 어디인지를 표기하는 목적의 enum */
public enum AddressBase {
    REGISTRATION, // registration이 주소를 자체 소유
    ORGANIZATION // organization의 주소를 가져와 사용
}
