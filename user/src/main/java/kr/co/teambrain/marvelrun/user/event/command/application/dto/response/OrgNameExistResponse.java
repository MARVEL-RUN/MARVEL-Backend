package kr.co.teambrain.marvelrun.user.event.command.application.dto.response;



public record OrgNameExistResponse (

    String requestedGroupName,
    boolean useable
){
    public static OrgNameExistResponse fromRawValue(
            String requestedGroupName,
            boolean rawExistvalue
    ) {

        return new OrgNameExistResponse(
                    requestedGroupName,
                    !rawExistvalue // exist(존재)하면 사용을 '못'하니까 반대로 처리
                );
    }
}
