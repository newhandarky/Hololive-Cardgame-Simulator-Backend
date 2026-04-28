package com.hololive.cardgame.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

class MatchPayloadJsonService {

    private final ObjectMapper objectMapper;

    MatchPayloadJsonService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
