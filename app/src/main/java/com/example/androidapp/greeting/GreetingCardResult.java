package com.example.androidapp.greeting;

//A generated greeting card message plus where it came from.
//The screen uses isMock() to decide whether to show the "sample template, not AI" label,
//so a future backend result is never confused with a mock one (or the other way round).
public final class GreetingCardResult {

    public static final String SOURCE_MOCK = "mock-template";

    private final String message;
    private final boolean mock;
    private final String source;

    public GreetingCardResult(String message, boolean mock, String source) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Message must not be empty");
        }
        this.message = message;
        this.mock = mock;
        this.source = source;
    }

    public static GreetingCardResult mock(String message) {
        return new GreetingCardResult(message, true, SOURCE_MOCK);
    }

    public String getMessage() { return message; }
    public boolean isMock() { return mock; }
    public String getSource() { return source; }
}
