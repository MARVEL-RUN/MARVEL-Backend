package kr.co.teambrain.marvelrun.admin.user.query.dto;


public record OrgLoginIdExistResponse (
        String requestedLoginId,
        boolean useableLoginId
){
    public static OrgLoginIdExistResponse fromRawValue(
            String requestedOrgLoginId,
            boolean rawExistvalueLoginId

    ) {
        // exist(존재)하면 사용을 '못'하니까 반대로 처리
        return new OrgLoginIdExistResponse(
                requestedOrgLoginId,
                !rawExistvalueLoginId
        );
    }
}
