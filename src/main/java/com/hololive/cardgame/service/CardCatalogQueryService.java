package com.hololive.cardgame.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hololive.cardgame.dto.CardDetailResponse;
import com.hololive.cardgame.dto.CardSearchResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CardCatalogQueryService {

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ObjectMapper objectMapper;

    public CardCatalogQueryService(
        NamedParameterJdbcTemplate namedParameterJdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<CardSearchResponse> searchCards(
        String keyword,
        String type,
        String rarity,
        String color,
        String levelType,
        String expansionCode,
        List<String> tags,
        Boolean hasImage,
        String sort
    ) {
        StringBuilder sql = new StringBuilder(
            """
            SELECT
                c.card_id,
                c.name,
                c.card_type,
                c.rarity,
                c.image_url,
                c.card_no,
                c.expansion_code,
                c.tags_json::text AS tags_json_text,
                COALESCE(oc.main_color, mc.main_color, cc.color) AS main_color,
                mc.level_type,
                oc.life,
                mc.hp
            FROM cards c
            LEFT JOIN oshi_cards oc ON oc.card_id = c.card_id
            LEFT JOIN member_cards mc ON mc.card_id = c.card_id
            LEFT JOIN cheer_cards cc ON cc.card_id = c.card_id
            WHERE 1 = 1
            """
        );

        MapSqlParameterSource params = new MapSqlParameterSource();

        if (StringUtils.hasText(keyword)) {
            sql.append(" AND (c.name ILIKE :keyword OR c.card_id ILIKE :keyword)");
            params.addValue("keyword", "%" + keyword.trim() + "%");
        }
        if (StringUtils.hasText(type)) {
            sql.append(" AND c.card_type = :cardType");
            params.addValue("cardType", type.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(rarity)) {
            sql.append(" AND c.rarity = :rarity");
            params.addValue("rarity", rarity.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(color)) {
            sql.append(" AND COALESCE(oc.main_color, mc.main_color, cc.color) = :mainColor");
            params.addValue("mainColor", color.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(levelType)) {
            sql.append(" AND mc.level_type = :levelType");
            params.addValue("levelType", levelType.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(expansionCode)) {
            sql.append(" AND c.expansion_code = :expansionCode");
            params.addValue("expansionCode", expansionCode.trim().toUpperCase(Locale.ROOT));
        }

        List<String> normalizedTags = normalizeTags(tags);
        if (!normalizedTags.isEmpty()) {
            // 多個 tag 先做「任一命中」，後續若要「全部命中」可再加參數控制。
            List<String> tagConditions = new ArrayList<>();
            for (int i = 0; i < normalizedTags.size(); i++) {
                String paramName = "tagPattern" + i;
                tagConditions.add("c.tags_json::text ILIKE :" + paramName);
                params.addValue(paramName, "%\"" + normalizedTags.get(i) + "\"%");
            }
            sql.append(" AND (").append(String.join(" OR ", tagConditions)).append(")");
        }

        if (hasImage != null) {
            if (hasImage) {
                sql.append(" AND c.image_url IS NOT NULL AND c.image_url <> ''");
            } else {
                sql.append(" AND (c.image_url IS NULL OR c.image_url = '')");
            }
        }

        sql.append(resolveOrderBy(sort));

        return namedParameterJdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> toCardSearchResponse(rs));
    }

    public CardDetailResponse getCardDetail(String cardId) {
        String normalizedCardId = cardId.trim().toUpperCase(Locale.ROOT);
        String sql =
            """
            SELECT
                c.card_id,
                c.name,
                c.card_type,
                c.rarity,
                c.image_url,
                c.card_no,
                c.expansion_code,
                c.source_url,
                c.tags_json::text AS tags_json_text,
                oc.life,
                oc.main_color AS oshi_main_color,
                oc.sub_color AS oshi_sub_color,
                mc.hp,
                mc.level_type,
                mc.bloom_level,
                mc.main_color AS member_main_color,
                mc.sub_color AS member_sub_color,
                mc.passive_effect_json::text AS passive_effect_json_text,
                mc.trigger_condition,
                sc.is_limited,
                sc.condition_type,
                sc.condition_json::text AS support_condition_json_text,
                sc.effect_type,
                sc.effect_json::text AS support_effect_json_text,
                sc.target_type,
                cc.color AS cheer_color
            FROM cards c
            LEFT JOIN oshi_cards oc ON oc.card_id = c.card_id
            LEFT JOIN member_cards mc ON mc.card_id = c.card_id
            LEFT JOIN support_cards sc ON sc.card_id = c.card_id
            LEFT JOIN cheer_cards cc ON cc.card_id = c.card_id
            WHERE c.card_id = :cardId
            """;

        List<CardDetailResponse> details = namedParameterJdbcTemplate.query(
            sql,
            new MapSqlParameterSource("cardId", normalizedCardId),
            (rs, rowNum) -> toCardDetailResponse(rs)
        );

        if (details.isEmpty()) {
            throw new IllegalArgumentException("找不到卡片：" + normalizedCardId);
        }

        CardDetailResponse detail = details.get(0);
        detail.setOshiSkills(loadOshiSkills(normalizedCardId));
        detail.setMemberArts(loadMemberArts(normalizedCardId));
        return detail;
    }

    public List<String> getAvailableTags() {
        String sql =
            """
            SELECT DISTINCT tag
            FROM cards c
            CROSS JOIN LATERAL jsonb_array_elements_text(c.tags_json) AS t(tag)
            ORDER BY tag
            """;
        return namedParameterJdbcTemplate.getJdbcTemplate().queryForList(sql, String.class);
    }

    private CardSearchResponse toCardSearchResponse(ResultSet rs) throws SQLException {
        return new CardSearchResponse(
            rs.getString("card_id"),
            rs.getString("name"),
            rs.getString("card_type"),
            rs.getString("rarity"),
            rs.getString("image_url"),
            rs.getString("card_no"),
            rs.getString("expansion_code"),
            rs.getString("main_color"),
            rs.getString("level_type"),
            (Integer) rs.getObject("life"),
            (Integer) rs.getObject("hp"),
            parseJsonTextToStringList(rs.getString("tags_json_text"))
        );
    }

    private CardDetailResponse toCardDetailResponse(ResultSet rs) throws SQLException {
        String cardType = rs.getString("card_type");
        String mainColor = "OSHI".equals(cardType) ? rs.getString("oshi_main_color") : rs.getString("member_main_color");
        String subColor = "OSHI".equals(cardType) ? rs.getString("oshi_sub_color") : rs.getString("member_sub_color");

        return new CardDetailResponse(
            rs.getString("card_id"),
            rs.getString("name"),
            cardType,
            rs.getString("rarity"),
            rs.getString("image_url"),
            rs.getString("card_no"),
            rs.getString("expansion_code"),
            rs.getString("source_url"),
            parseJsonTextToStringList(rs.getString("tags_json_text")),
            mainColor,
            subColor,
            (Integer) rs.getObject("life"),
            (Integer) rs.getObject("hp"),
            rs.getString("level_type"),
            (Integer) rs.getObject("bloom_level"),
            rs.getString("passive_effect_json_text"),
            rs.getString("trigger_condition"),
            (Boolean) rs.getObject("is_limited"),
            rs.getString("condition_type"),
            rs.getString("support_condition_json_text"),
            rs.getString("effect_type"),
            rs.getString("support_effect_json_text"),
            rs.getString("target_type"),
            rs.getString("cheer_color"),
            Collections.emptyList(),
            Collections.emptyList()
        );
    }

    private List<CardDetailResponse.OshiSkillItem> loadOshiSkills(String cardId) {
        String sql =
            """
            SELECT skill_type, skill_name, description, holopower_cost, effect_json::text AS effect_json_text
            FROM oshi_skills
            WHERE oshi_card_id = :cardId
            ORDER BY id
            """;
        return namedParameterJdbcTemplate.query(
            sql,
            new MapSqlParameterSource("cardId", cardId),
            (rs, rowNum) -> new CardDetailResponse.OshiSkillItem(
                rs.getString("skill_type"),
                rs.getString("skill_name"),
                rs.getString("description"),
                (Integer) rs.getObject("holopower_cost"),
                rs.getString("effect_json_text")
            )
        );
    }

    private List<CardDetailResponse.MemberArtItem> loadMemberArts(String cardId) {
        String sql =
            """
            SELECT order_index, name, description, cost_cheer_json::text AS cost_cheer_json_text, effect_json::text AS effect_json_text
            FROM member_arts
            WHERE member_card_id = :cardId
            ORDER BY order_index, id
            """;
        return namedParameterJdbcTemplate.query(
            sql,
            new MapSqlParameterSource("cardId", cardId),
            (rs, rowNum) -> new CardDetailResponse.MemberArtItem(
                (Integer) rs.getObject("order_index"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("cost_cheer_json_text"),
                rs.getString("effect_json_text")
            )
        );
    }

    private List<String> parseJsonTextToStringList(String jsonText) {
        if (!StringUtils.hasText(jsonText)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(
                jsonText,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
            );
        } catch (JsonProcessingException ex) {
            return Collections.emptyList();
        }
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }
        return tags.stream()
            .filter(StringUtils::hasText)
            .flatMap(tag -> List.of(tag.split(",")).stream())
            .map(String::trim)
            .filter(StringUtils::hasText)
            .distinct()
            .collect(Collectors.toList());
    }

    private String resolveOrderBy(String sort) {
        if (!StringUtils.hasText(sort)) {
            return " ORDER BY c.card_no NULLS LAST, c.card_id";
        }
        return switch (sort.trim()) {
            case "newest" -> " ORDER BY c.created_at DESC, c.card_id";
            case "rarity" -> " ORDER BY c.rarity NULLS LAST, c.card_no NULLS LAST, c.card_id";
            case "name" -> " ORDER BY c.name, c.card_id";
            case "cardId" -> " ORDER BY c.card_id";
            default -> " ORDER BY c.card_no NULLS LAST, c.card_id";
        };
    }
}
