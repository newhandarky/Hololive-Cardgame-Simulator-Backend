package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.LineLoginRequest;
import com.hololive.cardgame.dto.LineLoginResponse;
import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.repository.UserRepository;
import com.hololive.cardgame.service.DeckService;
import com.hololive.cardgame.service.JwtTokenProvider;
import com.hololive.cardgame.service.LineTokenVerifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LineTokenVerifier lineTokenVerifier;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final DeckService deckService;

    /**
     * 認證控制器，負責 LINE 登入與 JWT 簽發。
     */
    public AuthController(
        LineTokenVerifier lineTokenVerifier,
        JwtTokenProvider jwtTokenProvider,
        UserRepository userRepository,
        DeckService deckService
    ) {
        this.lineTokenVerifier = lineTokenVerifier;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
        this.deckService = deckService;
    }

    @PostMapping("/line-login")
    /**
     * 以 LINE idToken 登入，必要時建立新使用者並回傳 JWT。
     */
    public ResponseEntity<LineLoginResponse> lineLogin(@RequestBody LineLoginRequest request) {
        String lineUserId = lineTokenVerifier.verifyIdToken(request.getIdToken());

        UserUpsertResult upsertResult = findOrCreateUser(lineUserId, request);
        User user = upsertResult.user();

        // 若已有使用者且前端帶了新顯示名稱/頭像，進行同步
        boolean updated = false;
        if (request.getDisplayName() != null && !request.getDisplayName().isBlank()
            && !request.getDisplayName().equals(user.getDisplayName())) {
            user.setDisplayName(request.getDisplayName());
            updated = true;
        }
        if (request.getAvatarUrl() != null && !request.getAvatarUrl().equals(user.getAvatarUrl())) {
            user.setAvatarUrl(request.getAvatarUrl());
            updated = true;
        }
        if (updated) {
            user = userRepository.save(user);
        }

        // 每次登入都嘗試補齊官方預設套牌：新使用者直接建、舊使用者補缺漏。
        try {
            deckService.bootstrapStarterDecksForUser(user.getId());
        } catch (RuntimeException ignored) {
            // 若缺少官方卡片資料，回退為單一可測試牌組，仍允許登入。
            try {
                if (upsertResult.created()) {
                    deckService.setupQuickDeck(user.getId(), "AUTO");
                }
            } catch (RuntimeException ignoredAgain) {
                // 保底不阻擋登入流程。
            }
        }

        String token = jwtTokenProvider.generateToken(user.getId(), lineUserId);
        return ResponseEntity.ok(new LineLoginResponse(token, user.getId(), user.getDisplayName()));
    }

    /**
     * 以 lineUserId 查找使用者，若不存在則建立。
     */
    private UserUpsertResult findOrCreateUser(String lineUserId, LineLoginRequest request) {
        return userRepository.findByLineUserId(lineUserId)
            .map(user -> new UserUpsertResult(user, false))
            .orElseGet(() -> createUserWithRaceFallback(lineUserId, request));
    }

    /**
     * 建立新使用者並處理並發下的唯一鍵衝突回退。
     */
    private UserUpsertResult createUserWithRaceFallback(String lineUserId, LineLoginRequest request) {
        User newUser = new User();
        newUser.setLineUserId(lineUserId);
        newUser.setDisplayName(
            request.getDisplayName() == null || request.getDisplayName().isBlank()
                ? "使用者"
                : request.getDisplayName()
        );
        newUser.setAvatarUrl(request.getAvatarUrl());

        try {
            return new UserUpsertResult(userRepository.save(newUser), true);
        } catch (DataIntegrityViolationException ex) {
            User existingUser = userRepository.findByLineUserId(lineUserId).orElseThrow(() -> ex);
            return new UserUpsertResult(existingUser, false);
        }
    }

    private record UserUpsertResult(User user, boolean created) {}
}
