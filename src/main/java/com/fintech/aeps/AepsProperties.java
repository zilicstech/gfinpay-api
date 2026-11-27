package com.fintech.aeps;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Environment-backed operational and compliance settings for AePS ({@code app.aeps.*}).
 */
@Component
@ConfigurationProperties(prefix = "app.aeps")
public class AepsProperties {

    private boolean geofenceEnabled = true;
    private double maxGeofenceKm = 3.0;
    private int maxBiometricMismatches = 2;
    private int mismatchBlockHours = 24;
    private boolean cashDepositEnabled = false;

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

    public int maxBiometricMismatches() {
        return Math.max(1, maxBiometricMismatches);
    }

    public int getMaxBiometricMismatches() {
        return maxBiometricMismatches;
    }

    public void setMaxBiometricMismatches(int maxBiometricMismatches) {
        this.maxBiometricMismatches = maxBiometricMismatches;
    }

    public int mismatchBlockHours() {
        return Math.max(1, mismatchBlockHours);
    }

    public int getMismatchBlockHours() {
        return mismatchBlockHours;
    }

    public void setMismatchBlockHours(int mismatchBlockHours) {
        this.mismatchBlockHours = mismatchBlockHours;
    }

    public boolean cashDepositEnabled() {
        return cashDepositEnabled;
    }

    public boolean isCashDepositEnabled() {
        return cashDepositEnabled;
    }

    public void setCashDepositEnabled(boolean cashDepositEnabled) {
        this.cashDepositEnabled = cashDepositEnabled;
    }
}
