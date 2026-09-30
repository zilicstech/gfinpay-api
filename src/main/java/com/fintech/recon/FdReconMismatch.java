package com.fintech.recon;

import java.util.Map;

public record FdReconMismatch(String type, String partnerRef, Map<String, Object> details) {}
