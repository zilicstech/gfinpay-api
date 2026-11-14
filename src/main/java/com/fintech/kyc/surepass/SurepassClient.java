package com.fintech.kyc.surepass;

import java.util.List;

public interface SurepassClient {

    record DigilockerSession(String clientId, String sdkToken, long expirySeconds, String gateway) {}

    record DigilockerStatus(
            String clientId,
            boolean completed,
            boolean failed,
            String status,
            String errorDescription,
            boolean aadhaarLinked
    ) {}

    record DigilockerDocument(
            String fileId,
            String name,
            String docType,
            boolean downloaded,
            String issuer,
            String description,
            String fileType
    ) {}

    record DigilockerDownloadLink(String downloadUrl, String mimeType) {}

    record AadhaarAddress(
            String careOf,
            String house,
            String street,
            String landmark,
            String locality,
            String vtc,
            String postOffice,
            String subDistrict,
            String district,
            String state,
            String pincode,
            String country,
            String fullAddress
    ) {}

    record AadhaarRecord(
            String clientId,
            String fullName,
            String dob,
            String gender,
            String address,
            String city,
            String state,
            String pincode,
            String maskedAadhaar,
            byte[] photoBytes,
            AadhaarAddress structuredAddress
    ) {
        public AadhaarRecord(
                String clientId,
                String fullName,
                String dob,
                String gender,
                String address,
                String city,
                String state,
                String pincode,
                String maskedAadhaar,
                byte[] photoBytes
        ) {
            this(clientId, fullName, dob, gender, address, city, state, pincode, maskedAadhaar, photoBytes,
                    new AadhaarAddress("", "", "", "", "", city, "", "", city, state, pincode, "India", address));
        }
    }

    record PanRecord(
            String panNumber,
            String fullName,
            String status,
            boolean valid,
            boolean aadhaarSeeded,
            String maskedAadhaar,
            String dob,
            String gender,
            String providerRef
    ) {
        public PanRecord(
                String panNumber,
                String fullName,
                String status,
                boolean valid,
                boolean aadhaarSeeded,
                String providerRef
        ) {
            this(panNumber, fullName, status, valid, aadhaarSeeded, "", "", "", providerRef);
        }
    }

    /**
     * Structured IFSC branch metadata returned by Surepass {@code /api/v1/bank-verification}.
     * Note: branch {@code contact} phone number is intentionally omitted and never mapped.
     */
    record IfscDetails(
            String ifsc,
            String micr,
            String iso3166,
            String swift,
            String bank,
            String bankCode,
            String bankName,
            String branch,
            String centre,
            String district,
            String state,
            String city,
            String address,
            boolean imps,
            boolean rtgs,
            boolean neft,
            boolean upi,
            boolean micrCheck
    ) {}

    record BankRecord(
            boolean accountExists,
            String fullName,
            String bankName,
            String providerRef,
            String impsRefNo,
            String remarks,
            String status,
            String upiId,
            IfscDetails ifscDetails
    ) {
        public BankRecord(boolean accountExists, String fullName, String bankName, String providerRef) {
            this(accountExists, fullName, bankName, providerRef, "", "", accountExists ? "success" : "failed", null, null);
        }
    }

    record GstinRecord(
            String gstin,
            String legalName,
            String tradeName,
            String address,
            String status,
            boolean valid,
            String providerRef
    ) {}

    record UdyamRecord(
            String udyamNumber,
            String enterpriseName,
            String enterpriseType,
            String address,
            boolean valid,
            String providerRef
    ) {}

    record Director(
            String name,
            String din,
            String startDate,
            String endDate,
            boolean active
    ) {}

    record CompanyRecord(
            String cin,
            String companyName,
            String registeredAddress,
            String status,
            boolean valid,
            String providerRef,
            String companyType,
            String classOfCompany,
            String dateOfIncorporation,
            String rocCode,
            String pincode,
            List<Director> directors
    ) {
        public CompanyRecord(
                String cin,
                String companyName,
                String registeredAddress,
                String status,
                boolean valid,
                String providerRef
        ) {
            this(cin, companyName, registeredAddress, status, valid, providerRef,
                    "company", "Private", "", "", "", List.of());
        }
    }

    DigilockerSession digilockerInitialize(String defaultNameHint);

    DigilockerStatus digilockerStatus(String clientId);

    List<DigilockerDocument> digilockerListDocuments(String clientId);

    DigilockerDownloadLink digilockerDownloadDocument(String clientId, String fileId);

    AadhaarRecord digilockerAadhaar(String clientId);

    PanRecord panComprehensive(String pan, String defaultNameHint);

    BankRecord bankVerification(String accountNumber, String ifsc, String defaultNameHint);

    GstinRecord gstin(String gstin);

    UdyamRecord udyam(String udyam);

    CompanyRecord company(String cin);
}
