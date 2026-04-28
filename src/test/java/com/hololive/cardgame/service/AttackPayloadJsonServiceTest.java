package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttackPayloadJsonServiceTest {

    @Test
    void toJsonShouldSerializePayloadWithExistingObjectMapperShape() {
        AttackPayloadJsonService service = new AttackPayloadJsonService(new ObjectMapper());

        String json = service.toJson(Map.of("artTotalDamage", 50, "hasNextPerformanceAction", false));

        assertThat(json).contains("\"artTotalDamage\":50");
        assertThat(json).contains("\"hasNextPerformanceAction\":false");
    }

    @Test
    void toJsonShouldWrapSerializationFailure() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.getSerializerProvider().setNullKeySerializer(new FailingNullKeySerializer());
        AttackPayloadJsonService service = new AttackPayloadJsonService(objectMapper);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(null, "invalid");

        assertThatThrownBy(() -> service.toJson(payload))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("攻擊 payload 序列化失敗");
    }

    private static class FailingNullKeySerializer extends JsonSerializer<Object> {

        @Override
        public void serialize(Object value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            throw new IOException("forced failure");
        }
    }
}
