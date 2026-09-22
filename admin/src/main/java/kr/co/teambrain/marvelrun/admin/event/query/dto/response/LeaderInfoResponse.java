package kr.co.teambrain.marvelrun.admin.event.query.dto.response;

public record LeaderInfoResponse(
        String groupName,

        String name,
        String phNum,
        String birth,
        String address,
        String addressDetail

) {
}
