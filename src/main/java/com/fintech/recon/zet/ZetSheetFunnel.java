package com.fintech.recon.zet;

import java.util.List;

record ZetSheetFunnel(String sheetName, String productKey, List<ZetFunnelStage> stages) {}
