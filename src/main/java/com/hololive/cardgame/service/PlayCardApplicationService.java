package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlayCardApplicationService {

    private final PlayCardLegacyResolutionBridge playCardLegacyResolutionBridge;
    private final PlayCardActionValidator playCardActionValidator;
    private final PlayCardActionResolver playCardActionResolver;

    public PlayCardApplicationService(
        PlayCardLegacyResolutionBridge playCardLegacyResolutionBridge,
        PlayCardActionValidator playCardActionValidator,
        PlayCardActionResolver playCardActionResolver
    ) {
        this.playCardLegacyResolutionBridge = playCardLegacyResolutionBridge;
        this.playCardActionValidator = playCardActionValidator;
        this.playCardActionResolver = playCardActionResolver;
    }

    @Transactional
    public PlayCardValidationContext validate(PlayCardAction action) {
        PlayCardValidationContext validationContext = playCardLegacyResolutionBridge.loadValidationContext(action);
        PlayCardValidationResult validationResult = playCardActionValidator.validate(action, validationContext);
        if (!validationResult.allowed()) {
            throw new GameRuleException(
                validationResult.errorCode(),
                validationResult.message(),
                validationResult.details()
            );
        }
        return validationContext;
    }

    @Transactional
    public PlayCardResolutionResult resolveState(
        PlayCardAction action,
        PlayCardValidationContext validationContext
    ) {
        return playCardActionResolver.resolve(action, validationContext);
    }
}
