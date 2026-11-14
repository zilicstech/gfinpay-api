package com.fintech.biometric;

import com.fintech.platform.web.ApiException;
import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * XXE-hardened parser for UIDAI L1 RD Service {@code <PidData>} XML envelopes
 * (compatible with Mantra MFS110/MFS100, Morpho/IDEMIA MSO1300E3, Startek FM220U, Precision PB510, SecuGen, Tatvik).
 */
public final class RdPidXmlParser {

    public record ParsedPidData(
            String errCode,
            String errInfo,
            String fCount,
            String fType,
            String iCount,
            String iType,
            String pCount,
            String pType,
            String nmPoints,
            String qScore,
            String dpId,
            String rdsId,
            String rdsVer,
            String dc,
            String mi,
            String mc,
            String srno,
            String sysid,
            String sessionId,
            String ts,
            String ci,
            String sessionKey,
            String hmac,
            String pidDataType,
            String pidData
    ) {
        /**
         * Builds the exact 26 mandatory string parameters required by Emerald's
         * {@code POST /core/v1/outlets/biometric-kyc} specification.
         */
        public Map<String, String> toEmeraldBiometricData(String aadhaar12) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("encryptedAadhaar", aadhaar12);
            m.put("dc", dc);
            m.put("ci", ci);
            m.put("hmac", hmac);
            m.put("dpId", dpId);
            m.put("mc", mc);
            m.put("pidDataType", pidDataType);
            m.put("rdsId", rdsId);
            m.put("rdsVer", rdsVer);
            m.put("sessionKey", sessionKey);
            m.put("mi", mi);
            m.put("errCode", errCode);
            m.put("errInfo", errInfo);
            m.put("fCount", fCount);
            m.put("fType", fType);
            m.put("iCount", iCount);
            m.put("iType", iType);
            m.put("pCount", pCount);
            m.put("pType", pType);
            m.put("srno", srno);
            m.put("sysid", sysid);
            m.put("sessionId", sessionId);
            m.put("ts", ts);
            m.put("pidData", pidData);
            m.put("qScore", qScore);
            m.put("nmPoints", nmPoints);
            return m;
        }
    }

    private RdPidXmlParser() {}

    public static ParsedPidData parse(String rawXml) {
        if (rawXml == null || rawXml.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EMPTY_PID_XML",
                    "No fingerprint capture data was received from the biometric scanner.");
        }
        Document doc = parseSecureXml(rawXml.trim());
        Element root = doc.getDocumentElement();
        if (root == null || !"PidData".equalsIgnoreCase(localTagName(root))) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PID_XML",
                    "Biometric scanner returned an unrecognized XML envelope.");
        }

        Element resp = firstChildElement(root, "Resp");
        if (resp == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PID_XML",
                    "Biometric response element (<Resp>) is missing from scanner payload.");
        }

        String errCode = nonBlank(resp.getAttribute("errCode"), "999");
        String errInfo = nonBlank(resp.getAttribute("errInfo"), "Success.");
        if (!"0".equals(errCode)) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "BIOMETRIC_SCANNER_ERROR",
                    "Biometric sensor could not capture fingerprint (" + errInfo + "). Please clean the sensor and retry.");
        }

        Element deviceInfo = firstChildElement(root, "DeviceInfo");
        Element skey = firstChildElement(root, "Skey");
        Element hmacEl = firstChildElement(root, "Hmac");
        Element dataEl = firstChildElement(root, "Data");

        if (deviceInfo == null || skey == null || hmacEl == null || dataEl == null) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INCOMPLETE_PID_XML",
                    "Biometric capture is incomplete. Please scan your finger again.");
        }

        Map<String, String> additionalParams = extractAdditionalParams(deviceInfo);

        String dc = nonBlank(deviceInfo.getAttribute("dc"), "");
        String dpId = nonBlank(deviceInfo.getAttribute("dpId"), "");
        String rdsId = nonBlank(deviceInfo.getAttribute("rdsId"), "");
        String rdsVer = nonBlank(deviceInfo.getAttribute("rdsVer"), "1.0.0");
        String mi = nonBlank(deviceInfo.getAttribute("mi"), "L1_DEVICE");
        String mc = nonBlank(deviceInfo.getAttribute("mc"), "");

        String ci = nonBlank(skey.getAttribute("ci"), "");
        String sessionKey = textContent(skey);
        String hmac = textContent(hmacEl);
        String pidDataType = nonBlank(dataEl.getAttribute("type"), "X");
        String pidData = textContent(dataEl);

        if (dc.isBlank() || dpId.isBlank() || rdsId.isBlank() || mc.isBlank()
                || ci.isBlank() || sessionKey.isBlank() || hmac.isBlank() || pidData.isBlank()) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INCOMPLETE_PID_XML",
                    "Encrypted PID block or device certificate was empty. Please reconnect your biometric device and retry.");
        }

        String srno = firstNonBlank(
                additionalParams.get("srno"),
                additionalParams.get("device_srno"),
                additionalParams.get("serial_number"),
                dc);
        String sysid = firstNonBlank(
                additionalParams.get("sysid"),
                additionalParams.get("sys_id"),
                srno);
        String sessionId = firstNonBlank(
                additionalParams.get("sessionid"),
                additionalParams.get("sysid"),
                sysid);
        String ts = firstNonBlank(
                additionalParams.get("ts"),
                deviceInfo.getAttribute("ts"),
                OffsetDateTime.now(ZoneOffset.ofHoursMinutes(5, 30)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        String fCount = nonBlank(resp.getAttribute("fCount"), "1");
        String fType = nonBlank(resp.getAttribute("fType"), "2");
        String iCount = nonBlank(resp.getAttribute("iCount"), "0");
        String iType = nonBlank(resp.getAttribute("iType"), "0");
        String pCount = nonBlank(resp.getAttribute("pCount"), "0");
        String pType = nonBlank(resp.getAttribute("pType"), "0");
        String qScore = nonBlank(resp.getAttribute("qScore"), "80");
        String nmPoints = nonBlank(resp.getAttribute("nmPoints"), "40");

        return new ParsedPidData(
                errCode,
                errInfo,
                fCount,
                fType,
                iCount,
                iType,
                pCount,
                pType,
                nmPoints,
                qScore,
                dpId,
                rdsId,
                rdsVer,
                dc,
                mi,
                mc,
                srno,
                sysid,
                sessionId,
                ts,
                ci,
                sessionKey,
                hmac,
                pidDataType,
                pidData);
    }

    private static Document parseSecureXml(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PID_XML",
                    "Could not parse biometric XML data from scanner.");
        }
    }

    private static Element firstChildElement(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0 && nodes.item(0) instanceof Element el) {
            return el;
        }
        // Case-insensitive fallback across RD vendors
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && tagName.equalsIgnoreCase(localTagName(el))) {
                return el;
            }
        }
        return null;
    }

    private static Map<String, String> extractAdditionalParams(Element deviceInfo) {
        Map<String, String> params = new LinkedHashMap<>();
        NodeList list = deviceInfo.getElementsByTagName("Param");
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element p) {
                String name = p.getAttribute("name");
                String value = p.getAttribute("value");
                if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                    params.put(name.trim().toLowerCase(), value.trim());
                }
            }
        }
        return params;
    }

    private static String localTagName(Element el) {
        String name = el.getTagName();
        int idx = name.indexOf(':');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    private static String textContent(Element el) {
        String raw = el.getTextContent();
        return raw == null ? "" : raw.replaceAll("\\s+", "");
    }

    private static String nonBlank(String val, String fallback) {
        return (val == null || val.isBlank()) ? fallback : val.trim();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }
}

