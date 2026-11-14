package com.fintech.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fintech.kyc.surepass.SurepassClient;
import com.fintech.platform.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AddressFormatterTest {

    @Test
    void formatsPermanentAddressAndCopiesToBusinessAddress() {
        SurepassClient.AadhaarAddress addr = new SurepassClient.AadhaarAddress(
                "S/O Ramesh Sharma",
                "12",
                "MG Road",
                "Near Metro",
                "Indiranagar",
                "Bengaluru",
                "Indiranagar",
                "Bengaluru East",
                "Bengaluru Urban",
                "KA",
                "560038",
                "India",
                ""
        );

        Map<String, Object> perm = AddressFormatter.toPermanentAddressMap(addr, "dl-123", Instant.now());
        assertEquals("Karnataka", perm.get("state"));
        assertEquals("560038", perm.get("pincode"));
        assertTrue(String.valueOf(perm.get("fullAddress")).contains("MG Road"));

        Map<String, Object> copied = AddressFormatter.copyFromPermanentAddress(
                perm, "", "", "", "", "12 MG Road, Bengaluru", new BigDecimal("12.9716"), new BigDecimal("77.5946"));
        assertEquals(true, copied.get("sameAsPersonal"));
        assertEquals("COPIED_FROM_AADHAAR", copied.get("source"));
        assertEquals("Karnataka", copied.get("state"));
        assertEquals("560038", copied.get("pincode"));
        assertFalse(AddressFormatter.differsFromPersonal(copied, perm, "560038", "Karnataka"));
    }

    @Test
    void validatesBusinessAddressAndDetectsDifferenceFromPersonal() {
        SurepassClient.AadhaarAddress addr = new SurepassClient.AadhaarAddress(
                "", "12", "MG Road", "", "Indiranagar", "Bengaluru", "", "", "Bengaluru Urban",
                "Karnataka", "560038", "India", "12, MG Road, Bengaluru, Karnataka - 560038"
        );
        Map<String, Object> perm = AddressFormatter.toPermanentAddressMap(addr, "dl-123", Instant.now());

        Map<String, Object> biz = AddressFormatter.buildBusinessAddress(
                "Shop 9, Commercial Complex", "2nd Main", "Opp Bus Stand",
                "Mysuru", "Mysuru", "KA", "570001", null, null, null);
        assertEquals("Karnataka", biz.get("state"));
        assertEquals("570001", biz.get("pincode"));
        assertEquals(false, biz.get("sameAsPersonal"));
        assertTrue(AddressFormatter.differsFromPersonal(biz, perm, "560038", "Karnataka"));

        assertThrows(ApiException.class, () -> AddressFormatter.validatePincode("012345"));
        assertThrows(ApiException.class, () -> AddressFormatter.normalizeState("NotAState"));
    }
}

