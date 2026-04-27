package com.hololive.cardgame.service;

import com.hololive.cardgame.error.GameRuleException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachCheerApplicationService {

    private final AttachCheerLegacyResolutionBridge attachCheerLegacyResolutionBridge;
    private final AttachCheerActionValidator attachCheerActionValidator;
    private final AttachCheerActionResolver attachCheerActionResolver;
    private final AttachCheerEventFactory attachCheerEventFactory;
    private final AttachCheerTriggerDispatcher attachCheerTriggerDispatcher;

    public AttachCheerApplicationService(
        AttachCheerLegacyResolutionBridge attachCheerLegacyResolutionBridge,
        AttachCheerActionValidator attachCheerActionValidator,
        AttachCheerActionResolver attachCheerActionResolver,
        AttachCheerEventFactory attachCheerEventFactory,
        AttachCheerTriggerDispatcher attachCheerTriggerDispatcher
    ) {
        this.attachCheerLegacyResolutionBridge = attachCheerLegacyResolutionBridge;
        this.attachCheerActionValidator = attachCheerActionValidator;
        this.attachCheerActionResolver = attachCheerActionResolver;
        this.attachCheerEventFactory = attachCheerEventFactory;
        this.attachCheerTriggerDispatcher = attachCheerTriggerDispatcher;
    }

    @Transactional
    public AttachCheerValidationContext validate(AttachCheerAction action) {
        AttachCheerValidationContext validationContext = attachCheerLegacyResolutionBridge.loadValidationContext(action);
        AttachCheerValidationResult validationResult = attachCheerActionValidator.validate(action, validationContext);
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
    public AttachCheerResolutionResult resolveState(
        AttachCheerAction action,
        AttachCheerValidationContext validationContext
    ) {
        return attachCheerActionResolver.resolve(action, validationContext);
    }

    public AttachCheerTriggerDispatchResult dispatchResolvedEvents(
        AttachCheerAction action,
        AttachCheerResolutionResult resolutionResult
    ) {
        List<AttachCheerEvent> events = attachCheerEventFactory.createEvents(action, resolutionResult);
        return attachCheerTriggerDispatcher.dispatch(events);
    }
}
