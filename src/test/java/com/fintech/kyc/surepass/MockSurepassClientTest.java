package com.fintech.kyc.surepass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MockSurepassClientTest {

    private final MockSurepassClient client = new MockSurepassClient();

    @Test
    void digilockerAndPanAndPennyDropHappyPaths() {
        SurepassClient.DigilockerSession init =
                client.digilockerInitialize("Rajesh Sharma");
        assertNotNull(init.clientId());
        assertNotNull(init.sdkToken());
        assertEquals("production", init.gateway());

        SurepassClient.DigilockerStatus status = client.digilockerStatus(init.clientId());
        assertTrue(status.completed());
        assertTrue(status.aadhaarLinked());

        List<SurepassClient.DigilockerDocument> docs = client.digilockerListDocuments(init.clientId());
        assertTrue(docs.stream().anyMatch(d -> "ADHAR".equals(d.docType()) && "pdf".equals(d.fileType())));
        assertTrue(docs.stream().anyMatch(d -> "PANCR".equals(d.docType()) && "pdf".equals(d.fileType())));

        SurepassClient.AadhaarRecord doc = client.digilockerAadhaar(init.clientId());
        assertEquals("Rajesh Sharma", doc.fullName());
        assertTrue(doc.maskedAadhaar().startsWith("XXXXXXXX"));
        assertNotNull(doc.photoBytes());
        assertNotNull(doc.structuredAddress());
        assertEquals("560038", doc.structuredAddress().pincode());

        SurepassClient.PanRecord pan = client.panComprehensive("ABCDE1234F", "Rajesh Sharma");
        assertTrue(pan.valid());
        assertTrue(pan.aadhaarSeeded());
        assertEquals("Rajesh Sharma", pan.fullName());

        SurepassClient.BankRecord bank = client.bankVerification("123456789012", "HDFC0001234", "Rajesh Sharma");
        assertTrue(bank.accountExists());
        assertEquals("Rajesh Sharma", bank.fullName());

        SurepassClient.GstinRecord gst = client.gstin("29ABCDE1234F1Z5");
        assertTrue(gst.valid());
        assertEquals("ACTIVE", gst.status());
    }

    @Test
    void simulatesFailuresOnSentinelInputs() {
        SurepassClient.PanRecord mismatchPan = client.panComprehensive("FAILP1234F", "Rajesh Sharma");
        assertEquals("UNRELATED PERSON NAME", mismatchPan.fullName());

        SurepassClient.BankRecord mismatchBank = client.bankVerification("123456780000", "HDFC0001234", "Rajesh Sharma");
        assertEquals("DIFFERENT ACCOUNT HOLDER", mismatchBank.fullName());
    }
}
