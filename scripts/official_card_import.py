#!/usr/bin/env python3
"""
官方卡表文字頁匯入工具（第一版）

用途：
1) 抓取官方 cardsearch 的 text view 頁面
2) 解析卡片基本資料、推し技能、ホロメンアーツ
3) 輸出可重複執行的 SQL（搭配 Flyway migration）
"""

from __future__ import annotations

import argparse
import html
import json
import re
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

BASE_URL = "https://hololive-official-cardgame.com"

CARD_TYPE_MAP = {
    "推しホロメン": "OSHI",
    "ホロメン": "MEMBER",
    "サポート": "SUPPORT",
    "エール": "CHEER",
}

COLOR_MAP = {
    "白": "WHITE",
    "緑": "GREEN",
    "赤": "RED",
    "青": "BLUE",
    "黄": "YELLOW",
    "紫": "PURPLE",
    "◇": "COLORLESS",
}

JP_COLOR_CHAR_MAP = {
    "白": "WHITE",
    "緑": "GREEN",
    "赤": "RED",
    "青": "BLUE",
    "黄": "YELLOW",
    "紫": "PURPLE",
    "◇": "COLORLESS",
}

LEVEL_MAP = {
    "Debut": "DEBUT",
    "1st": "FIRST",
    "2nd": "SECOND",
    "Spot": "SPOT",
    "Buzz": "BUZZ",
}

BLOOM_LEVEL_NUMERIC_MAP = {
    "Debut": 0,
    "1st": 1,
    "2nd": 2,
    "Spot": 0,
    "Buzz": 2,
}


@dataclass
class SkillData:
    skill_type: str
    skill_name: str
    holopower_cost: int
    description: str
    effect_json: dict[str, Any]


@dataclass
class ArtData:
    name: str
    description: str
    cost_cheer_json: dict[str, int]
    effect_json: dict[str, Any]
    order_index: int


@dataclass
class CardRecord:
    card_id: str
    name: str
    rarity: str
    image_url: str | None
    card_type: str
    expansion_code: str
    source_url: str
    tags: list[str] = field(default_factory=list)
    variant_image_urls: list[str] = field(default_factory=list)
    main_color: str | None = None
    life: int | None = None
    hp: int | None = None
    level_type: str | None = None
    bloom_level: int | None = None
    support_is_limited: bool = False
    support_condition_type: str | None = None
    support_condition_json: dict[str, Any] | None = None
    support_effect_type: str | None = None
    support_effect_json: dict[str, Any] | None = None
    support_target_type: str | None = None
    cheer_color: str | None = None
    oshi_skills: list[SkillData] = field(default_factory=list)
    member_arts: list[ArtData] = field(default_factory=list)
    member_extra: dict[str, str] = field(default_factory=dict)


def fetch_url(url: str) -> str:
    with urllib.request.urlopen(url) as response:
        return response.read().decode("utf-8")


def detect_max_page(html_text: str) -> int:
    match = re.search(r"max_page\s*=\s*(\d+)", html_text)
    if not match:
        return 1
    return max(1, int(match.group(1)))


def fetch_html(expansion: str, sort: str) -> str:
    first_url = f"{BASE_URL}/cardlist/cardsearch/?expansion={expansion}&view=text&sort={sort}"
    first_html = fetch_url(first_url)
    max_page = detect_max_page(first_html)
    if max_page <= 1:
        return first_html

    pages = [first_html]
    for page in range(2, max_page + 1):
        page_url = (
            f"{BASE_URL}/cardlist/cardsearch_ex?expansion={expansion}"
            f"&view=text&page={page}"
        )
        pages.append(fetch_url(page_url))

    return "\n".join(pages)


def clean_text(fragment: str) -> str:
    text = re.sub(r"<img[^>]*alt=\"([^\"]*)\"[^>]*>", r"\1", fragment)
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"<[^>]+>", "", text)
    text = html.unescape(text)
    text = text.replace("\u3000", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n\s*", "\n", text)
    return text.strip()


def extract_img_alts(fragment: str) -> list[str]:
    return re.findall(r"<img[^>]*alt=\"([^\"]*)\"[^>]*>", fragment)


def sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def sql_nullable(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return str(value)
    return sql_quote(str(value))


def sql_json(value: Any) -> str:
    return sql_quote(json.dumps(value, ensure_ascii=False)) + "::jsonb"


def normalize_art_name(span_text: str) -> str:
    # 去掉前方顏色符號，再保留原始數值（例如 60、90+）。
    return re.sub(r"^[赤青緑黄紫白◇\s]+", "", span_text).strip()


def parse_cost_from_span(span_html: str) -> dict[str, int]:
    cost: dict[str, int] = {}
    for alt in extract_img_alts(span_html):
        mapped = COLOR_MAP.get(alt)
        if mapped is None:
            continue
        cost[mapped] = cost.get(mapped, 0) + 1
    return cost


def parse_color_codes(raw_text: str) -> list[str]:
    if not raw_text:
        return []
    raw = raw_text.strip()
    if raw in COLOR_MAP:
        return [COLOR_MAP[raw]]

    codes: list[str] = []
    for ch in raw:
        mapped = JP_COLOR_CHAR_MAP.get(ch)
        if mapped and mapped not in codes:
            codes.append(mapped)
    return codes


def parse_skill_html(skill_html: str, skill_type: str) -> SkillData:
    skill_name_match = re.search(r"<span>(.*?)</span>", skill_html, flags=re.DOTALL)
    skill_name = clean_text(skill_name_match.group(1)) if skill_name_match else "未命名技能"
    whole_text = clean_text(skill_html)
    cost_match = re.search(r"ホロパワー：-?(\d+)", whole_text)
    holopower_cost = int(cost_match.group(1)) if cost_match else 0

    # 描述去掉成本與技能名（保留原文敘述，方便後續 effect_json 映射）
    description = whole_text
    description = re.sub(r"^\[ホロパワー：-?\d+\]", "", description).strip()
    description = description.replace(skill_name, "", 1).strip()
    inference_source = description if description else whole_text
    inferred_types = infer_effect_types(inference_source)
    inferred_type = inferred_types[0]
    inferred_target = infer_target_type(inference_source)

    return SkillData(
        skill_type=skill_type,
        skill_name=skill_name,
        holopower_cost=holopower_cost,
        description=description,
        effect_json={
            "type": inferred_type,
            "effects": inferred_types,
            "target": inferred_target,
            "rawText": description,
        },
    )


def resolve_card_type(card_type_jp: str) -> str | None:
    raw = card_type_jp.strip()
    if raw in CARD_TYPE_MAP:
        return CARD_TYPE_MAP[raw]
    if "推しホロメン" in raw:
        return "OSHI"
    # 一些字串同時包含「サポート」與「ホロメン」，先判斷 SUPPORT 避免誤歸類 MEMBER。
    if "サポート" in raw:
        return "SUPPORT"
    if "ホロメン" in raw:
        return "MEMBER"
    if "エール" in raw:
        return "CHEER"
    return None


def parse_member_arts(block_html: str) -> list[ArtData]:
    arts: list[ArtData] = []
    art_blocks = re.findall(
        r"<div class=\"sp arts\"><p>アーツ</p><p>(.*?)</p></div>",
        block_html,
        flags=re.DOTALL,
    )

    for index, art_html in enumerate(art_blocks):
        span_match = re.search(r"<span>(.*?)</span>", art_html, flags=re.DOTALL)
        if not span_match:
            continue
        span_html = span_match.group(1)
        span_text = clean_text(span_html)
        name = normalize_art_name(span_text)
        cost = parse_cost_from_span(span_html)

        effect_text = clean_text(re.sub(r"<span>.*?</span>", "", art_html, count=1, flags=re.DOTALL))
        description = effect_text if effect_text else None

        inference_source = f"{span_text} {effect_text}".strip()
        inferred_types = infer_effect_types(inference_source)

        arts.append(
            ArtData(
                name=name,
                description=description or "",
                cost_cheer_json=cost if cost else {"COLORLESS": 1},
                effect_json={
                    "type": inferred_types[0],
                    "effects": inferred_types,
                    "target": infer_target_type(inference_source),
                    "rawHeader": span_text,
                    "rawEffect": effect_text,
                },
                order_index=index,
            )
        )

    return arts


def parse_support_sections(block_html: str) -> dict[str, str]:
    sections: dict[str, str] = {}
    # 官方 text view 的效果區塊多為「<div ...><p>標題</p><p>內容</p></div>」格式。
    raw_sections = re.findall(
        r"<div class=\"[^\"]*\"><p>(.*?)</p><p>(.*?)</p></div>",
        block_html,
        flags=re.DOTALL,
    )
    for title_raw, body_raw in raw_sections:
        title = clean_text(title_raw)
        body = clean_text(body_raw)
        if not title or not body:
            continue
        sections[title] = body
    return sections


def infer_effect_type(effect_text: str) -> str:
    text = effect_text or ""
    if not text.strip():
        return "UNIMPLEMENTED"

    if "ライフ-1" in text or "残りHP" in text or "特殊ダメージ" in text:
        return "DAMAGE"
    if re.search(r"受けるダメージ\s*-\s*\d+", text):
        return "BUFF"
    if "ダメージを受ける時" in text and ("付け替" in text or "かわりに受ける" in text):
        return "MOVE_ZONE"

    if "手札に加える" in text or "手札に戻す" in text:
        if "デッキ" in text:
            return "SEARCH"
        return "MOVE_ZONE"

    if "デッキから" in text and ("公開し" in text or "公開する" in text or "探し" in text):
        if "ステージに出" in text or "手札に加える" in text or "付ける" in text:
            return "SEARCH"
    if "デッキの上から" in text and ("見る" in text or "公開" in text):
        return "SEARCH"

    if "移動" in text or "お休み" in text or "アクティブにならない" in text:
        return "MOVE_ZONE"
    if "入れ替える" in text or "交代させる" in text:
        return "MOVE_ZONE"
    if "アーカイブする" in text or "アーカイブできる" in text:
        return "MOVE_ZONE"
    if "エールデッキに戻す" in text:
        return "MOVE_ZONE"

    if "アーツ+" in text or "HP+" in text or "無色エール-1" in text:
        return "BUFF"
    if "必要な無色-" in text or "必要とせず" in text or "対象にできる" in text:
        return "BUFF"
    if "サイコロ" in text and ("振り直" in text or "扱う" in text):
        return "BUFF"
    if "もう1回Bloom" in text or "もう1回使う" in text:
        return "BUFF"
    if "Bloomさせる" in text:
        return "BUFF"
    if "使えるLIMITED" in text or ("LIMITED" in text and "枚数" in text and "使える" in text):
        return "BUFF"
    if "アーツ-" in text or "HP-" in text:
        return "DEBUFF"
    if "必要な無色+1" in text:
        return "DEBUFF"

    if "エール" in text and ("減らす" in text or "取り除" in text):
        return "REMOVE_CHEER"
    if "エール" in text and ("アーカイブ" in text or "デッキの下に戻" in text):
        return "REMOVE_CHEER"
    if "引く" in text:
        return "DRAW"
    if "引いた後" in text:
        return "DRAW"
    if "エールデッキ" in text and "送る" in text:
        return "ADD_CHEER"
    if "回復" in text:
        return "HEAL"
    if "ダメージ" in text and ("与える" in text or "受ける" in text):
        return "DAMAGE"
    if "サーチ" in text or "探し" in text:
        return "SEARCH"
    if "エール" in text and ("付け" in text or "送る" in text):
        return "ADD_CHEER"
    if "アーカイブ" in text and ("送る" in text or "置く" in text):
        return "MOVE_ZONE"

    # 常見純打點格式：招式標頭含顏色符號與數值（例如「◇技名 30」「白◇技名 60赤+50」）。
    if re.search(r"[白緑赤青黄紫◇][^\n]{0,80}\d+(?:[^\n]*\+\d+)?", text):
        return "DAMAGE"
    if re.search(r"\b\d+\+?\b", text) and "アーツ" not in text and "引く" not in text and "回復" not in text:
        if "ダメージ" in text or re.search(r"\b\d+\+?\b$", text.strip()):
            return "DAMAGE"
    return "UNIMPLEMENTED"


def infer_target_type(effect_text: str) -> str:
    text = effect_text or ""
    has_self = "自分" in text
    has_enemy = "相手" in text
    if has_self and has_enemy:
        return "BOTH"
    if "相手のバック" in text:
        return "ENEMY"
    if "自分のバック" in text:
        return "SELF"
    if "相手のセンター" in text:
        return "ENEMY_CENTER"
    if "自分のセンター" in text:
        return "SELF_CENTER"
    if "相手" in text:
        return "ENEMY"
    if "自分" in text:
        return "SELF"
    return "ANY_HOLOMEM"


def infer_effect_types(effect_text: str) -> list[str]:
    text = effect_text or ""
    primary = infer_effect_type(text)
    effects: list[str] = [primary]

    def add_effect(effect_name: str) -> None:
        if effect_name not in effects:
            effects.append(effect_name)

    if "引く" in text:
        add_effect("DRAW")
    if (
        "サーチ" in text
        or "探し" in text
        or ("デッキから" in text and ("公開し" in text or "公開する" in text))
        or ("デッキの上から" in text and ("見る" in text or "公開" in text))
    ):
        add_effect("SEARCH")
    if "回復" in text:
        add_effect("HEAL")
    if (
        "ライフ-1" in text
        or "残りHP" in text
        or "特殊ダメージ" in text
        or ("ダメージ" in text and ("与える" in text or "受ける" in text))
    ):
        add_effect("DAMAGE")
    if "受けるダメージ" in text and "-" in text:
        add_effect("BUFF")
    if "アーツ+" in text or "HP+" in text or "必要な無色-" in text or "必要とせず" in text:
        add_effect("BUFF")
    if "アーツ-" in text or "HP-" in text or "必要な無色+1" in text:
        add_effect("DEBUFF")
    if "エール" in text and ("付け" in text or "送る" in text):
        add_effect("ADD_CHEER")
    if "エール" in text and ("減らす" in text or "取り除" in text or "アーカイブ" in text):
        add_effect("REMOVE_CHEER")
    if (
        "移動" in text
        or "入れ替える" in text
        or "交代させる" in text
        or "お休み" in text
        or "アクティブにならない" in text
        or "アーカイブする" in text
        or "アーカイブできる" in text
        or "手札に戻す" in text
        or "手札に加える" in text
    ):
        add_effect("MOVE_ZONE")

    return effects


def parse_cards(html_text: str, expansion_code: str) -> list[CardRecord]:
    cards: list[CardRecord] = []
    cards_by_id: dict[str, CardRecord] = {}
    blocks = re.findall(
        r"<li[^>]*>\s*<a href=\"(/cardlist/\?id=[^\"]+)\">(.*?)</li>",
        html_text,
        flags=re.DOTALL,
    )

    for href, block in blocks:
        card_id_match = re.search(r"<p class=\"number\">(.*?)</p>", block, flags=re.DOTALL)
        name_match = re.search(r"<p class=\"name\">(.*?)</p>", block, flags=re.DOTALL)
        if not card_id_match or not name_match:
            continue

        card_id = clean_text(card_id_match.group(1)).upper()
        name = clean_text(name_match.group(1))
        image_match = re.search(r"<div class=\"img w100\"><img src=\"([^\"]+)\"", block)
        image_url = BASE_URL + image_match.group(1) if image_match else None
        if card_id in cards_by_id:
            existing = cards_by_id[card_id]
            if image_url and image_url != existing.image_url and image_url not in existing.variant_image_urls:
                existing.variant_image_urls.append(image_url)
            continue

        info_pairs = re.findall(r"<dt>(.*?)</dt>\s*<dd>(.*?)</dd>", block, flags=re.DOTALL)
        info_map: dict[str, str] = {}
        for dt_raw, dd_raw in info_pairs:
            info_map[clean_text(dt_raw)] = clean_text(dd_raw)

        card_type_jp = info_map.get("カードタイプ", "")
        card_type = resolve_card_type(card_type_jp)
        if card_type is None:
            continue

        rarity = info_map.get("レアリティ", "")
        tags = [tag for tag in info_map.get("タグ", "").split(" ") if tag.startswith("#")]

        color_dd_raw = next((dd for dt, dd in info_pairs if clean_text(dt) == "色"), "")
        color_alts = extract_img_alts(color_dd_raw)
        color_codes: list[str] = []
        for alt in color_alts:
            parsed = parse_color_codes(alt)
            for code in parsed:
                if code not in color_codes:
                    color_codes.append(code)
        main_color = color_codes[0] if color_codes else None

        life = int(info_map["LIFE"]) if info_map.get("LIFE", "").isdigit() else None
        hp = int(info_map["HP"]) if info_map.get("HP", "").isdigit() else None
        bloom_raw = info_map.get("Bloomレベル")
        level_type = LEVEL_MAP.get(bloom_raw) if bloom_raw else None
        bloom_level = BLOOM_LEVEL_NUMERIC_MAP.get(bloom_raw) if bloom_raw else None

        inferred_expansion = card_id.split("-", 1)[0].upper() if "-" in card_id else expansion_code.upper()

        record = CardRecord(
            card_id=card_id,
            name=name,
            rarity=rarity,
            image_url=image_url,
            card_type=card_type,
            expansion_code=inferred_expansion,
            source_url=BASE_URL + href,
            tags=tags,
            main_color=main_color,
            life=life,
            hp=hp,
            level_type=level_type,
            bloom_level=bloom_level,
        )

        if card_type == "OSHI":
            normal_skill_match = re.search(
                r"<div class=\"oshi skill\"><p>推しスキル</p><p>(.*?)</p></div>",
                block,
                flags=re.DOTALL,
            )
            sp_skill_match = re.search(
                r"<div class=\"sp skill\"><p>SP推しスキル</p><p>(.*?)</p></div>",
                block,
                flags=re.DOTALL,
            )
            if normal_skill_match:
                record.oshi_skills.append(parse_skill_html(normal_skill_match.group(1), "NORMAL"))
            if sp_skill_match:
                record.oshi_skills.append(parse_skill_html(sp_skill_match.group(1), "SP"))

        if card_type == "MEMBER":
            record.member_arts = parse_member_arts(block)
            keyword_match = re.search(
                r"<div class=\"keyword\"><p>(.*?)</p><p>(.*?)</p></div>",
                block,
                flags=re.DOTALL,
            )
            extra_match = re.search(
                r"<div class=\"extra\"><p>(.*?)</p><p>(.*?)</p></div>",
                block,
                flags=re.DOTALL,
            )
            if keyword_match:
                record.member_extra[clean_text(keyword_match.group(1))] = clean_text(keyword_match.group(2))
            if extra_match:
                record.member_extra[clean_text(extra_match.group(1))] = clean_text(extra_match.group(2))

        if card_type == "SUPPORT":
            sections = parse_support_sections(block)
            whole_text = clean_text(block)
            condition_text = sections.get("条件") or sections.get("使用条件")
            effect_text = (
                sections.get("効果")
                or sections.get("能力")
                or sections.get("テキスト")
                or whole_text
            )
            is_limited = (
                "LIMITED" in card_type_jp.upper()
                or "リミテッド" in card_type_jp
                or any("LIMITED" in tag.upper() for tag in tags)
            )
            record.support_is_limited = is_limited
            record.support_condition_type = "RAW_TEXT" if condition_text else None
            record.support_condition_json = {"rawText": condition_text} if condition_text else None
            inferred_types = infer_effect_types(effect_text)
            record.support_effect_type = inferred_types[0]
            record.support_target_type = infer_target_type(effect_text)
            record.support_effect_json = {
                "type": record.support_effect_type,
                "effects": inferred_types,
                "rawText": effect_text,
                "sections": sections,
            }

        if card_type == "CHEER":
            # 色資訊以「色」欄位為主，解析不到時退回 COLORLESS，避免中斷整批匯入。
            record.cheer_color = main_color or "COLORLESS"

        cards.append(record)
        cards_by_id[card_id] = record

    return cards


def build_sql(cards: list[CardRecord], source_url: str, expansion_code: str) -> str:
    lines: list[str] = []
    lines.append(f"-- 匯入官方卡表批次資料（來源：{expansion_code.upper()}）")
    lines.append("-- 由 scripts/official_card_import.py 產生，請勿手動大幅改寫。")
    lines.append(f"-- Source: {source_url}")
    lines.append("")
    lines.append("BEGIN;")
    lines.append("")

    for card in cards:
        tags_json = card.tags if card.tags else []
        lines.append(
            "INSERT INTO cards (card_id, name, rarity, image_url, card_type, expansion_code, card_no, tags_json, source_url) VALUES "
            f"({sql_quote(card.card_id)}, {sql_quote(card.name)}, {sql_quote(card.rarity)}, {sql_nullable(card.image_url)}, "
            f"{sql_quote(card.card_type)}, {sql_quote(card.expansion_code)}, {sql_quote(card.card_id)}, {sql_json(tags_json)}, {sql_quote(card.source_url)}) "
            "ON CONFLICT (card_id) DO UPDATE SET "
            "name = EXCLUDED.name, rarity = EXCLUDED.rarity, image_url = EXCLUDED.image_url, "
            "card_type = EXCLUDED.card_type, expansion_code = EXCLUDED.expansion_code, card_no = EXCLUDED.card_no, "
            "tags_json = EXCLUDED.tags_json, source_url = EXCLUDED.source_url, updated_at = CURRENT_TIMESTAMP;"
        )
        if card.image_url:
            lines.append(
                "INSERT INTO card_variants (card_id, variant_code, variant_name, image_url, source_url, is_default) VALUES "
                f"({sql_quote(card.card_id)}, 'DEFAULT', '預設圖', {sql_quote(card.image_url)}, {sql_quote(card.source_url)}, TRUE) "
                "ON CONFLICT (card_id, variant_code) DO UPDATE SET "
                "variant_name = EXCLUDED.variant_name, image_url = EXCLUDED.image_url, source_url = EXCLUDED.source_url, "
                "is_default = EXCLUDED.is_default, updated_at = CURRENT_TIMESTAMP;"
            )
            for index, variant_url in enumerate(card.variant_image_urls, start=1):
                lines.append(
                    "INSERT INTO card_variants (card_id, variant_code, variant_name, image_url, source_url, is_default) VALUES "
                    f"({sql_quote(card.card_id)}, {sql_quote(f'ALT_{index}')}, {sql_quote(f'變體 {index}')}, "
                    f"{sql_quote(variant_url)}, {sql_quote(card.source_url)}, FALSE) "
                    "ON CONFLICT (card_id, variant_code) DO UPDATE SET "
                    "variant_name = EXCLUDED.variant_name, image_url = EXCLUDED.image_url, source_url = EXCLUDED.source_url, "
                    "is_default = EXCLUDED.is_default, updated_at = CURRENT_TIMESTAMP;"
                )

        if card.card_type == "OSHI":
            if card.life is None or card.main_color is None:
                raise ValueError(f"OSHI 卡片缺少 LIFE 或主色：{card.card_id}")
            lines.append(
                "INSERT INTO oshi_cards (card_id, life, main_color, sub_color) VALUES "
                f"({sql_quote(card.card_id)}, {card.life}, {sql_quote(card.main_color)}, NULL) "
                "ON CONFLICT (card_id) DO UPDATE SET "
                "life = EXCLUDED.life, main_color = EXCLUDED.main_color, sub_color = EXCLUDED.sub_color, "
                "updated_at = CURRENT_TIMESTAMP;"
            )
            lines.append(f"DELETE FROM oshi_skills WHERE oshi_card_id = {sql_quote(card.card_id)};")
            for skill in card.oshi_skills:
                lines.append(
                    "INSERT INTO oshi_skills (oshi_card_id, skill_type, skill_name, description, holopower_cost, effect_json) VALUES "
                    f"({sql_quote(card.card_id)}, {sql_quote(skill.skill_type)}, {sql_quote(skill.skill_name)}, "
                    f"{sql_quote(skill.description)}, {skill.holopower_cost}, {sql_json(skill.effect_json)});"
                )

        if card.card_type == "MEMBER":
            if card.hp is None or card.level_type is None or card.main_color is None:
                raise ValueError(f"MEMBER 卡片缺少 HP / Bloom / 色：{card.card_id}")
            passive_json = card.member_extra if card.member_extra else None
            lines.append(
                "INSERT INTO member_cards (card_id, hp, level_type, main_color, sub_color, bloom_level, passive_effect_json, trigger_condition) VALUES "
                f"({sql_quote(card.card_id)}, {card.hp}, {sql_quote(card.level_type)}, {sql_quote(card.main_color)}, NULL, "
                f"{sql_nullable(card.bloom_level)}, {sql_json(passive_json) if passive_json else 'NULL'}, NULL) "
                "ON CONFLICT (card_id) DO UPDATE SET "
                "hp = EXCLUDED.hp, level_type = EXCLUDED.level_type, main_color = EXCLUDED.main_color, "
                "sub_color = EXCLUDED.sub_color, bloom_level = EXCLUDED.bloom_level, "
                "passive_effect_json = EXCLUDED.passive_effect_json, trigger_condition = EXCLUDED.trigger_condition, "
                "updated_at = CURRENT_TIMESTAMP;"
            )
            lines.append(f"DELETE FROM member_arts WHERE member_card_id = {sql_quote(card.card_id)};")
            for art in card.member_arts:
                lines.append(
                    "INSERT INTO member_arts (member_card_id, name, description, cost_cheer_json, effect_json, order_index) VALUES "
                    f"({sql_quote(card.card_id)}, {sql_quote(art.name)}, {sql_quote(art.description)}, "
                    f"{sql_json(art.cost_cheer_json)}, {sql_json(art.effect_json)}, {art.order_index});"
                )

        if card.card_type == "SUPPORT":
            effect_type = card.support_effect_type or "UNIMPLEMENTED"
            target_type = card.support_target_type or "ANY_HOLOMEM"
            effect_json = card.support_effect_json or {"type": "UNIMPLEMENTED", "rawText": ""}
            lines.append(
                "INSERT INTO support_cards (card_id, is_limited, condition_type, condition_json, effect_type, effect_json, target_type) VALUES "
                f"({sql_quote(card.card_id)}, {'TRUE' if card.support_is_limited else 'FALSE'}, "
                f"{sql_nullable(card.support_condition_type)}, "
                f"{sql_json(card.support_condition_json) if card.support_condition_json else 'NULL'}, "
                f"{sql_quote(effect_type)}, {sql_json(effect_json)}, {sql_quote(target_type)}) "
                "ON CONFLICT (card_id) DO UPDATE SET "
                "is_limited = EXCLUDED.is_limited, condition_type = EXCLUDED.condition_type, "
                "condition_json = EXCLUDED.condition_json, effect_type = EXCLUDED.effect_type, "
                "effect_json = EXCLUDED.effect_json, target_type = EXCLUDED.target_type, "
                "updated_at = CURRENT_TIMESTAMP;"
            )

        if card.card_type == "CHEER":
            cheer_color = card.cheer_color or "COLORLESS"
            lines.append(
                "INSERT INTO cheer_cards (card_id, color) VALUES "
                f"({sql_quote(card.card_id)}, {sql_quote(cheer_color)}) "
                "ON CONFLICT (card_id) DO UPDATE SET "
                "color = EXCLUDED.color, updated_at = CURRENT_TIMESTAMP;"
            )

        lines.append("")

    lines.append("COMMIT;")
    lines.append("")
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="抓取官方卡表並產生 SQL migration")
    parser.add_argument("--expansion", default="hSD13", help="官方 expansion 代碼，例如 hSD13")
    parser.add_argument("--sort", default="new", help="排序參數（new/old/no...）")
    parser.add_argument(
        "--output",
        default="src/main/resources/db/migration/V9__seed_official_hsd13_batch_01.sql",
        help="輸出 SQL 檔案路徑",
    )
    parser.add_argument(
        "--input-html",
        default="",
        help="離線模式：指定已下載的 HTML 檔案，避免重新抓網頁",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    source_url = f"{BASE_URL}/cardlist/cardsearch/?expansion={args.expansion}&view=text&sort={args.sort}"
    html_text = Path(args.input_html).read_text(encoding="utf-8") if args.input_html else fetch_html(args.expansion, args.sort)

    cards = parse_cards(html_text, args.expansion)
    if not cards:
        raise RuntimeError("解析不到任何卡片，請檢查 expansion 代碼或頁面格式是否改版。")

    sql_text = build_sql(cards, source_url, args.expansion)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(sql_text, encoding="utf-8")
    print(f"[OK] 已產生 {output}，共 {len(cards)} 張卡片")


if __name__ == "__main__":
    main()
