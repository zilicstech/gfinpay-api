package com.fintech.dmt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Environment-backed defaults for Domestic Money Transfer ({@code app.dmt.*}),
 * including fallback wallet surcharge (used until Admin saves a custom DMT surcharge
 * in Settings -> Service Rails -> DMT -> Set commission), RBI BC limits, 3 km shop
 * geo-fencing, and webhook-free {@code /reports/txnStatus} reconfirmation windows.
 */
@Component
@ConfigurationProperties(prefix = "app.dmt")
public class DmtProperties {

    private BigDecimal minAmount = new BigDecimal("100.00");
    private BigDecimal maxPerTxn = new BigDecimal("5000.00");
    private String defaultSurchargeMode = "PERCENT";
    private BigDecimal defaultSurchargeValue = new BigDecimal("1.00");
    private BigDecimal defaultSurchargeMin = new BigDecimal("10.00");
    private boolean geofenceEnabled = true;
    private double maxGeofenceKm = 3.0;
    private int statusInitialDelayMinutes = 30;
    private int statusPollIntervalMinutes = 240;
    private boolean reconEnabled = true;
    private long reconSweepMs = 300_000L;

    public BigDecimal minAmount() {
        return (minAmount == null ? new BigDecimal("100.00") : minAmount).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public void setMinAmount(BigDecimal minAmount) {
        this.minAmount = minAmount;
    }

    public BigDecimal maxPerTxn() {
        return (maxPerTxn == null ? new BigDecimal("5000.00") : maxPerTxn).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getMaxPerTxn() {
        return maxPerTxn;
    }

    public void setMaxPerTxn(BigDecimal maxPerTxn) {
        this.maxPerTxn = maxPerTxn;
    }

    public String defaultSurchargeMode() {
        String m = defaultSurchargeMode == null ? "" : defaultSurchargeMode.trim().toUpperCase(Locale.ROOT);
        return "FLAT".equals(m) ? "FLAT" : "PERCENT";
    }

    public String getDefaultSurchargeMode() {
        return defaultSurchargeMode;
    }

    public void setDefaultSurchargeMode(String defaultSurchargeMode) {
        this.defaultSurchargeMode = defaultSurchargeMode;
    }

    public BigDecimal defaultSurchargeValue() {
        return (defaultSurchargeValue == null ? new BigDecimal("1.00") : defaultSurchargeValue.max(BigDecimal.ZERO))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getDefaultSurchargeValue() {
        return defaultSurchargeValue;
    }

    public void setDefaultSurchargeValue(BigDecimal defaultSurchargeValue) {
        this.defaultSurchargeValue = defaultSurchargeValue;
    }

    public BigDecimal defaultSurchargeMin() {
        return (defaultSurchargeMin == null ? new BigDecimal("10.00") : defaultSurchargeMin.max(BigDecimal.ZERO))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getDefaultSurchargeMin() {
        return defaultSurchargeMin;
    }

    public void setDefaultSurchargeMin(BigDecimal defaultSurchargeMin) {
        this.defaultSurchargeMin = defaultSurchargeMin;
    }

    public boolean geofenceEnabled() {
        return geofenceEnabled;
    }

    public boolean isGeofenceEnabled() {
        return geofenceEnabled;
    }

    public void setGeofenceEnabled(boolean geofenceEnabled) {
        this.geofenceEnabled = geofenceEnabled;
    }

    public double maxGeofenceKm() {
        return maxGeofenceKm <= 0 ? 3.0 : maxGeofenceKm;
    }

    public double getMaxGeofenceKm() {
        return maxGeofenceKm;
    }

    public void setMaxGeofenceKm(double maxGeofenceKm) {
        this.maxGeofenceKm = maxGeofenceKm;
    }

    public int statusInitialDelayMinutes() {
        return Math.max(0, statusInitialDelayMinutes);
    }

    public int getStatusInitialDelayMinutes() {
        return statusInitialDelayMinutes;
    }

    public void setStatusInitialDelayMinutes(int statusInitialDelayMinutes) {
        this.statusInitialDelayMinutes = statusInitialDelayMinutes;
    }

    public int statusPollIntervalMinutes() {
        return Math.max(1, statusPollIntervalMinutes);
    }

    public int getStatusPollIntervalMinutes() {
        return statusPollIntervalMinutes;
    }

    public void setStatusPollIntervalMinutes(int statusPollIntervalMinutes) {
        this.statusPollIntervalMinutes = statusPollIntervalMinutes;
    }

    public boolean reconEnabled() {
        return reconEnabled;
    }

    public boolean isReconEnabled() {
        return reconEnabled;
    }

    public void setReconEnabled(boolean reconEnabled) {
        this.reconEnabled = reconEnabled;
    }

    public long reconSweepMs() {
        return Math.max(10_000L, reconSweepMs);
    }

    public long getReconSweepMs() {
        return reconSweepMs;
    }

    public void setReconSweepMs(long reconSweepMs) {
        this.reconSweepMs = reconSweepMs;
    }
}
