package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.LineLoginRequest;
import com.hololive.cardgame.dto.LineLoginResponse;
import com.hololive.cardgame.entity.User;
import com.hololive.cardgame.repository.UserRepository;
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

    public AuthController(
        LineTokenVerifier lineTokenVerifier,
        JwtTokenProvider jwtTokenProvider,
        UserRepository userRepository
    ) {
        this.lineTokenVerifier = lineTokenVerifier;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
    }

    @PostMapping("/line-login")
    public ResponseEntity<LineLoginResponse> lineLogin(@RequestBody LineLoginRequest request) {
        String lineUserId = lineTokenVerifier.verifyIdToken(request.getIdToken());

        User user = findOrCreateUser(lineUserId, request);

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

        String token = jwtTokenProvider.generateToken(user.getId(), lineUserId);
        return ResponseEntity.ok(new LineLoginResponse(token, user.getId(), user.getDisplayName()));
    }

    private User findOrCreateUser(String lineUserId, LineLoginRequest request) {
        return userRepository.findByLineUserId(lineUserId)
            .orElseGet(() -> createUserWithRaceFallback(lineUserId, request));
    }

    private User createUserWithRaceFallback(String lineUserId, LineLoginRequest request) {
        User newUser = new User();
        newUser.setLineUserId(lineUserId);
        newUser.setDisplayName(
            request.getDisplayName() == null || request.getDisplayName().isBlank()
                ? "使用者"
                : request.getDisplayName()
        );
        newUser.setAvatarUrl(request.getAvatarUrl());

        try {
            return userRepository.save(newUser);
        } catch (DataIntegrityViolationException ex) {
            return userRepository.findByLineUserId(lineUserId).orElseThrow(() -> ex);
        }
    }
}
