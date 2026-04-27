package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameRuleException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlayCardApplicationService {

    private final PlayCardLegacyResolutionBridge playCardLegacyResolutionBridge;
    private final PlayCardActionValidator playCardActionValidator;
    private final PlayCardActionResolver playCardActionResolver;
    private final PlayCardEventFactory playCardEventFactory;
    private final PlayCardTriggerDispatcher playCardTriggerDispatcher;

    public PlayCardApplicationService(
        PlayCardLegacyResolutionBridge playCardLegacyResolutionBridge,
        PlayCardActionValidator playCardActionValidator,
        PlayCardActionResolver playCardActionResolver,
        PlayCardEventFactory playCardEventFactory,
        PlayCardTriggerDispatcher playCardTriggerDispatcher
    ) {
        this.playCardLegacyResolutionBridge = playCardLegacyResolutionBridge;
        this.playCardActionValidator = playCardActionValidator;
        this.playCardActionResolver = playCardActionResolver;
        this.playCardEventFactory = playCardEventFactory;
        this.playCardTriggerDispatcher = playCardTriggerDispatcher;
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

    public PlayCardTriggerDispatchResult dispatchResolvedEvents(
        PlayCardAction action,
        PlayCardResolutionResult resolutionResult,
        PlayCardEffectResolution effectResolution
    ) {
        List<PlayCardEvent> events = playCardEventFactory.createEvents(action, resolutionResult, effectResolution);
        return playCardTriggerDispatcher.dispatch(events);
    }
}
