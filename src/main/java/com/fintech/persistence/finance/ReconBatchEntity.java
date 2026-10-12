package com.fintech.persistence.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "recon_batches")
public class ReconBatchEntity {

    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "text")
    private String provider;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "mis_file_uri", nullable = false, columnDefinition = "text")
    private String misFileUri;

    @Column(name = "total_rows")
    private Integer totalRows;

    @Column(name = "matched_rows")
    private Integer matchedRows;

    @Column(name = "unidentified_rows", nullable = false)
    private int unidentifiedRows;

    @Column(nullable = false, columnDefinition = "text")
    private String status;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
}
