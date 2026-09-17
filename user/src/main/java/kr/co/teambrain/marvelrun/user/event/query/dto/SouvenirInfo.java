package kr.co.teambrain.marvelrun.user.event.query.dto;

import java.util.List;

public record SouvenirInfo (

    String souvenirId,

    String souvenirName,

    List<String> selectableSizeList,

    boolean isActive

){}
