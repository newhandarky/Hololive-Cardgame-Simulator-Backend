package com.hololive.cardgame.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateDeckRequest {

    @NotBlank
    @Size(max = 100)
    private String name;
}
