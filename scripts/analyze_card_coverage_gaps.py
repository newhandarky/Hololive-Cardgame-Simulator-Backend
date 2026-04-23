#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
CARD_ID_PATTERN = re.compile(r"\bH[A-Z0-9]+-\d{3}\b")
LINE_PREFIXES = (
    "INSERT INTO cards ",
    "INSERT INTO support_cards ",
    "INSERT INTO oshi_skills ",
    "INSERT INTO member_cards ",
    "INSERT INTO member_arts ",
)
IMPORTANT_TAGS = (
    "SELF_DOWNED",
    "ALLY_DOWNED",
    "OPPONENT_DOWNED",
    "DAMAGE_RECEIVED",
    "DEAL_DAMAGE_TRIGGER",
    "DICE",
    "REATTACH",
    "ARCHIVE_CHEER",
    "ARCHIVE_CHEER_ATTACH",
    "ARCHIVE_MEMBER_TO_HAND",
    "ARCHIVE_MEMBER_TO_DECK_TOP",
    "SEARCH_TO_HAND",
    "TURN_ARTS_BUFF",
    "SPECIAL_DAMAGE",
    "BLOOM_EFFECT",
    "COLLAB_EFFECT",
    "PASSIVE_GIFT",
    "FAN_SUPPORT",
    "MASCOT_SUPPORT",
    "TOOL_SUPPORT",
    "OSHI_SKILL",
    "SUPPORT_CARD",
    "MEMBER_ART",
    "PASSIVE_TEXT",
)


@dataclass
class CardMeta:
    card_id: str
    name: str
    card_type: str
    expansion_code: str


@dataclass
class CardFeature:
    effect_tokens: set[str] = field(default_factory=set)
    exact_effect_sets: set[tuple[str, ...]] = field(default_factory=set)
    tags: set[str] = field(default_factory=set)
    sources: set[str] = field(default_factory=set)


@dataclass
class SmokeCoverageRule:
    rule_id: str
    description: str
    card_type: str | None = None
    sources_any: set[str] = field(default_factory=set)
    effect_tokens_any: set[str] = field(default_factory=set)
    effect_tokens_all: set[str] = field(default_factory=set)
    exclude_effect_tokens_any: set[str] = field(default_factory=set)
    tags_any: set[str] = field(default_factory=set)
    tags_all: set[str] = field(default_factory=set)
    exclude_tags_any: set[str] = field(default_factory=set)


def sql_unquote(token: str) -> str:
    token = token.strip()
    if token.startswith("'") and token.endswith("'"):
        token = token[1:-1]
    return token.replace("''", "'")


def split_sql_values(segment: str) -> list[str]:
    values: list[str] = []
    current: list[str] = []
    in_quote = False
    index = 0
    while index < len(segment):
        char = segment[index]
        if in_quote:
            current.append(char)
            if char == "'":
                if index + 1 < len(segment) and segment[index + 1] == "'":
                    current.append(segment[index + 1])
                    index += 2
                    continue
                in_quote = False
            index += 1
            continue
        if char == "'":
            in_quote = True
            current.append(char)
        elif char == ",":
            values.append("".join(current).strip())
            current = []
        else:
            current.append(char)
        index += 1
    if current:
        values.append("".join(current).strip())
    return values


def split_sql_statements(text: str) -> list[str]:
    statements: list[str] = []
    current: list[str] = []
    in_quote = False
    index = 0
    while index < len(text):
        char = text[index]
        current.append(char)
        if in_quote:
            if char == "'" and index + 1 < len(text) and text[index + 1] == "'":
                current.append(text[index + 1])
                index += 2
                continue
            if char == "'":
                in_quote = False
        else:
            if char == "'":
                in_quote = True
            elif char == ";":
                statement = "".join(current).strip()
                if statement:
                    statements.append(statement)
                current = []
        index += 1
    trailing = "".join(current).strip()
    if trailing:
        statements.append(trailing)
    return statements


def extract_values_segment(line: str) -> str:
    values_index = line.find("VALUES")
    if values_index < 0:
        raise ValueError(f"Cannot locate VALUES keyword: {line[:80]}")

    start = line.find("(", values_index)
    if start < 0:
        raise ValueError(f"Cannot locate opening parenthesis after VALUES: {line[:80]}")

    end = line.find(") ON CONFLICT", start)
    if end >= 0:
        return line[start + 1:end]

    depth = 0
    in_quote = False
    index = start
    while index < len(line):
        char = line[index]
        if in_quote:
            if char == "'" and index + 1 < len(line) and line[index + 1] == "'":
                index += 2
                continue
            if char == "'":
                in_quote = False
            index += 1
            continue
        if char == "'":
            in_quote = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return line[start + 1:index]
        index += 1

    raise ValueError(f"Cannot locate VALUES segment: {line[:80]}")


def parse_sql_token(token: str) -> Any:
    stripped = token.strip()
    if stripped == "NULL":
        return None
    if stripped == "TRUE":
        return True
    if stripped == "FALSE":
        return False
    if stripped.endswith("::jsonb"):
        return json.loads(sql_unquote(stripped[:-7]))
    if stripped.startswith("'") and stripped.endswith("'"):
        return sql_unquote(stripped)
    if re.fullmatch(r"-?\d+", stripped):
        return int(stripped)
    return stripped


def normalize_effect_token(value: str | None) -> str:
    if value is None:
        return ""
    return re.sub(r"[^A-Z0-9_]+", "_", value.strip().upper()).strip("_")


def iter_text_fragments(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        fragments: list[str] = []
        for item in value:
            fragments.extend(iter_text_fragments(item))
        return fragments
    if isinstance(value, dict):
        fragments = []
        for item in value.values():
            fragments.extend(iter_text_fragments(item))
        return fragments
    return [str(value)]


def extract_effect_tokens(effect_json: Any) -> list[str]:
    if not isinstance(effect_json, dict):
        return []
    tokens: list[str] = []
    effects = effect_json.get("effects")
    if isinstance(effects, list):
        for item in effects:
            normalized = normalize_effect_token(item if isinstance(item, str) else str(item))
            if normalized:
                tokens.append(normalized)
    if not tokens:
        normalized = normalize_effect_token(effect_json.get("type"))
        if normalized:
            tokens.append(normalized)
    return tokens


def extract_text_tags(text: str, source_tag: str) -> set[str]:
    normalized = text.replace("\\n", "\n").replace("１", "1").replace("～", "~")
    tags = {source_tag}
    if "サイコロ" in normalized:
        tags.add("DICE")
    if "付け替える" in normalized:
        tags.add("REATTACH")
    if "特殊ダメージ" in normalized:
        tags.add("SPECIAL_DAMAGE")
    if "デッキから" in normalized and "手札に加" in normalized:
        tags.add("SEARCH_TO_HAND")
    if "このターンの間" in normalized and ("アーツ+" in normalized or "アーツ＋" in normalized):
        tags.add("TURN_ARTS_BUFF")
    if "アーカイブ" in normalized and "エール" in normalized:
        tags.add("ARCHIVE_CHEER")
    if "アーカイブのエール" in normalized and ("送る" in normalized or "付け" in normalized):
        tags.add("ARCHIVE_CHEER_ATTACH")
    if "アーカイブのホロメン" in normalized and "手札に戻" in normalized:
        tags.add("ARCHIVE_MEMBER_TO_HAND")
    if "アーカイブのホロメン" in normalized and "デッキの上" in normalized:
        tags.add("ARCHIVE_MEMBER_TO_DECK_TOP")
    if "ギフト" in normalized:
        tags.add("PASSIVE_GIFT")
    bloom_blocked_text = "Bloomできない" in normalized or "ブルームできない" in normalized
    if (
        "ブルームエフェクト" in normalized
        or ("Bloom" in normalized and not bloom_blocked_text)
        or ("ブルーム" in normalized and not bloom_blocked_text)
    ):
        tags.add("BLOOM_EFFECT")
    if "コラボエフェクト" in normalized or "コラボ" in normalized:
        tags.add("COLLAB_EFFECT")
    if "ファン" in normalized:
        tags.add("FAN_SUPPORT")
    if "マスコット" in normalized:
        tags.add("MASCOT_SUPPORT")
    if "ツール" in normalized:
        tags.add("TOOL_SUPPORT")
    if "このホロメンがダウンした時" in normalized or "このファンが付いているホロメンがダウンした時" in normalized:
        tags.add("SELF_DOWNED")
    elif "自分のホロメンがダウンした時" in normalized or "自分の" in normalized and "がダウンした時" in normalized:
        tags.add("ALLY_DOWNED")
    if "相手のホロメンをダウンさせた時" in normalized or "相手のホロメンがダウンした時" in normalized:
        tags.add("OPPONENT_DOWNED")
    if "ダメージを受ける時" in normalized or "ダメージを受けた時" in normalized:
        tags.add("DAMAGE_RECEIVED")
    if "ダメージを与えた時" in normalized:
        tags.add("DEAL_DAMAGE_TRIGGER")
    return tags


def card_sort_key(card_id: str) -> tuple[str, int]:
    match = re.fullmatch(r"([A-Z0-9]+)-(\d+)", card_id)
    if not match:
        return (card_id, 0)
    return (match.group(1), int(match.group(2)))


def feature_signature(feature: CardFeature, card_type: str) -> str:
    effect_tokens = sorted(feature.effect_tokens) or ["TEXT_ONLY"]
    tag_tokens = sorted(tag for tag in feature.tags if tag in IMPORTANT_TAGS)
    tag_part = ", ".join(tag_tokens[:8]) if tag_tokens else "NONE"
    return f"{card_type} | effects={'+'.join(effect_tokens)} | tags={tag_part}"


def short_card_label(card_id: str, cards: dict[str, CardMeta]) -> str:
    meta = cards.get(card_id)
    if meta is None:
        return card_id
    return f"{card_id} {meta.name}"


def parse_migration(migration_path: Path) -> tuple[dict[str, CardMeta], dict[str, CardFeature]]:
    cards: dict[str, CardMeta] = {}
    features: dict[str, CardFeature] = defaultdict(CardFeature)

    for statement in split_sql_statements(migration_path.read_text(encoding="utf-8")):
        line = statement.strip()
        if not line.startswith(LINE_PREFIXES):
            continue
        values = split_sql_values(extract_values_segment(line))

        if line.startswith("INSERT INTO cards "):
            card_id = parse_sql_token(values[0])
            name = parse_sql_token(values[1])
            card_type = parse_sql_token(values[4])
            expansion_code = parse_sql_token(values[5])
            cards[card_id] = CardMeta(
                card_id=card_id,
                name=name,
                card_type=card_type,
                expansion_code=expansion_code,
            )
            continue

        if line.startswith("INSERT INTO support_cards "):
            card_id = parse_sql_token(values[0])
            effect_json = parse_sql_token(values[5])
            feature = features[card_id]
            tokens = extract_effect_tokens(effect_json)
            feature.effect_tokens.update(tokens)
            if tokens:
                feature.exact_effect_sets.add(tuple(tokens))
            for fragment in iter_text_fragments(effect_json):
                feature.tags.update(extract_text_tags(fragment, "SUPPORT_CARD"))
            feature.sources.add("SUPPORT_CARD")
            continue

        if line.startswith("INSERT INTO oshi_skills "):
            card_id = parse_sql_token(values[0])
            effect_json = parse_sql_token(values[5])
            feature = features[card_id]
            tokens = extract_effect_tokens(effect_json)
            feature.effect_tokens.update(tokens)
            if tokens:
                feature.exact_effect_sets.add(tuple(tokens))
            for fragment in iter_text_fragments(effect_json):
                feature.tags.update(extract_text_tags(fragment, "OSHI_SKILL"))
            feature.sources.add("OSHI_SKILL")
            continue

        if line.startswith("INSERT INTO member_cards "):
            card_id = parse_sql_token(values[0])
            passive_effect_json = parse_sql_token(values[6])
            feature = features[card_id]
            for fragment in iter_text_fragments(passive_effect_json):
                feature.tags.update(extract_text_tags(fragment, "PASSIVE_TEXT"))
            if passive_effect_json is not None:
                feature.sources.add("PASSIVE_TEXT")
            continue

        if line.startswith("INSERT INTO member_arts "):
            card_id = parse_sql_token(values[0])
            effect_json = parse_sql_token(values[4])
            feature = features[card_id]
            tokens = extract_effect_tokens(effect_json)
            feature.effect_tokens.update(tokens)
            if tokens:
                feature.exact_effect_sets.add(tuple(tokens))
            for fragment in iter_text_fragments(effect_json):
                feature.tags.update(extract_text_tags(fragment, "MEMBER_ART"))
            feature.sources.add("MEMBER_ART")

    return cards, features


def load_covered_cards(test_path: Path) -> set[str]:
    return set(CARD_ID_PATTERN.findall(test_path.read_text(encoding="utf-8")))


def normalize_token_set(values: Any) -> set[str]:
    if not isinstance(values, list):
        return set()
    normalized: set[str] = set()
    for value in values:
        token = normalize_effect_token(str(value))
        if token:
            normalized.add(token)
    return normalized


def load_smoke_coverage_rules(rules_path: Path) -> list[SmokeCoverageRule]:
    if not rules_path.exists():
        return []
    payload = json.loads(rules_path.read_text(encoding="utf-8"))
    raw_rules = payload.get("rules", []) if isinstance(payload, dict) else []
    rules: list[SmokeCoverageRule] = []
    for index, raw_rule in enumerate(raw_rules):
        if not isinstance(raw_rule, dict):
            continue
        rule_id = str(raw_rule.get("id") or f"rule-{index + 1}")
        card_type = raw_rule.get("cardType")
        rules.append(
            SmokeCoverageRule(
                rule_id=rule_id,
                description=str(raw_rule.get("description") or rule_id),
                card_type=normalize_effect_token(str(card_type)) if card_type else None,
                sources_any=normalize_token_set(raw_rule.get("sourcesAny")),
                effect_tokens_any=normalize_token_set(raw_rule.get("effectTokensAny")),
                effect_tokens_all=normalize_token_set(raw_rule.get("effectTokensAll")),
                exclude_effect_tokens_any=normalize_token_set(raw_rule.get("excludeEffectTokensAny")),
                tags_any=normalize_token_set(raw_rule.get("tagsAny")),
                tags_all=normalize_token_set(raw_rule.get("tagsAll")),
                exclude_tags_any=normalize_token_set(raw_rule.get("excludeTagsAny")),
            )
        )
    return rules


def smoke_rule_matches(rule: SmokeCoverageRule, meta: CardMeta, feature: CardFeature) -> bool:
    if rule.card_type and normalize_effect_token(meta.card_type) != rule.card_type:
        return False
    feature_effects = {normalize_effect_token(token) for token in feature.effect_tokens}
    feature_tags = {normalize_effect_token(tag) for tag in feature.tags}
    feature_sources = {normalize_effect_token(source) for source in feature.sources}

    if rule.sources_any and feature_sources.isdisjoint(rule.sources_any):
        return False
    if rule.effect_tokens_any and feature_effects.isdisjoint(rule.effect_tokens_any):
        return False
    if rule.effect_tokens_all and not rule.effect_tokens_all.issubset(feature_effects):
        return False
    if rule.exclude_effect_tokens_any and not feature_effects.isdisjoint(rule.exclude_effect_tokens_any):
        return False
    if rule.tags_any and feature_tags.isdisjoint(rule.tags_any):
        return False
    if rule.tags_all and not rule.tags_all.issubset(feature_tags):
        return False
    if rule.exclude_tags_any and not feature_tags.isdisjoint(rule.exclude_tags_any):
        return False
    return True


def build_smoke_coverage(
    cards: dict[str, CardMeta],
    features: dict[str, CardFeature],
    official_ids: list[str],
    rules: list[SmokeCoverageRule],
) -> tuple[set[str], dict[str, list[str]]]:
    smoke_covered: set[str] = set()
    by_rule: dict[str, list[str]] = {}
    for rule in rules:
        matched = [
            card_id
            for card_id in official_ids
            if smoke_rule_matches(rule, cards[card_id], features.get(card_id, CardFeature()))
        ]
        matched.sort(key=card_sort_key)
        if matched:
            by_rule[rule.rule_id] = matched
            smoke_covered.update(matched)
    return smoke_covered, by_rule


def render_markdown(
    cards: dict[str, CardMeta],
    features: dict[str, CardFeature],
    explicit_covered: set[str],
    smoke_covered: set[str],
    smoke_by_rule: dict[str, list[str]],
    smoke_rules: list[SmokeCoverageRule],
    official_ids: list[str],
    uncovered_ids: list[str],
    migration_path: Path,
    test_path: Path,
    smoke_rules_path: Path,
    top_series_limit: int,
    top_batch_limit: int,
) -> str:
    official_set = set(official_ids)
    explicit_official_covered = explicit_covered & official_set
    smoke_official_covered = smoke_covered & official_set
    covered = explicit_official_covered | smoke_official_covered
    uncovered_by_series = Counter(card_id.split("-")[0] for card_id in uncovered_ids)
    uncovered_by_card_type = Counter(cards[card_id].card_type for card_id in uncovered_ids)

    batch_to_cards: dict[str, list[str]] = defaultdict(list)
    covered_batch_examples: dict[str, list[str]] = defaultdict(list)

    for card_id in official_ids:
        meta = cards[card_id]
        signature = feature_signature(features.get(card_id, CardFeature()), meta.card_type)
        if card_id in covered:
            covered_batch_examples[signature].append(card_id)
        else:
            batch_to_cards[signature].append(card_id)

    reuse_ready_batches: list[tuple[str, list[str], list[str]]] = []
    new_mechanism_batches: list[tuple[str, list[str], list[str]]] = []
    for signature, card_ids in batch_to_cards.items():
        covered_examples = sorted(covered_batch_examples.get(signature, []), key=card_sort_key)
        payload = (signature, sorted(card_ids, key=card_sort_key), covered_examples)
        if covered_examples:
            reuse_ready_batches.append(payload)
        else:
            new_mechanism_batches.append(payload)

    reuse_ready_batches.sort(key=lambda item: (-len(item[1]), item[0]))
    new_mechanism_batches.sort(key=lambda item: (-len(item[1]), item[0]))

    lines: list[str] = []
    lines.append("# Coverage Gap Analysis")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(f"- Migration source: `{migration_path}`")
    lines.append(f"- Explicit coverage source: `{test_path}`")
    lines.append(f"- Smoke coverage rules: `{smoke_rules_path}`")
    lines.append(f"- Official non-CHEER cards: `{len(official_ids)}`")
    lines.append(f"- Explicit card ids in integration tests: `{len(explicit_official_covered)}`")
    lines.append(f"- Automated smoke-covered card ids: `{len(smoke_official_covered)}`")
    lines.append(f"- Covered by explicit or smoke coverage: `{len(covered)}`")
    lines.append(f"- Uncovered card ids: `{len(uncovered_ids)}`")
    lines.append("")
    lines.append("## How To Read")
    lines.append("")
    lines.append("- `explicit` means the card id appears directly in an integration test.")
    lines.append("- `smoke-covered` means an automated integration smoke bucket exercises every card matching a rule.")
    lines.append("- `uncovered` means the card id is not covered by either explicit card-id tests or smoke bucket rules.")
    lines.append("- `reuse-ready` means uncovered cards share a batch signature with at least one already-covered card.")
    lines.append("- `new-mechanism` means this batch currently has no covered template; treat it as engine or parser gap first.")
    lines.append("")
    lines.append("## Smoke Coverage Rules")
    lines.append("")
    if not smoke_rules:
        lines.append("- None")
    else:
        for rule in smoke_rules:
            matched = smoke_by_rule.get(rule.rule_id, [])
            examples = ", ".join(short_card_label(card_id, cards) for card_id in matched[:6])
            lines.append(f"- `{rule.rule_id}`: `{len(matched)}` cards")
            lines.append(f"  {rule.description}")
            lines.append(f"  examples: {examples if examples else 'None'}")
    lines.append("")
    lines.append("## Uncovered By Series")
    lines.append("")
    for series, count in uncovered_by_series.most_common(top_series_limit):
        lines.append(f"- `{series}`: `{count}`")
    lines.append("")
    lines.append("## Uncovered By Card Type")
    lines.append("")
    for card_type, count in uncovered_by_card_type.most_common():
        lines.append(f"- `{card_type}`: `{count}`")
    lines.append("")
    lines.append("## Reuse-Ready Batches")
    lines.append("")
    if not reuse_ready_batches:
        lines.append("- None")
    else:
        for signature, card_ids, covered_examples in reuse_ready_batches[:top_batch_limit]:
            uncovered_examples = ", ".join(short_card_label(card_id, cards) for card_id in card_ids[:6])
            covered_examples_text = ", ".join(short_card_label(card_id, cards) for card_id in covered_examples[:4])
            lines.append(f"- `{signature}`")
            lines.append(f"  uncovered: `{len(card_ids)}`")
            lines.append(f"  covered examples: {covered_examples_text if covered_examples_text else 'None'}")
            lines.append(f"  uncovered examples: {uncovered_examples}")
    lines.append("")
    lines.append("## New-Mechanism Batches")
    lines.append("")
    if not new_mechanism_batches:
        lines.append("- None")
    else:
        for signature, card_ids, _ in new_mechanism_batches[:top_batch_limit]:
            uncovered_examples = ", ".join(short_card_label(card_id, cards) for card_id in card_ids[:6])
            lines.append(f"- `{signature}`")
            lines.append(f"  uncovered: `{len(card_ids)}`")
            lines.append(f"  uncovered examples: {uncovered_examples}")
    lines.append("")
    lines.append("## First 80 Uncovered Card IDs")
    lines.append("")
    for card_id in uncovered_ids[:80]:
        lines.append(f"- `{card_id}` {cards[card_id].name} [{cards[card_id].card_type}]")
    lines.append("")
    lines.append("## Suggested AI Workflow")
    lines.append("")
    lines.append("- Pick one `reuse-ready` batch with the highest uncovered count first.")
    lines.append("- Implement or refactor the shared mechanism once, then add 2-4 integration tests that cover the batch pattern.")
    lines.append("- Re-run this script after each merged batch instead of manually counting remaining cards.")
    lines.append("- Only drop to single-card handling when the card lands in a `new-mechanism` batch of size 1 or 2.")
    lines.append("")
    return "\n".join(lines) + "\n"


def build_json_payload(
    cards: dict[str, CardMeta],
    features: dict[str, CardFeature],
    explicit_covered: set[str],
    smoke_covered: set[str],
    smoke_by_rule: dict[str, list[str]],
    official_ids: list[str],
    uncovered_ids: list[str],
) -> dict[str, Any]:
    official_set = set(official_ids)
    covered = (explicit_covered | smoke_covered) & official_set
    batches: dict[str, dict[str, Any]] = {}
    for card_id in official_ids:
        signature = feature_signature(features.get(card_id, CardFeature()), cards[card_id].card_type)
        bucket = batches.setdefault(
            signature,
            {
                "signature": signature,
                "covered_card_ids": [],
                "uncovered_card_ids": [],
            },
        )
        target_key = "covered_card_ids" if card_id in covered else "uncovered_card_ids"
        bucket[target_key].append(card_id)
    for bucket in batches.values():
        bucket["covered_card_ids"].sort(key=card_sort_key)
        bucket["uncovered_card_ids"].sort(key=card_sort_key)
    return {
        "summary": {
            "official_non_cheer_cards": len(official_ids),
            "explicit_card_ids": len(explicit_covered & official_set),
            "smoke_covered_card_ids": len(smoke_covered & official_set),
            "covered_card_ids": len(covered),
            "uncovered_card_ids": len(uncovered_ids),
        },
        "smoke_coverage": {
            rule_id: card_ids
            for rule_id, card_ids in sorted(smoke_by_rule.items())
        },
        "uncovered_card_ids": uncovered_ids,
        "batches": sorted(batches.values(), key=lambda item: (-len(item["uncovered_card_ids"]), item["signature"])),
    }


def resolve_repo_path(raw_path: str) -> Path:
    path = Path(raw_path)
    if path.is_absolute():
        return path
    return REPO_ROOT / path


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Analyze uncovered official card ids and group them into reusable implementation batches."
    )
    parser.add_argument(
        "--migration-file",
        default="src/main/resources/db/migration/V40__add_multi_effects_array_from_official_all.sql",
        help="Migration file used as the official card/effect source.",
    )
    parser.add_argument(
        "--test-file",
        default="src/test/java/com/hololive/cardgame/service/MatchActionServiceIntegrationTest.java",
        help="Integration test file used to determine covered card ids.",
    )
    parser.add_argument(
        "--output-md",
        default="doc/coverage-gap-analysis.md",
        help="Markdown report output path.",
    )
    parser.add_argument(
        "--output-json",
        default="",
        help="Optional JSON output path for machine-readable post-processing.",
    )
    parser.add_argument(
        "--smoke-rules-file",
        default="src/test/resources/card-smoke-coverage-rules.json",
        help="Optional JSON file describing automated smoke coverage buckets.",
    )
    parser.add_argument(
        "--include-cheer",
        action="store_true",
        help="Include CHEER cards in the official total.",
    )
    parser.add_argument(
        "--top-series-limit",
        type=int,
        default=15,
        help="How many series rows to print in the markdown summary.",
    )
    parser.add_argument(
        "--top-batch-limit",
        type=int,
        default=25,
        help="How many batch rows to print per section in the markdown report.",
    )
    args = parser.parse_args()

    migration_path = resolve_repo_path(args.migration_file)
    test_path = resolve_repo_path(args.test_file)
    smoke_rules_path = resolve_repo_path(args.smoke_rules_file)
    output_md_path = resolve_repo_path(args.output_md)

    cards, features = parse_migration(migration_path)
    explicit_covered = load_covered_cards(test_path)

    official_ids = sorted(
        [
            card_id
            for card_id, meta in cards.items()
            if meta.expansion_code.startswith("H") and (args.include_cheer or meta.card_type != "CHEER")
        ],
        key=card_sort_key,
    )
    smoke_rules = load_smoke_coverage_rules(smoke_rules_path)
    smoke_covered, smoke_by_rule = build_smoke_coverage(cards, features, official_ids, smoke_rules)
    covered = (explicit_covered | smoke_covered) & set(official_ids)
    uncovered_ids = [card_id for card_id in official_ids if card_id not in covered]

    markdown = render_markdown(
        cards=cards,
        features=features,
        explicit_covered=explicit_covered,
        smoke_covered=smoke_covered,
        smoke_by_rule=smoke_by_rule,
        smoke_rules=smoke_rules,
        official_ids=official_ids,
        uncovered_ids=uncovered_ids,
        migration_path=migration_path,
        test_path=test_path,
        smoke_rules_path=smoke_rules_path,
        top_series_limit=args.top_series_limit,
        top_batch_limit=args.top_batch_limit,
    )
    output_md_path.parent.mkdir(parents=True, exist_ok=True)
    output_md_path.write_text(markdown, encoding="utf-8")

    if args.output_json:
        output_json_path = resolve_repo_path(args.output_json)
        payload = build_json_payload(
            cards,
            features,
            explicit_covered,
            smoke_covered,
            smoke_by_rule,
            official_ids,
            uncovered_ids,
        )
        output_json_path.parent.mkdir(parents=True, exist_ok=True)
        output_json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"Wrote markdown report to: {output_md_path.relative_to(REPO_ROOT)}")
    if args.output_json:
        try:
            json_display_path = output_json_path.relative_to(REPO_ROOT)
        except ValueError:
            json_display_path = output_json_path
        print(f"Wrote json report to: {json_display_path}")


if __name__ == "__main__":
    main()
