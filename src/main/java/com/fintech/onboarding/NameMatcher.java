package com.fintech.onboarding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Normalizes personal/entity names and computes a similarity score in [0, 100].
 */
public final class NameMatcher {

    public static final int DEFAULT_THRESHOLD = 80;

    private static final Set<String> TITLES = Set.of(
            "MR", "MRS", "MS", "MISS", "DR", "PROF", "SHRI", "SMT", "SMTI", "KUM", "KUMARI",
            "SRI", "LATE", "MESSRS", "SH", "PROP", "PROPRIETOR");

    private NameMatcher() {}

    public static int score(String left, String right) {
        List<String> a = tokens(left);
        List<String> b = tokens(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        String joinedA = String.join(" ", a);
        String joinedB = String.join(" ", b);
        if (joinedA.equals(joinedB)) {
            return 100;
        }

        double directJw = jaroWinkler(joinedA, joinedB) * 100.0;
        double tokenScore = bestTokenAlignment(a, b) * 100.0;
        int best = (int) Math.round(Math.max(directJw, tokenScore));
        return Math.max(0, Math.min(100, best));
    }

    public static boolean matches(String left, String right) {
        return score(left, right) >= DEFAULT_THRESHOLD;
    }

    public static String normalize(String raw) {
        return String.join(" ", tokens(raw));
    }

    private static List<String> tokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String cleaned = raw.toUpperCase(Locale.ROOT)
                .replaceAll("\\b(S\\s*/\\s*O|D\\s*/\\s*O|W\\s*/\\s*O|C\\s*/\\s*O)\\b.*$", " ")
                .replaceAll("M/S\\.?\\s*", " ")
                .replaceAll("[^A-Z0-9\\s]", " ")
                .trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        String[] parts = cleaned.split("\\s+");
        List<String> result = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isEmpty() && !TITLES.contains(p)) {
                result.add(p);
            }
        }
        if (result.isEmpty() && parts.length > 0) {
            result.addAll(Arrays.asList(parts));
        }
        return result;
    }

    private static double bestTokenAlignment(List<String> a, List<String> b) {
        List<String> shorter = a.size() <= b.size() ? a : b;
        List<String> longer = a.size() <= b.size() ? new ArrayList<>(b) : new ArrayList<>(a);

        boolean[] used = new boolean[longer.size()];
        double totalSim = 0.0;

        for (String sToken : shorter) {
            double bestSim = 0.0;
            int bestIdx = -1;
            for (int j = 0; j < longer.size(); j++) {
                if (used[j]) {
                    continue;
                }
                double sim = tokenSimilarity(sToken, longer.get(j));
                if (sim > bestSim) {
                    bestSim = sim;
                    bestIdx = j;
                }
            }
            if (bestIdx >= 0 && bestSim >= 0.80) {
                used[bestIdx] = true;
                totalSim += bestSim;
            } else if (sToken.length() >= 2 && sToken.length() <= 3 && matchesConcatenatedInitials(sToken, longer, used)) {
                totalSim += 0.92;
            } else if (bestIdx >= 0) {
                used[bestIdx] = true;
                totalSim += bestSim;
            }
        }

        double avgMatched = totalSim / shorter.size();
        int remainingUnmatched = 0;
        for (boolean u : used) {
            if (!u) remainingUnmatched++;
        }
        // Slight penalty for extra unmatched non-initial tokens, allowing middle name omissions
        double penalty = remainingUnmatched == 0 ? 1.0 : Math.max(0.82, 1.0 - (remainingUnmatched * 0.06));
        return avgMatched * penalty;
    }

    private static boolean matchesConcatenatedInitials(String initials, List<String> longer, boolean[] used) {
        int[] indices = new int[initials.length()];
        for (int c = 0; c < initials.length(); c++) {
            char ch = initials.charAt(c);
            int found = -1;
            for (int j = 0; j < longer.size(); j++) {
                if (used[j]) continue;
                boolean alreadyPicked = false;
                for (int k = 0; k < c; k++) {
                    if (indices[k] == j) {
                        alreadyPicked = true;
                        break;
                    }
                }
                if (!alreadyPicked && !longer.get(j).isEmpty() && longer.get(j).charAt(0) == ch) {
                    found = j;
                    break;
                }
            }
            if (found < 0) return false;
            indices[c] = found;
        }
        for (int idx : indices) {
            used[idx] = true;
        }
        return true;
    }

    private static double tokenSimilarity(String t1, String t2) {
        if (t1.equals(t2)) {
            return 1.0;
        }
        if (t1.length() == 1 && t2.startsWith(t1)) {
            return 0.92;
        }
        if (t2.length() == 1 && t1.startsWith(t2)) {
            return 0.92;
        }
        return jaroWinkler(t1, t2);
    }

    public static double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 || len2 == 0) {
            return 0.0;
        }
        int matchDistance = Math.max(len1, len2) / 2 - 1;
        if (matchDistance < 0) {
            matchDistance = 0;
        }
        boolean[] s1Matches = new boolean[len1];
        boolean[] s2Matches = new boolean[len2];

        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, len2);
            for (int j = start; j < end; j++) {
                if (s2Matches[j]) {
                    continue;
                }
                if (s1.charAt(i) != s2.charAt(j)) {
                    continue;
                }
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!s1Matches[i]) {
                continue;
            }
            while (!s2Matches[k]) {
                k++;
            }
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }
        double m = matches;
        double jaro = ((m / len1) + (m / len2) + ((m - transpositions / 2.0) / m)) / 3.0;
        int prefix = 0;
        int maxPrefix = Math.min(4, Math.min(len1, len2));
        for (int i = 0; i < maxPrefix; i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }
}
