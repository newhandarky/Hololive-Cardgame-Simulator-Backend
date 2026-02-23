package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CardEffectValidatorTest {

    private CardEffectValidator cardEffectValidator;

    @BeforeEach
    void setUp() {
        cardEffectValidator = new CardEffectValidator(new ObjectMapper());
    }

    @Test
    void validateEffectJsonShouldAcceptUnimplementedWithRawText() {
        assertThatCode(() -> cardEffectValidator.validateEffectJson(
            "{\"type\":\"UNIMPLEMENTED\",\"rawText\":\"暫未實作\"}",
            "effectJson"
        )).doesNotThrowAnyException();
    }

    @Test
    void validateEffectJsonShouldAcceptUpperAndLowerCaseTypes() {
        assertThatCode(() -> cardEffectValidator.validateEffectJson(
            "{\"type\":\"DRAW\",\"value\":1}",
            "effectJson"
        )).doesNotThrowAnyException();
        assertThatCode(() -> cardEffectValidator.validateEffectJson(
            "{\"type\":\"draw\",\"value\":1}",
            "effectJson"
        )).doesNotThrowAnyException();
    }

    @Test
    void validateEffectJsonShouldRejectMissingType() {
        assertThatThrownBy(() -> cardEffectValidator.validateEffectJson(
            "{\"rawText\":\"缺少 type\"}",
            "effectJson"
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("缺少 type");
    }

    @Test
    void validateEffectJsonShouldRejectUnknownType() {
        assertThatThrownBy(() -> cardEffectValidator.validateEffectJson(
            "{\"type\":\"SOME_NEW_TYPE\"}",
            "effectJson"
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("type 不支援");
    }

    @Test
    void validateEffectJsonShouldRejectUnimplementedWithoutRawField() {
        assertThatThrownBy(() -> cardEffectValidator.validateEffectJson(
            "{\"type\":\"UNIMPLEMENTED\"}",
            "effectJson"
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("至少要提供");
    }

    @Test
    void validateJsonObjectShouldRejectInvalidJson() {
        assertThatThrownBy(() -> cardEffectValidator.validateJsonObject(
            "{bad json",
            "passiveEffectJson"
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不是合法 JSON");
    }
}
