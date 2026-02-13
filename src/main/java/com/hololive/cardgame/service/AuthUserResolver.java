package com.hololive.cardgame.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthUserResolver {

    public Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof Long userId) {
            return userId;
        }
        if (principal instanceof String text && text.matches("\\d+")) {
            return Long.valueOf(text);
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登入");
    }
}
