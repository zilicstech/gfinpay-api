package com.fintech.recon.zet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.fintech.recon.FdMisRow;
import com.fintech.recon.FdPartnerLifecycle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ZetFdMisParserTest {

    private final ZetFdMisParser parser = new ZetFdMisParser();

    @BeforeAll
    static void writeFixture() throws IOException {
        Path resource = Path.of("src/test/resources/recon/zet-mini.xlsx");
        ZetMisWorkbookFixtures.writeResourceFixture(resource);
    }

    @Test
    void parsesSbmPaymentRetryAsFdPaid() throws IOException {
        MockMultipartFile file = file();
        List<FdMisRow> rows = parser.parse(file);
        FdMisRow pay = byPhone(rows, "9876500001", "SBM");
        assertEquals("FD_PAID", pay.partnerStatus());
        assertEquals(FdPartnerLifecycle.IN_PROGRESS, pay.lifecycle());
        assertEquals("2026-01-03 11:00:00", pay.raw().get("fd_payment_success_time"));
    }

    @Test
    void parsesVirtualActivation() throws IOException {
        FdMisRow act = byPhone(parser.parse(file()), "9876500002", "SBM");
        assertEquals("ACTIVATED", act.partnerStatus());
        assertEquals(FdPartnerLifecycle.ACTIVATED, act.lifecycle());
    }

    @Test
    void physicalActivationWinsOverVirtual() throws IOException {
        FdMisRow phy = byPhone(parser.parse(file()), "9876500003", "SBM");
        assertEquals("PHYSICAL_CARD_ACTIVATED", phy.partnerStatus());
        assertEquals(FdPartnerLifecycle.ACTIVATED, phy.lifecycle());
    }

    @Test
    void parsesIobPanVerified() throws IOException {
        FdMisRow iob = byPhone(parser.parse(file()), "9876500004", "IOB");
        assertEquals("PAN_VERIFIED", iob.partnerStatus());
        assertEquals("IOB", iob.productKey());
    }

    private static FdMisRow byPhone(List<FdMisRow> rows, String phone, String sheet) {
        Map<String, FdMisRow> map = rows.stream()
                .filter(r -> r.sheetName().equals(sheet))
                .collect(Collectors.toMap(FdMisRow::phone, r -> r, (a, b) -> b));
        return map.get(phone);
    }

    private MockMultipartFile file() throws IOException {
        return new MockMultipartFile("file", "zet-mini.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ZetMisWorkbookFixtures.minimalWorkbook());
    }
}
