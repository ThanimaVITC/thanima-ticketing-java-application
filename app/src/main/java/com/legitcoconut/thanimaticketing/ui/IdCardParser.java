package com.legitcoconut.thanimaticketing.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads a name and registration number out of raw OCR text off a college ID card, and holds
 * the shared name / reg no validation both unpaid screens use before ever calling the API.
 * The rules mirror the server's normalizeName and normalizeRegNo exactly.
 */
public final class IdCardParser {

    private static final Pattern REG_NO = Pattern.compile("^\\d{2}[A-Z]{3,4}\\d{4}$");

    /** Lines that talk about the college or the card itself, never a person's name. */
    private static final Pattern NOT_A_NAME = Pattern.compile(
            "\\b(VIT|VELLORE|INSTITUTE|TECHNOLOGY|IDENTITY|CARD|STUDENT|NAME|UNIVERSITY|"
                    + "VALID|BLOOD|GROUP|DOB|PHONE|EMAIL)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NAME_LABEL =
            Pattern.compile("^\\s*name\\s*[:\\-]?\\s*", Pattern.CASE_INSENSITIVE);

    private IdCardParser() {
    }

    // ------------------------------------------------------------------ shared validation

    /** Collapses whitespace runs to a single space and trims. Mirrors the server's normalizeName. */
    public static String normalizeName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    /** Null when the name is valid, else the message to show as the field error. */
    public static String nameError(String raw) {
        return normalizeName(raw).length() < 2 ? "Name must be at least 2 characters" : null;
    }

    /** Strips space, full stop, underscore, slash and hyphen, then uppercases. Mirrors normalizeRegNo. */
    public static String normalizeRegNo(String raw) {
        return raw == null ? "" : raw.replaceAll("[ ._/-]", "").toUpperCase(Locale.US);
    }

    /** Null when the reg no is valid, else the message to show as the field error. */
    public static String regNoError(String raw) {
        return REG_NO.matcher(normalizeRegNo(raw)).matches()
                ? null : "Registration number must look like 21BCE1234";
    }

    // ------------------------------------------------------------------ OCR parsing

    public static final class Result {
        public final String name;
        public final String regNo;

        public Result(String name, String regNo) {
            this.name = name;
            this.regNo = regNo;
        }
    }

    public static Result parse(String rawText) {
        List<String> lines = new ArrayList<>();
        if (rawText != null) {
            for (String line : rawText.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) lines.add(trimmed);
            }
        }

        int regNoLine = -1;
        String regNo = null;
        outer:
        for (int i = 0; i < lines.size(); i++) {
            for (String token : lines.get(i).split("\\s+")) {
                String candidate = token.replaceAll("\\s+", "").toUpperCase(Locale.US);
                if (candidate.isEmpty()) continue;
                if (REG_NO.matcher(candidate).matches()) {
                    regNo = candidate;
                    regNoLine = i;
                    break outer;
                }
                String coerced = coerce(candidate);
                if (coerced != null && REG_NO.matcher(coerced).matches()) {
                    regNo = coerced;
                    regNoLine = i;
                    break outer;
                }
            }
        }

        String name = null;
        if (regNoLine >= 0) {
            for (int i = regNoLine - 1; i >= 0 && name == null; i--) {
                if (isPlausibleName(lines.get(i))) name = tidyName(lines.get(i));
            }
            for (int i = regNoLine + 1; i < lines.size() && name == null; i++) {
                if (isPlausibleName(lines.get(i))) name = tidyName(lines.get(i));
            }
        }

        return new Result(name, regNo);
    }

    /** Repairs the classic OCR letter/digit mix ups, only at the positions the format demands. */
    private static String coerce(String token) {
        int len = token.length();
        int letterLen = len - 6; // 2 leading digits + 4 trailing digits either side of the letters
        if (letterLen != 3 && letterLen != 4) return null;
        char[] c = token.toCharArray();
        for (int i = 0; i < 2; i++) c[i] = asDigit(c[i]);
        for (int i = 2; i < 2 + letterLen; i++) c[i] = asLetter(c[i]);
        for (int i = 2 + letterLen; i < len; i++) c[i] = asDigit(c[i]);
        return new String(c);
    }

    private static char asDigit(char c) {
        switch (c) {
            case 'O': return '0';
            case 'I':
            case 'L': return '1';
            case 'S': return '5';
            case 'B': return '8';
            default: return c;
        }
    }

    private static char asLetter(char c) {
        switch (c) {
            case '0': return 'O';
            case '1': return 'I';
            case '5': return 'S';
            case '8': return 'B';
            default: return c;
        }
    }

    private static boolean isPlausibleName(String line) {
        if (line.length() < 3) return false;
        if (line.matches(".*\\d.*")) return false;
        return !NOT_A_NAME.matcher(line).find();
    }

    private static String tidyName(String line) {
        String collapsed = line.trim().replaceAll("\\s+", " ");
        String stripped = NAME_LABEL.matcher(collapsed).replaceFirst("").trim();
        StringBuilder out = new StringBuilder();
        for (String word : stripped.split(" ")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) out.append(word.substring(1).toLowerCase(Locale.US));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ self check

    /** Runs a few hard coded cards through {@link #parse}. Debug builds only, see UnpaidScanFragment. */
    public static void selfCheck() {
        check("VIT VELLORE\nSTUDENT IDENTITY CARD\nKavita Sharma\n21BCE1234\nValid Upto: 2027",
                "Kavita Sharma", "21BCE1234", "clean card");

        check("VIT VELLORE\nSTUDENT ID CARD\nRahul Kumar\n2IBCEI234",
                "Rahul Kumar", "21BCE1234", "reg no needs coercion");

        check("Priya Nair\nVIT VELLORE\n21BCE5678",
                "Priya Nair", "21BCE5678", "name has to come from further up");
    }

    private static void check(String raw, String expectName, String expectRegNo, String label) {
        Result r = parse(raw);
        if (!expectName.equals(r.name) || !expectRegNo.equals(r.regNo)) {
            throw new IllegalStateException("IdCardParser.selfCheck failed on " + label
                    + ": got name=" + r.name + " regNo=" + r.regNo);
        }
    }
}
