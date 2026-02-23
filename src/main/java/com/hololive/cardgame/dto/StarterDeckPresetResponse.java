package com.hololive.cardgame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StarterDeckPresetResponse {

    private String code;
    private String name;
    private String description;
}
