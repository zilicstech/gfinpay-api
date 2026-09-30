package com.fintech.recon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.fintech.recon.zet.ZetFdMisParser;
import com.fintech.recon.zet.ZetMisWorkbookFixtures;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** Handler wiring test without Mockito (parser + stub matcher/applier). */
class ZetFdReconFlowTest {

    @Test
    void handlerUsesParserNotMassActivate() throws IOException {
        ZetFdMisParser parser = new ZetFdMisParser();
        RecordingMatcher matcher = new RecordingMatcher();
        RecordingApplier applier = new RecordingApplier();
        ZetFdReconHandler handler = new ZetFdReconHandler(parser, matcher, applier);

        MockMultipartFile file = new MockMultipartFile("file", "zet-mini.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ZetMisWorkbookFixtures.minimalWorkbook());
        FdReconOutcome outcome = handler.reconcile(file);

        assertEquals(4, outcome.eligible());
        assertEquals(4, outcome.matched());
        assertEquals(0, outcome.activated());
        assertEquals("ZET_EXCEL", outcome.parser());
        assertEquals(4, outcome.rows().size());
        assertEquals(0, outcome.unidentified());
    }

    static final class RecordingMatcher extends FdMisMatcher {
        RecordingMatcher() {
            super(null);
        }

        @Override
        public Optional<Map<String, Object>> matchLead(FdMisRow row) {
            return Optional.of(Map.of("id", UUID.randomUUID(), "state", "IN_PROGRESS"));
        }

        @Override
        public List<FdReconMismatch> missingAtPartner(List<FdMisRow> parsedRows) {
            return new ArrayList<>();
        }
    }

    static final class RecordingApplier extends FdLeadReconApplier {
        RecordingApplier() {
            super(null, null);
        }

        @Override
        public ApplyResult apply(Map<String, Object> lead, FdMisRow row) {
            return new ApplyResult(true, false, true);
        }
    }
}
