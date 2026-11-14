package com.fintech.kyc.surepass;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.surepass.mode", havingValue = "mock")
public class MockSurepassClient implements SurepassClient {

    private static final Logger log = LoggerFactory.getLogger(MockSurepassClient.class);
    private static final byte[] MOCK_PHOTO = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 'J', 'F', 'I', 'F'};

    private final Map<String, String> sessionNames = new ConcurrentHashMap<>();
    private final Set<String> omitPanClientIds = ConcurrentHashMap.newKeySet();
    private final Map<String, PanOverride> panOverrides = new ConcurrentHashMap<>();
    private volatile String lastAadhaarLast4 = "1234";

    public record PanOverride(boolean aadhaarLinked, String maskedAadhaar) {}

    public void setOmitPanForClient(String clientId, boolean omit) {
        if (omit) {
            omitPanClientIds.add(clientId);
        } else {
            omitPanClientIds.remove(clientId);
        }
    }

    public void setPanAadhaarOverride(String pan, boolean aadhaarLinked, String maskedAadhaar) {
        if (pan != null) {
            panOverrides.put(pan.trim().toUpperCase(Locale.ROOT), new PanOverride(aadhaarLinked, maskedAadhaar));
        }
    }

    public void clearPanAadhaarOverride(String pan) {
        if (pan != null) {
            panOverrides.remove(pan.trim().toUpperCase(Locale.ROOT));
        }
    }

    @Override
    public DigilockerSession digilockerInitialize(String defaultNameHint) {
        String clientId = "sp-dl-" + UUID.randomUUID().toString().substring(0, 8);
        String name = (defaultNameHint == null || defaultNameHint.isBlank()) ? "Partner Demo" : defaultNameHint.trim();
        sessionNames.put(clientId, name);
        String sdkToken = "mock-sdk-token-" + clientId;
        log.info("MOCK_SUREPASS digilockerInitialize clientId={} name={}", clientId, name);
        return new DigilockerSession(clientId, sdkToken, 600L, "production");
    }

    @Override
    public DigilockerStatus digilockerStatus(String clientId) {
        return new DigilockerStatus(clientId, true, false, "completed", null, true);
    }

    @Override
    public List<DigilockerDocument> digilockerListDocuments(String clientId) {
        if (omitPanClientIds.contains(clientId)) {
            return List.of(
                    new DigilockerDocument("aadhaar", "Aadhaar Card", "ADHAR", true,
                            "Unique Identification Authority of India (UIDAI)", "Aadhaar Card", "pdf"),
                    new DigilockerDocument("digilocker_file_adhar_xml", "Aadhaar Card", "ADHAR", true,
                            "Unique Identification Authority of India (UIDAI)", "Aadhaar Card", "xml")
            );
        }
        return List.of(
                new DigilockerDocument("aadhaar", "Aadhaar Card", "ADHAR", true,
                        "Unique Identification Authority of India (UIDAI)", "Aadhaar Card", "pdf"),
                new DigilockerDocument("digilocker_file_adhar_xml", "Aadhaar Card", "ADHAR", true,
                        "Unique Identification Authority of India (UIDAI)", "Aadhaar Card", "xml"),
                new DigilockerDocument("digilocker_file_pancr_pdf", "PAN Verification Record", "PANCR", true,
                        "Income Tax Department", "PAN Verification Record", "pdf"),
                new DigilockerDocument("pan", "PAN Verification Record", "PANCR", true,
                        "Income Tax Department", "PAN Verification Record", "xml"),
                new DigilockerDocument("digilocker_file_ntmst_pdf", "JEE Main Score Card", "NTMST", true,
                        "National Testing Agency", "JEE Main Score Card", "pdf")
        );
    }

    @Override
    public DigilockerDownloadLink digilockerDownloadDocument(String clientId, String fileId) {
        boolean isXml = fileId != null && (fileId.endsWith("xml") || "pan".equals(fileId));
        String mime = isXml ? "application/xml" : "application/pdf";
        String ext = isXml ? "xml" : "pdf";
        return new DigilockerDownloadLink(
                "mock://digilocker.local/" + clientId + "/" + fileId + "." + ext,
                mime
        );
    }

    @Override
    public AadhaarRecord digilockerAadhaar(String clientId) {
        String name = sessionNames.getOrDefault(clientId, "Partner Demo");
        String last4 = String.format("%04d", Math.abs(clientId.hashCode()) % 10000);
        this.lastAadhaarLast4 = last4;
        String fullAddr = "12, MG Road, Near Metro, Indiranagar, Bengaluru, Karnataka - 560038";
        AadhaarAddress structured = new AadhaarAddress(
                "S/O Ramesh Sharma",
                "12",
                "MG Road",
                "Near Metro",
                "Indiranagar",
                "Bengaluru",
                "Indiranagar",
                "Bengaluru East",
                "Bengaluru Urban",
                "Karnataka",
                "560038",
                "India",
                fullAddr
        );
        return new AadhaarRecord(
                clientId,
                name,
                "1990-05-15",
                "Male",
                fullAddr,
                "Bengaluru",
                "Karnataka",
                "560038",
                "XXXXXXXX" + last4,
                MOCK_PHOTO,
                structured
        );
    }

    @Override
    public PanRecord panComprehensive(String pan, String defaultNameHint) {
        String upper = pan == null ? "" : pan.trim().toUpperCase(Locale.ROOT);
        boolean triggerMismatch = upper.startsWith("FAIL") || upper.endsWith("0000Z");
        String name = triggerMismatch
                ? "UNRELATED PERSON NAME"
                : ((defaultNameHint == null || defaultNameHint.isBlank()) ? "Partner Demo" : defaultNameHint.trim());
        PanOverride override = panOverrides.get(upper);
        boolean aadhaarSeeded = override != null ? override.aadhaarLinked() : true;
        String maskedAadhaar = override != null
                ? override.maskedAadhaar()
                : ("XXXXXXXX" + lastAadhaarLast4);
        String ref = "sp-pan-" + UUID.randomUUID().toString().substring(0, 8);
        return new PanRecord(upper, name, "VALID", true, aadhaarSeeded, maskedAadhaar, "1990-05-15", "M", ref);
    }

    @Override
    public BankRecord bankVerification(String accountNumber, String ifsc, String defaultNameHint) {
        String acc = accountNumber == null ? "" : accountNumber.trim();
        String normIfsc = ifsc == null ? "DEMO0001234" : ifsc.trim().toUpperCase(Locale.ROOT);
        String bankCode = normIfsc.length() >= 4 ? normIfsc.substring(0, 4) : "DEMO";
        boolean accountDoesNotExist = acc.endsWith("9999");
        boolean triggerMismatch = acc.endsWith("0000");
        String name = accountDoesNotExist
                ? ""
                : triggerMismatch
                        ? "DIFFERENT ACCOUNT HOLDER"
                        : ((defaultNameHint == null || defaultNameHint.isBlank()) ? "Partner Demo" : defaultNameHint.trim());
        String bank = bankCode + " Bank";
        String ref = "sp-bank-" + UUID.randomUUID().toString().substring(0, 8);
        String impsRef = accountDoesNotExist ? "" : "5060" + String.format("%08d", Math.abs(acc.hashCode()) % 100_000_000);
        IfscDetails details = new IfscDetails(
                normIfsc,
                "560002001",
                "IN-KA",
                null,
                bank,
                bankCode,
                bank,
                "MAIN BRANCH",
                "BENGALURU",
                "BENGALURU URBAN",
                "KARNATAKA",
                "BENGALURU",
                "MG ROAD, BENGALURU",
                true,
                true,
                true,
                true,
                true
        );
        return new BankRecord(
                !accountDoesNotExist,
                name,
                bank,
                ref,
                impsRef,
                accountDoesNotExist ? "Account does not exist" : "",
                accountDoesNotExist ? "failed" : "success",
                null,
                details
        );
    }

    @Override
    public GstinRecord gstin(String gstin) {
        String clean = gstin == null ? "" : gstin.trim().toUpperCase(Locale.ROOT);
        String ref = "sp-gstin-" + UUID.randomUUID().toString().substring(0, 8);
        return new GstinRecord(
                clean,
                "Demo Fin Services Pvt Ltd",
                "Demo Fin Services",
                "12 MG Road, Indiranagar, Bengaluru, Karnataka - 560038",
                "ACTIVE",
                true,
                ref
        );
    }

    @Override
    public UdyamRecord udyam(String udyam) {
        String clean = udyam == null ? "" : udyam.trim().toUpperCase(Locale.ROOT);
        String ref = "sp-udyam-" + UUID.randomUUID().toString().substring(0, 8);
        return new UdyamRecord(
                clean,
                "Demo Fin Enterprises",
                "MICRO",
                "12 MG Road, Indiranagar, Bengaluru, Karnataka - 560038",
                true,
                ref
        );
    }

    private final Map<String, CompanyRecord> companyOverrides = new ConcurrentHashMap<>();

    public void setCompanyOverride(String cin, CompanyRecord record) {
        if (cin != null && record != null) {
            companyOverrides.put(cin.trim().toUpperCase(Locale.ROOT), record);
        }
    }

    public void clearCompanyOverride(String cin) {
        if (cin != null) {
            companyOverrides.remove(cin.trim().toUpperCase(Locale.ROOT));
        }
    }

    @Override
    public CompanyRecord company(String cin) {
        String clean = cin == null ? "" : cin.trim().toUpperCase(Locale.ROOT);
        CompanyRecord override = companyOverrides.get(clean);
        if (override != null) {
            return override;
        }
        String ref = "sp-cin-" + UUID.randomUUID().toString().substring(0, 8);
        boolean inactive = clean.contains("INACTIVE") || clean.endsWith("000000");
        String status = inactive ? "Strike Off" : "Active";
        List<Director> directors = List.of(
                new Director("ANNU JHAMNANI", "10472494", "2024-02-28", "", true),
                new Director("HEMANT MISHRA", "09278529", "2024-01-19", "", true)
        );
        return new CompanyRecord(
                clean,
                "ZILICS VENTURES PRIVATE LIMITED",
                "NO 301/302 3RD FLOOR SARJ Bangalore Bangalore South Karnataka India 560035",
                status,
                true,
                ref,
                "company",
                "Private",
                "2024-01-19",
                "ROC Bangalore",
                "560035",
                directors
        );
    }
}
