package com.hololive.cardgame.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MatchPayloadJsonServiceTest {

    @Test
    void toJsonShouldSerializePayloadWithExistingObjectMapperShape() {
        MatchPayloadJsonService service = new MatchPayloadJsonService(new ObjectMapper());

        String json = service.toJson(Map.of("actionType", "DRAW", "count", 1));

        assertThat(json).contains("\"actionType\":\"DRAW\"");
        assertThat(json).contains("\"count\":1");
    }

    @Test
    void toJsonShouldReturnEmptyObjectWhenSerializationFails() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.getSerializerProvider().setNullKeySerializer(new FailingNullKeySerializer());
        MatchPayloadJsonService service = new MatchPayloadJsonService(objectMapper);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(null, "invalid");

        assertThat(service.toJson(payload)).isEqualTo("{}");
    }

    private static class FailingNullKeySerializer extends JsonSerializer<Object> {

        @Override
        public void serialize(Object value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            throw new IOException("forced failure");
        }
    }
}
