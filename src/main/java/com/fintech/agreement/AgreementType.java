package com.fintech.agreement;

public enum AgreementType {
    RETAILER_MSA("retailer-msa-v1", "R", "Retailer"),
    DISTRIBUTOR_MSA("distributor-msa-v1", "D", "Distributor");

    private final String templateVersion;
    private final String codeLetter;
    private final String roleLabel;

    AgreementType(String templateVersion, String codeLetter, String roleLabel) {
        this.templateVersion = templateVersion;
        this.codeLetter = codeLetter;
        this.roleLabel = roleLabel;
    }

    public String templateVersion() {
        return templateVersion;
    }

    public String codeLetter() {
        return codeLetter;
    }

    public String roleLabel() {
        return roleLabel;
    }

    public static AgreementType forUserType(String userType) {
        if ("MASTER_DISTRIBUTOR".equalsIgnoreCase(userType) || "DISTRIBUTOR".equalsIgnoreCase(userType)) {
            return DISTRIBUTOR_MSA;
        }
        return RETAILER_MSA;
    }
}

