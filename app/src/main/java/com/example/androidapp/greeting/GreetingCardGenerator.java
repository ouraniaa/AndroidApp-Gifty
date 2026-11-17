package com.example.androidapp.greeting;

//Anything that can turn a GreetingCardRequest into a card message.
//
//The API is asynchronous because BackendGreetingCardGenerator calls the Gifty backend over
//the network; MockGreetingCardGenerator (explicit mock mode only) implements the same contract.
//
//Contract for implementations:
// - Callbacks must be delivered on the main (UI) thread.
// - Exactly one of onSuccess / onError is called per generate() call.
// - Never call an AI provider (Gemini, Groq, ...) directly from the app and never
//   ship a provider API key in the APK. Talk to the Gifty backend instead, which
//   holds the key and forwards the request.
public interface GreetingCardGenerator {

    interface Callback {
        void onSuccess(GreetingCardResult result);

        void onError(String userMessage);
    }

    void generate(GreetingCardRequest request, Callback callback);
}
