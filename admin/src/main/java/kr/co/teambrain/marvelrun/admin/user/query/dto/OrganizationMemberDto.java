package kr.co.teambrain.marvelrun.admin.user.query.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class OrganizationMemberDto {
    private String registrationId;
    private String name;
    private String courseName;
    private String souvenirName;
    private String souvenirSize;
    private String birth;
    private BigDecimal amount;
}