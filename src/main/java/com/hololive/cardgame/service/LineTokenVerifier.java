package com.hololive.cardgame.service;

import org.springframework.stereotype.Service;

@Service
public class LineTokenVerifier {

    public String verifyIdToken(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new IllegalArgumentException("idToken 不可為空");
        }

        // 開發階段先支援 mock token：
        // mock:<lineUserId> 或 mock_<lineUserId>
        if (idToken.startsWith("mock:")) {
            String lineUserId = idToken.substring("mock:".length());
            if (!lineUserId.isBlank()) {
                return lineUserId;
            }
        }
        if (idToken.startsWith("mock_")) {
            String lineUserId = idToken.substring("mock_".length());
            if (!lineUserId.isBlank()) {
                return lineUserId;
            }
        }

        throw new IllegalArgumentException("目前僅支援 mock idToken（mock:<lineUserId>）");
    }
}

