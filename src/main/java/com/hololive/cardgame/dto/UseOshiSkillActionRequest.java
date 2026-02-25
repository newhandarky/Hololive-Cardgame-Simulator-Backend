package com.hololive.cardgame.dto;

import java.util.List;
import lombok.Data;

@Data
public class UseOshiSkillActionRequest {
    private String skillType;
    private Long targetHolomemCardInstanceId;
    private List<Long> selectedCardInstanceIds;
}
