package com.example.androidapp.greeting;

import java.util.Locale;

//Immutable description of the greeting card the user wants.
//It is plain Java (no Android classes) so it can be unit tested and later serialized
//as a request body for the Gifty backend. The enum keys are stable identifiers meant
//for that backend; the labels are what the screen shows.
public final class GreetingCardRequest {

    public static final int MAX_NAME_LENGTH = 40;

    //Common contract for every selectable option
    public interface Option {
        String getKey();
        String getLabel();
    }

    public enum Occasion implements Option {
        BIRTHDAY("birthday", "Birthday"),
        NAME_DAY("name_day", "Name Day"),
        CHRISTMAS("christmas", "Christmas"),
        NEW_YEAR("new_year", "New Year"),
        ANNIVERSARY("anniversary", "Anniversary"),
        GRADUATION("graduation", "Graduation"),
        WEDDING("wedding", "Wedding"),
        VALENTINES_DAY("valentines_day", "Valentine's Day"),
        THANK_YOU("thank_you", "Thank You");

        private final String key;
        private final String label;

        Occasion(String key, String label) { this.key = key; this.label = label; }

        @Override public String getKey() { return key; }
        @Override public String getLabel() { return label; }
    }

    //The first five match the relationships offered by the Gift Finder screen
    public enum Relationship implements Option {
        FRIEND("friend", "Friend"),
        MUM("mum", "Mum"),
        DAD("dad", "Dad"),
        BOYFRIEND("boyfriend", "Boyfriend"),
        GIRLFRIEND("girlfriend", "Girlfriend"),
        SIBLING("sibling", "Sibling"),
        COLLEAGUE("colleague", "Colleague");

        private final String key;
        private final String label;

        Relationship(String key, String label) { this.key = key; this.label = label; }

        @Override public String getKey() { return key; }
        @Override public String getLabel() { return label; }
    }

    public enum Tone implements Option {
        WARM("warm", "Warm"),
        FUNNY("funny", "Funny"),
        FORMAL("formal", "Formal"),
        ROMANTIC("romantic", "Romantic");

        private final String key;
        private final String label;

        Tone(String key, String label) { this.key = key; this.label = label; }

        @Override public String getKey() { return key; }
        @Override public String getLabel() { return label; }
    }

    //Keys are ISO 639-1 language codes
    public enum Language implements Option {
        ENGLISH("en", "English"),
        GREEK("el", "Ελληνικά");

        private final String key;
        private final String label;

        Language(String key, String label) { this.key = key; this.label = label; }

        @Override public String getKey() { return key; }
        @Override public String getLabel() { return label; }
    }

    private final Occasion occasion;
    private final Relationship relationship;
    private final Tone tone;
    private final Language language;
    private final String recipientName;

    public GreetingCardRequest(Occasion occasion, Relationship relationship, Tone tone,
                               Language language, String recipientName) {
        if (occasion == null || relationship == null || tone == null || language == null) {
            throw new IllegalArgumentException("Occasion, relationship, tone and language are required");
        }
        this.occasion = occasion;
        this.relationship = relationship;
        this.tone = tone;
        this.language = language;
        this.recipientName = normalizeName(recipientName);
    }

    public Occasion getOccasion() { return occasion; }
    public Relationship getRelationship() { return relationship; }
    public Tone getTone() { return tone; }
    public Language getLanguage() { return language; }

    //Returns null when the user did not enter a name
    public String getRecipientName() { return recipientName; }

    public boolean hasRecipientName() { return recipientName != null; }

    //Trim the optional name, treat blank as missing and cap its length
    static String normalizeName(String name) {
        if (name == null) return null;
        String trimmed = name.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_NAME_LENGTH) {
            trimmed = trimmed.substring(0, MAX_NAME_LENGTH).trim();
        }
        return trimmed;
    }

    //Same rule the backend enforces: starts with a letter, then letters, combining marks,
    //spaces, - ' . only; no control characters; at most MAX_NAME_LENGTH characters.
    //Blank or missing names are acceptable (the name is optional).
    public static boolean isAcceptableRecipientName(String name) {
        if (name == null || name.trim().isEmpty()) return true;
        if (CONTROL_CHARS.matcher(name).find()) return false;
        String cleaned = name.trim().replaceAll("\\s+", " ");
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_NAME_LENGTH) return false;
        return NAME_PATTERN.matcher(cleaned).matches();
    }

    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("^\\p{L}[\\p{L}\\p{M} .'’-]*$");
    private static final java.util.regex.Pattern CONTROL_CHARS =
            java.util.regex.Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

    //Labels of every value of an option enum, in declaration order (used by dropdowns)
    public static <E extends Enum<E> & Option> String[] labelsOf(Class<E> type) {
        E[] values = type.getEnumConstants();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i].getLabel();
        }
        return labels;
    }

    //Find the option whose label matches the text shown on screen, or return the fallback
    public static <E extends Enum<E> & Option> E fromLabel(Class<E> type, String label, E fallback) {
        if (label == null) return fallback;
        String wanted = label.trim().toLowerCase(Locale.ROOT);
        for (E value : type.getEnumConstants()) {
            if (value.getLabel().toLowerCase(Locale.ROOT).equals(wanted)) {
                return value;
            }
        }
        return fallback;
    }
}
