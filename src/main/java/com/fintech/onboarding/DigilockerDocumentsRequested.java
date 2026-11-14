package com.fintech.onboarding;

import java.util.UUID;

public record DigilockerDocumentsRequested(UUID journeyId, UUID userId, String clientId) {}

