package kr.co.teambrain.marvelrun.admin.user.query.dto;


public record OrgNameExistResponse (

        String requestedGroupName,
        boolean useableGroupName,
        String requestedLoginId,
        boolean useableLoginId
){
    public static OrgNameExistResponse fromRawValue(
            String requestedGroupName,
            boolean rawExistvalueGroupName,
            String requestedOrgLoginId,
            boolean rawExistvalueLoginId

    ) {
        // exist(존재)하면 사용을 '못'하니까 반대로 처리
        return new OrgNameExistResponse(
                requestedGroupName,
                !rawExistvalueGroupName,
                requestedOrgLoginId,
                !rawExistvalueLoginId
        );
    }
}
