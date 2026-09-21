package kr.co.teambrain.marvelrun.user.event.command.application.dto.response;

public record OrgInfoExistResponse (
    String requestValue,
    boolean requestUseable
){
    public static OrgInfoExistResponse fromRawValue(
            String requestValue,
            boolean requestRawUseable

    ) {
        // exist(존재)하면 사용을 '못'하니까 반대로 처리
        return new OrgInfoExistResponse(
                requestValue,
                !requestRawUseable
        );
    }
}
