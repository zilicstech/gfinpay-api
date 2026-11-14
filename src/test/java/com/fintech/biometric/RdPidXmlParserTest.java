package com.fintech.biometric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fintech.platform.web.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RdPidXmlParserTest {

    private static final String MANTRA_L1_PID_XML = """
            <?xml version="1.0"?>
            <PidData>
              <Resp errCode="0" errInfo="Success." fCount="1" fType="2" nmPoints="48" qScore="82"/>
              <DeviceInfo dpId="MANTRA.MSIPL" rdsId="MANTRA.AND.001" rdsVer="1.0.8" mi="MFS110"
                          mc="MIIC5zCCAc+gAwIBAgIGAX" dc="28d7f4b2-11aa-45cc-9e01-abcdef123456">
                <additional_info>
                  <Param name="srno" value="9421887"/>
                  <Param name="sysid" value="SYS998877"/>
                  <Param name="ts" value="2026-11-13T12:00:00+05:30"/>
                </additional_info>
              </DeviceInfo>
              <Skey ci="20281029">SESSION_KEY_BASE64_PAYLOAD==</Skey>
              <Hmac>HMAC_SHA256_BASE64==</Hmac>
              <Data type="X">ENCRYPTED_PID_BLOCK_BASE64==</Data>
            </PidData>
            """;

    private static final String MORPHO_L1_PID_XML = """
            <PidData>
              <Resp errCode="0" errInfo="Success" fCount="1" fType="2" iCount="0" iType="0" pCount="0" pType="0" nmPoints="52" qScore="91"/>
              <DeviceInfo dpId="IDEMIA.NOB" rdsId="IDEMIA.WIN.001" rdsVer="2.1.0" mi="MSO1300E3"
                          mc="MORPHO_CERT_B64" dc="morpho-dc-001">
                <additional_info>
                  <Param name="device_srno" value="2140I009911"/>
                </additional_info>
              </DeviceInfo>
              <Skey ci="20291231">MORPHO_SKEY_B64</Skey>
              <Hmac>MORPHO_HMAC_B64</Hmac>
              <Data type="X">MORPHO_PID_B64</Data>
            </PidData>
            """;

    @Test
    void extractsAll26MandatoryEmeraldBiometricParametersFromMantraAndMorpho() {
        RdPidXmlParser.ParsedPidData mantra = RdPidXmlParser.parse(MANTRA_L1_PID_XML);
        Map<String, String> map26 = mantra.toEmeraldBiometricData("499118665240");

        assertEquals(26, map26.size());
        assertEquals("499118665240", map26.get("encryptedAadhaar"));
        assertEquals("28d7f4b2-11aa-45cc-9e01-abcdef123456", map26.get("dc"));
        assertEquals("20281029", map26.get("ci"));
        assertEquals("HMAC_SHA256_BASE64==", map26.get("hmac"));
        assertEquals("MANTRA.MSIPL", map26.get("dpId"));
        assertEquals("MANTRA.AND.001", map26.get("rdsId"));
        assertEquals("1.0.8", map26.get("rdsVer"));
        assertEquals("MFS110", map26.get("mi"));
        assertEquals("9421887", map26.get("srno"));
        assertEquals("SYS998877", map26.get("sysid"));
        assertEquals("82", map26.get("qScore"));
        assertEquals("48", map26.get("nmPoints"));
        assertEquals("ENCRYPTED_PID_BLOCK_BASE64==", map26.get("pidData"));

        RdPidXmlParser.ParsedPidData morpho = RdPidXmlParser.parse(MORPHO_L1_PID_XML);
        Map<String, String> morphoMap = morpho.toEmeraldBiometricData("499118665240");
        assertEquals(26, morphoMap.size());
        assertEquals("IDEMIA.NOB", morphoMap.get("dpId"));
        assertEquals("2140I009911", morphoMap.get("srno"));
        assertEquals("91", morphoMap.get("qScore"));
    }

    @Test
    void rejectsScannerFailureErrCodeCleanly() {
        String timeoutXml = """
                <PidData>
                  <Resp errCode="720" errInfo="Device not ready or capture timed out"/>
                </PidData>
                """;
        ApiException ex = assertThrows(ApiException.class, () -> RdPidXmlParser.parse(timeoutXml));
        assertEquals("BIOMETRIC_SCANNER_ERROR", ex.getCode());
    }

    @Test
    void blocksDoctypeXxeInjection() {
        String xxePayload = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <PidData><Resp errCode="0" errInfo="&xxe;"/></PidData>
                """;
        ApiException ex = assertThrows(ApiException.class, () -> RdPidXmlParser.parse(xxePayload));
        assertEquals("INVALID_PID_XML", ex.getCode());
    }
}
