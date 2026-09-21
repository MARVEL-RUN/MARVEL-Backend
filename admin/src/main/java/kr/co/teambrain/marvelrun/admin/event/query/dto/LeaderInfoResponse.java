package kr.co.teambrain.marvelrun.admin.event.query.dto;

public record LeaderInfoResponse(

        String name,
        String phNum,
        String birth,
        String address,
        String addressDetail

) {
}
