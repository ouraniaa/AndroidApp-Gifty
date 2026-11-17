package com.example.androidapp.greeting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.example.androidapp.greeting.GreetingCardRequest.Language;
import com.example.androidapp.greeting.GreetingCardRequest.Occasion;
import com.example.androidapp.greeting.GreetingCardRequest.Relationship;
import com.example.androidapp.greeting.GreetingCardRequest.Tone;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public class MockGreetingCardGeneratorTest {

    private final MockGreetingCardGenerator generator = new MockGreetingCardGenerator();

    @Test
    public void resultIsAlwaysMarkedAsMock() {
        GreetingCardResult result = generator.compose(new GreetingCardRequest(
                Occasion.BIRTHDAY, Relationship.FRIEND, Tone.WARM, Language.ENGLISH, null));

        assertTrue(result.isMock());
        assertEquals(GreetingCardResult.SOURCE_MOCK, result.getSource());
    }

    @Test
    public void englishMessageUsesNameOccasionAndTone() {
        String message = generator.compose(new GreetingCardRequest(
                Occasion.GRADUATION, Relationship.FRIEND, Tone.FORMAL, Language.ENGLISH, "  Maria ")).getMessage();

        assertTrue(message.startsWith("Dear Maria,"));
        assertTrue(message.contains("Congratulations on your graduation!"));
        assertTrue(message.endsWith("Kind regards,"));
    }

    @Test
    public void greekMessageIsWrittenInGreek() {
        String message = generator.compose(new GreetingCardRequest(
                Occasion.NAME_DAY, Relationship.MUM, Tone.WARM, Language.GREEK, null)).getMessage();

        assertTrue(message.startsWith("Μανούλα μου,"));
        assertTrue(message.contains("Χρόνια πολλά για τη γιορτή σου!"));
        assertTrue(message.endsWith("Με αγάπη,"));
    }

    @Test
    public void everyCombinationProducesACompleteMessage() {
        for (Occasion occasion : Occasion.values()) {
            for (Relationship relationship : Relationship.values()) {
                for (Tone tone : Tone.values()) {
                    for (Language language : Language.values()) {
                        for (String name : new String[]{null, "Alex"}) {
                            String message = generator.compose(new GreetingCardRequest(
                                    occasion, relationship, tone, language, name)).getMessage();

                            assertFalse(message.contains("null"));
                            assertEquals("Expected greeting, body and sign-off: " + message,
                                    3, message.split("\n\n").length);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void generateDeliversResultThroughCallback() {
        AtomicReference<GreetingCardResult> received = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();

        generator.generate(new GreetingCardRequest(
                Occasion.CHRISTMAS, Relationship.DAD, Tone.FUNNY, Language.ENGLISH, null),
                new GreetingCardGenerator.Callback() {
                    @Override public void onSuccess(GreetingCardResult result) { received.set(result); }
                    @Override public void onError(String userMessage) { error.set(userMessage); }
                });

        assertNotNull(received.get());
        assertNull(error.get());
        assertTrue(received.get().getMessage().contains("Merry Christmas!"));
    }

    @Test
    public void factoryUsesMockOnlyInExplicitMockMode() {
        GreetingCardGenerator backend = (request, callback) -> { };

        assertTrue(GreetingCardGeneratorFactory.select(true, () -> backend) instanceof MockGreetingCardGenerator);
        assertSame(backend, GreetingCardGeneratorFactory.select(false, () -> backend));
    }

    @Test
    public void defaultBuildDoesNotUseMockMode() {
        assertFalse(GreetingCardGeneratorFactory.isMockMode());
    }

    @Test
    public void recipientNameRuleMatchesBackend() {
        assertTrue(GreetingCardRequest.isAcceptableRecipientName(null));
        assertTrue(GreetingCardRequest.isAcceptableRecipientName("   "));
        assertTrue(GreetingCardRequest.isAcceptableRecipientName("Γιώργος"));
        assertTrue(GreetingCardRequest.isAcceptableRecipientName("Anna-Maria O'Brien"));

        assertFalse(GreetingCardRequest.isAcceptableRecipientName("R2D2"));
        assertFalse(GreetingCardRequest.isAcceptableRecipientName("Anna{system}"));
        assertFalse(GreetingCardRequest.isAcceptableRecipientName("-Anna"));
        assertFalse(GreetingCardRequest.isAcceptableRecipientName("Anna\tMaria"));
        assertFalse(GreetingCardRequest.isAcceptableRecipientName(
                new String(new char[41]).replace('\0', 'a')));
    }

    @Test
    public void recipientNameIsNormalized() {
        assertNull(GreetingCardRequest.normalizeName(null));
        assertNull(GreetingCardRequest.normalizeName("   "));
        assertEquals("Anna Maria", GreetingCardRequest.normalizeName("  Anna   Maria "));

        String longName = new String(new char[60]).replace('\0', 'x');
        assertEquals(GreetingCardRequest.MAX_NAME_LENGTH,
                GreetingCardRequest.normalizeName(longName).length());
    }

    @Test
    public void optionsAreResolvedFromScreenLabels() {
        assertEquals(Occasion.VALENTINES_DAY,
                GreetingCardRequest.fromLabel(Occasion.class, "Valentine's Day", Occasion.BIRTHDAY));
        assertEquals(Relationship.MUM,
                GreetingCardRequest.fromLabel(Relationship.class, " mum ", Relationship.FRIEND));
        assertEquals(Occasion.BIRTHDAY,
                GreetingCardRequest.fromLabel(Occasion.class, "Unknown", Occasion.BIRTHDAY));
        assertEquals(Occasion.values().length, GreetingCardRequest.labelsOf(Occasion.class).length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void requestRequiresAllSelections() {
        new GreetingCardRequest(null, Relationship.FRIEND, Tone.WARM, Language.ENGLISH, null);
    }
}
