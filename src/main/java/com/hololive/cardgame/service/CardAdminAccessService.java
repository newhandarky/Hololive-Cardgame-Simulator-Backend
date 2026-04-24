package com.hololive.cardgame.service;

import com.hololive.cardgame.config.CardAdminProperties;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CardAdminAccessService {

    private final CardAdminProperties cardAdminProperties;

    public CardAdminAccessService(CardAdminProperties cardAdminProperties) {
        this.cardAdminProperties = cardAdminProperties;
    }

    public boolean isAllowed(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }
        Set<Long> allowedUserIds = cardAdminProperties.getAllowedUserIds();
        return allowedUserIds != null && !allowedUserIds.isEmpty() && allowedUserIds.contains(userId);
    }

    public void assertAllowed(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登入");
        }
        Set<Long> allowedUserIds = cardAdminProperties.getAllowedUserIds();
        if (allowedUserIds == null || allowedUserIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "card-admin 尚未設定可用帳號");
        }
        if (!isAllowed(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你沒有 card-admin 權限");
        }
    }
}
