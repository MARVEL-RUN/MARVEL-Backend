package kr.co.teambrain.marvelrun.user.community.query.dto.response;

import kr.co.teambrain.marvelrun.user.community.query.dto.NoticeListResponse;
import kr.co.teambrain.marvelrun.user.community.query.dto.PinnedNoticeResponse;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class NoticeHeaderPageWrapperResponse {

    public List<PinnedNoticeResponse> pinnedNoticeList;

    public Page<NoticeListResponse> noticePage;
}
