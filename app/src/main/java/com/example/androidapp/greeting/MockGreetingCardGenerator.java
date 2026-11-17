package com.example.androidapp.greeting;

import com.example.androidapp.greeting.GreetingCardRequest.Language;
import com.example.androidapp.greeting.GreetingCardRequest.Occasion;
import com.example.androidapp.greeting.GreetingCardRequest.Relationship;
import com.example.androidapp.greeting.GreetingCardRequest.Tone;

//Offline sample-template generator, used ONLY in an explicit mock-mode build
//(-PgreetingMockMode=true). It is NOT AI: it assembles a message from fixed templates
//(greeting + occasion wish + tone sentence + sign-off). Every result is marked as mock.
public class MockGreetingCardGenerator implements GreetingCardGenerator {

    //Runs synchronously on the caller's thread. The screen calls it from the main thread,
    //which satisfies the interface's main-thread callback contract.
    @Override
    public void generate(GreetingCardRequest request, Callback callback) {
        try {
            callback.onSuccess(compose(request));
        } catch (RuntimeException e) {
            callback.onError("Could not create a sample message. Please try again.");
        }
    }

    //Build the full sample message for a request
    public GreetingCardResult compose(GreetingCardRequest request) {
        boolean greek = request.getLanguage() == Language.GREEK;

        String message = salutation(request, greek)
                + "\n\n"
                + occasionWish(request.getOccasion(), greek)
                + " "
                + toneSentence(request.getTone(), greek)
                + "\n\n"
                + signOff(request.getTone(), greek);

        return GreetingCardResult.mock(message);
    }

    //Opening line, based on who the card is for and the optional name.
    //Greek lines are written to avoid assuming the recipient's gender.
    static String salutation(GreetingCardRequest request, boolean greek) {
        String name = request.getRecipientName();
        boolean hasName = request.hasRecipientName();
        Relationship relationship = request.getRelationship();

        if (greek) {
            switch (relationship) {
                case MUM: return "Μανούλα μου,";
                case DAD: return "Μπαμπά μου,";
                case BOYFRIEND:
                case GIRLFRIEND: return hasName ? name + ", αγάπη μου," : "Αγάπη μου,";
                case SIBLING: return hasName ? "Γεια σου " + name + "!" : "Γεια σου, αδερφάκι μου!";
                case COLLEAGUE: return hasName ? "Γεια σου " + name + "," : "Γεια σου,";
                case FRIEND:
                default: return hasName ? "Γεια σου " + name + "!" : "Γεια σου, φιλαράκι!";
            }
        }

        switch (relationship) {
            case MUM: return "Dear Mum,";
            case DAD: return "Dear Dad,";
            case BOYFRIEND:
            case GIRLFRIEND: return hasName ? "My dear " + name + "," : "My love,";
            case SIBLING: return hasName ? "Dear " + name + "," : "To my favourite sibling,";
            case COLLEAGUE: return hasName ? "Dear " + name + "," : "Dear colleague,";
            case FRIEND:
            default: return hasName ? "Dear " + name + "," : "Dear friend,";
        }
    }

    static String occasionWish(Occasion occasion, boolean greek) {
        switch (occasion) {
            case NAME_DAY: return greek ? "Χρόνια πολλά για τη γιορτή σου!" : "Happy name day!";
            case CHRISTMAS: return greek ? "Καλά Χριστούγεννα!" : "Merry Christmas!";
            case NEW_YEAR: return greek ? "Καλή χρονιά!" : "Happy New Year!";
            case ANNIVERSARY: return greek ? "Χρόνια πολλά για την επέτειο!" : "Happy anniversary!";
            case GRADUATION: return greek ? "Συγχαρητήρια για την αποφοίτησή σου!" : "Congratulations on your graduation!";
            case WEDDING: return greek ? "Να ζήσετε! Συγχαρητήρια για τον γάμο σας!" : "Congratulations on your wedding!";
            case VALENTINES_DAY: return greek ? "Καλό Άγιο Βαλεντίνο!" : "Happy Valentine's Day!";
            case THANK_YOU: return greek ? "Σε ευχαριστώ πολύ!" : "Thank you so much!";
            case BIRTHDAY:
            default: return greek ? "Χρόνια πολλά για τα γενέθλιά σου!" : "Happy birthday!";
        }
    }

    static String toneSentence(Tone tone, boolean greek) {
        switch (tone) {
            case FUNNY: return greek
                    ? "Να έχεις άφθονα σνακ, λίγες έγνοιες και Wi-Fi που δεν πέφτει ποτέ!"
                    : "May your snacks be plenty, your worries few, and your Wi-Fi always strong!";
            case FORMAL: return greek
                    ? "Θερμές ευχές για υγεία, ευτυχία και κάθε επιτυχία."
                    : "Please accept my warmest wishes for health, happiness and continued success.";
            case ROMANTIC: return greek
                    ? "Κάθε στιγμή μαζί σου είναι δώρο και σε ευχαριστώ που είσαι στη ζωή μου."
                    : "Every moment with you is a gift, and I'm grateful for you every single day.";
            case WARM:
            default: return greek
                    ? "Σου εύχομαι μια μέρα γεμάτη χαρά, αγάπη και ό,τι σε κάνει να χαμογελάς."
                    : "Wishing you a day full of joy, love and everything that makes you smile.";
        }
    }

    static String signOff(Tone tone, boolean greek) {
        switch (tone) {
            case FUNNY: return greek ? "Με πολλά χαμόγελα," : "Cheers,";
            case FORMAL: return greek ? "Με εκτίμηση," : "Kind regards,";
            case ROMANTIC: return greek ? "Σε αγαπώ," : "Forever yours,";
            case WARM:
            default: return greek ? "Με αγάπη," : "With love,";
        }
    }
}
