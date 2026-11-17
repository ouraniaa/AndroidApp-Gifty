package com.example.androidapp.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.androidapp.R;
import com.example.androidapp.greeting.GreetingCardGenerator;
import com.example.androidapp.greeting.GreetingCardGeneratorFactory;
import com.example.androidapp.greeting.GreetingCardRequest;
import com.example.androidapp.greeting.GreetingCardRequest.Language;
import com.example.androidapp.greeting.GreetingCardRequest.Occasion;
import com.example.androidapp.greeting.GreetingCardRequest.Relationship;
import com.example.androidapp.greeting.GreetingCardRequest.Tone;
import com.example.androidapp.greeting.GreetingCardResult;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

//Standalone screen that creates a greeting card message from an occasion, relationship,
//tone and language. By default the message comes from the Gifty backend (Gemini); sample
//templates are only used in an explicit mock-mode build, and are always labeled as such.
public class GreetingCardActivity extends AppCompatActivity {

    private static final String STATE_MESSAGE = "GREETING_MESSAGE";
    private static final String STATE_IS_MOCK = "GREETING_IS_MOCK";
    private static final String STATE_SOURCE = "GREETING_SOURCE";

    private AutoCompleteTextView spOccasion, spCardRelationship;
    private TextInputLayout tilRecipientName;
    private TextInputEditText etRecipientName;
    private ChipGroup cgTone, cgLanguage;
    private MaterialButton btnGenerateCard, btnCopyMessage;
    private LinearProgressIndicator progressGreeting;
    private MaterialCardView cardResult;
    private TextView tvResultBadge, tvResultNote, tvCardMessage, tvGreetingError;

    private GreetingCardGenerator generator;
    private GreetingCardResult currentResult;

    //True while a request is pending; blocks duplicate submissions
    private boolean requestInFlight = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.activity.EdgeToEdge.enable(this);
        setContentView(R.layout.activity_greeting_card);

        //Keep content clear of the status and navigation bars
        View root = findViewById(R.id.greetingRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        spOccasion = findViewById(R.id.spOccasion);
        spCardRelationship = findViewById(R.id.spCardRelationship);
        tilRecipientName = findViewById(R.id.tilRecipientName);
        etRecipientName = findViewById(R.id.etRecipientName);
        cgTone = findViewById(R.id.cgTone);
        cgLanguage = findViewById(R.id.cgLanguage);
        btnGenerateCard = findViewById(R.id.btnGenerateCard);
        btnCopyMessage = findViewById(R.id.btnCopyMessage);
        progressGreeting = findViewById(R.id.progressGreeting);
        cardResult = findViewById(R.id.cardResult);
        tvResultBadge = findViewById(R.id.tvResultBadge);
        tvResultNote = findViewById(R.id.tvResultNote);
        tvCardMessage = findViewById(R.id.tvCardMessage);
        tvGreetingError = findViewById(R.id.tvGreetingError);

        generator = GreetingCardGeneratorFactory.create(this);

        //Tell the user up front where messages come from in this build
        TextView tvGreetingSubtitle = findViewById(R.id.tvGreetingSubtitle);
        tvGreetingSubtitle.setText(GreetingCardGeneratorFactory.isMockMode()
                ? "Test mode · sample templates, not AI"
                : "Written with Gemini AI");

        //Fill the dropdowns from the option enums and preselect the first entry
        setupDropdown(spOccasion, GreetingCardRequest.labelsOf(Occasion.class));
        setupDropdown(spCardRelationship, GreetingCardRequest.labelsOf(Relationship.class));

        ImageButton btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        btnGenerateCard.setOnClickListener(v -> generateMessage());
        btnCopyMessage.setOnClickListener(v -> copyMessage());

        //Restore a previously generated message after rotation
        if (savedInstanceState != null && savedInstanceState.getString(STATE_MESSAGE) != null) {
            showResult(new GreetingCardResult(
                    savedInstanceState.getString(STATE_MESSAGE),
                    savedInstanceState.getBoolean(STATE_IS_MOCK, true),
                    savedInstanceState.getString(STATE_SOURCE)));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentResult != null) {
            outState.putString(STATE_MESSAGE, currentResult.getMessage());
            outState.putBoolean(STATE_IS_MOCK, currentResult.isMock());
            outState.putString(STATE_SOURCE, currentResult.getSource());
        }
    }

    private void setupDropdown(AutoCompleteTextView view, String[] labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        view.setAdapter(adapter);
        if (labels.length > 0) {
            //false = do not filter the list down to the preselected value
            view.setText(labels[0], false);
        }
    }

    //Read the current selections, build a request and ask the generator for a message
    private void generateMessage() {
        if (requestInFlight) return;

        String name = etRecipientName.getText() != null ? etRecipientName.getText().toString() : null;
        if (!GreetingCardRequest.isAcceptableRecipientName(name)) {
            tilRecipientName.setError("Use letters only (spaces, - ' . allowed)");
            return;
        }
        tilRecipientName.setError(null);

        Occasion occasion = GreetingCardRequest.fromLabel(
                Occasion.class, spOccasion.getText().toString(), Occasion.BIRTHDAY);
        Relationship relationship = GreetingCardRequest.fromLabel(
                Relationship.class, spCardRelationship.getText().toString(), Relationship.FRIEND);

        GreetingCardRequest request = new GreetingCardRequest(
                occasion, relationship, selectedTone(), selectedLanguage(), name);

        setLoading(true);
        generator.generate(request, new GreetingCardGenerator.Callback() {
            @Override
            public void onSuccess(GreetingCardResult result) {
                if (isFinishing() || isDestroyed()) return;
                setLoading(false);
                showResult(result);
            }

            @Override
            public void onError(String userMessage) {
                if (isFinishing() || isDestroyed()) return;
                setLoading(false);
                showError(userMessage);
            }
        });
    }

    private Tone selectedTone() {
        int id = cgTone.getCheckedChipId();
        if (id == R.id.chipToneFunny) return Tone.FUNNY;
        if (id == R.id.chipToneFormal) return Tone.FORMAL;
        if (id == R.id.chipToneRomantic) return Tone.ROMANTIC;
        return Tone.WARM;
    }

    private Language selectedLanguage() {
        return cgLanguage.getCheckedChipId() == R.id.chipLangGreek ? Language.GREEK : Language.ENGLISH;
    }

    //Disable the button and show progress while a request is pending
    private void setLoading(boolean loading) {
        requestInFlight = loading;
        btnGenerateCard.setEnabled(!loading);
        btnGenerateCard.setText(loading ? "Writing your message…" : "Generate Message");
        progressGreeting.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) tvGreetingError.setVisibility(View.GONE);
    }

    private void showError(String userMessage) {
        tvGreetingError.setText(userMessage != null ? userMessage : "Something went wrong. Please try again.");
        tvGreetingError.setVisibility(View.VISIBLE);
    }

    private void showResult(GreetingCardResult result) {
        currentResult = result;
        tvCardMessage.setText(result.getMessage());
        tvGreetingError.setVisibility(View.GONE);

        //The label always reflects where the text really came from
        if (result.isMock()) {
            tvResultBadge.setText("SAMPLE TEMPLATE · NOT AI-GENERATED");
            tvResultBadge.setTextColor(Color.parseColor("#FBBF24"));
            tvResultBadge.setBackgroundColor(Color.parseColor("#3A2A10"));
            tvResultNote.setText("This message is built from fixed sample templates for testing. It was not written by AI.");
        } else {
            tvResultBadge.setText("WRITTEN BY GEMINI AI");
            tvResultBadge.setTextColor(Color.parseColor("#C4B5FD"));
            tvResultBadge.setBackgroundColor(Color.parseColor("#2A2140"));
            tvResultNote.setText("Created by Google's Gemini AI. Please read it before sending.");
        }

        cardResult.setVisibility(View.VISIBLE);
    }

    private void copyMessage() {
        if (currentResult == null) return;

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Clipboard is not available", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Greeting card message", currentResult.getMessage()));

        //Android 13+ shows its own confirmation when something is copied
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Message copied", Toast.LENGTH_SHORT).show();
        }
    }
}
