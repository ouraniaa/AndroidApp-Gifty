package com.example.androidapp.greeting;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.example.androidapp.BuildConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

//Single place that decides which GreetingCardGenerator the app uses.
//
//Default: BackendGreetingCardGenerator, which calls the Gifty Cloudflare Worker (Gemini).
//Mock templates are used ONLY when the build is made in explicit mock mode:
//    ./gradlew assembleDebug -PgreetingMockMode=true
//There is no automatic fallback to the mock when the backend fails.
public final class GreetingCardGeneratorFactory {

    //One shared background thread is enough: the screen allows a single request at a time
    private static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gifty-greeting-card");
        thread.setDaemon(true);
        return thread;
    });

    private GreetingCardGeneratorFactory() { }

    public static GreetingCardGenerator create(Context context) {
        Context appContext = context.getApplicationContext();
        return select(isMockMode(), () -> new BackendGreetingCardGenerator(
                BuildConfig.GIFTY_AI_BASE_URL,
                InstallIdStore.get(appContext),
                new UrlConnectionTransport(),
                BACKGROUND,
                ContextCompat.getMainExecutor(appContext)));
    }

    public static boolean isMockMode() {
        return BuildConfig.GREETING_CARD_MOCK_MODE;
    }

    static GreetingCardGenerator select(boolean mockMode, Supplier<GreetingCardGenerator> backend) {
        return mockMode ? new MockGreetingCardGenerator() : backend.get();
    }
}
