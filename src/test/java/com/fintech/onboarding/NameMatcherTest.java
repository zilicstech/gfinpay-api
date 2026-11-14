package com.fintech.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NameMatcherTest {

    @Test
    void exactAndCaseInsensitiveMatch() {
        assertEquals(100, NameMatcher.score("Rajesh Kumar Sharma", "rajesh kumar sharma"));
        assertTrue(NameMatcher.matches("Rajesh Kumar Sharma", "RAJESH KUMAR SHARMA"));
    }

    @Test
    void stripsTitlesAndHonorifics() {
        assertEquals(100, NameMatcher.score("Mr. Rajesh Sharma", "Shri Rajesh Sharma"));
        assertTrue(NameMatcher.matches("Mrs. Priya Verma", "Smt Priya Verma"));
        assertTrue(NameMatcher.matches("M/s. Sharma Enterprises", "SHARMA ENTERPRISES"));
    }

    @Test
    void matchesInitialAndMiddleNameVariations() {
        assertTrue(NameMatcher.matches("R K Sharma", "Rajesh Kumar Sharma"));
        assertTrue(NameMatcher.matches("Rajesh Sharma", "Rajesh Kumar Sharma"));
        assertTrue(NameMatcher.matches("Sharma Rajesh Kumar", "Rajesh Kumar Sharma"));
        assertTrue(NameMatcher.matches("A YADAV", "Amit Yadav"));
        assertTrue(NameMatcher.matches("A YADAV", "Amit Kumar Yadav"));
        assertTrue(NameMatcher.matches("AK YADAV", "Amit Kumar Yadav"));
        assertTrue(NameMatcher.matches("A YADAV S/O R YADAV", "Amit Yadav"));
    }

    @Test
    void rejectsUnrelatedNames() {
        assertFalse(NameMatcher.matches("Rajesh Kumar Sharma", "Amitabh Bachchan"));
        assertFalse(NameMatcher.matches("A YADAV", "Ravi Sharma"));
        assertEquals(0, NameMatcher.score("", "Rajesh Sharma"));
        assertEquals(0, NameMatcher.score(null, "Rajesh Sharma"));
    }
}

