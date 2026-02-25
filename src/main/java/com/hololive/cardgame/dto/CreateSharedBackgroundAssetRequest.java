package com.hololive.cardgame.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateSharedBackgroundAssetRequest {
    @NotBlank(message = "category 不可為空")
    private String category;

    @NotBlank(message = "imageUrl 不可為空")
    private String imageUrl;
}
