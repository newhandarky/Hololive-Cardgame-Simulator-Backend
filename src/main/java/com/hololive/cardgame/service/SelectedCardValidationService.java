package com.hololive.cardgame.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class SelectedCardValidationService {

    List<Long> sanitize(List<Long> selectedCardInstanceIds) {
        if (selectedCardInstanceIds == null || selectedCardInstanceIds.isEmpty()) {
            return List.of();
        }
        List<Long> normalized = new ArrayList<>();
        for (Long value : selectedCardInstanceIds) {
            if (value == null || value <= 0 || normalized.contains(value)) {
                continue;
            }
            normalized.add(value);
        }
        return normalized;
    }

    List<Long> validate(
        List<Long> selectedCardInstanceIds,
        int minSelect,
        int maxSelect,
        List<Long> candidateCardInstanceIds
    ) {
        List<Long> selected = sanitize(selectedCardInstanceIds);
        if (selected.size() < minSelect) {
            throw new IllegalArgumentException("選擇卡片數量不足，至少需要 " + minSelect + " 張");
        }
        if (selected.size() > maxSelect) {
            throw new IllegalArgumentException("選擇卡片數量超過上限，最多只能選 " + maxSelect + " 張");
        }
        validateWithinCandidates(selected, candidateCardInstanceIds);
        return selected;
    }

    private void validateWithinCandidates(List<Long> selected, List<Long> candidates) {
        if (selected == null || selected.isEmpty() || candidates == null || candidates.isEmpty()) {
            return;
        }
        Set<Long> candidateSet = Set.copyOf(candidates);
        for (Long selectedId : selected) {
            if (!candidateSet.contains(selectedId)) {
                throw new IllegalArgumentException("選擇的卡片不在候選清單內: " + selectedId);
            }
        }
    }
}
