# Gifty

An Android application developed with Java and Material Design that simplifies gift-giving through gift recommendations and an integrated event reminder system.

Gifty was developed as a collaborative team project for the **Mobile Application Development** course at the School of Informatics, Aristotle University of Thessaloniki. The **AI Greeting Card Generator** described below was added later as an individual extension of the original team project.

## Features

### Original team project

1. **Gift Recommendations:** filters a predefined catalogue of gift ideas by the recipient's age, relationship, interests and budget. Recommendations come from seeded data and SQL filters, **not AI**.
2. **Event and Reminder Timeline:** in-app calendar to track upcoming events, with swipe-to-delete and visual timeline cues.
3. **Wishlist:** save, manage and remove favourite gift recommendations.
4. **Authentication and Guest Mode:** sign up and log in for permanent data storage, or browse as a guest for instant ideas.
5. **Modern UI:** Material Design Components and Fragment-based navigation.

### Individual extension: AI Greeting Card Generator

A standalone screen, opened from the Home screen, that writes a personalised greeting card message with Google Gemini.

- **Inputs:** occasion, recipient relationship, tone, language, and an optional recipient name.
  - Occasions: Birthday, Name Day, Christmas, New Year, Anniversary, Graduation, Wedding, Valentine's Day, Thank You.
  - Relationships: Friend, Mum, Dad, Boyfriend, Girlfriend, Sibling, Colleague.
  - Tones: Warm, Funny, Formal, Romantic.
- **Languages:** Greek and English.
- **Results** are labelled "Written by Gemini AI" and can be copied with one tap.
- **Mock mode:** an offline sample-template mode exists for development only. Its messages are always labelled as templates, and the app never falls back to them when the backend fails.

## Architecture

```
Android app  ──HTTPS──▶  Cloudflare Worker (gifty-ai-api)  ──HTTPS──▶  Google Gemini API
   (Java)                     (JavaScript)                            (gemini-3.5-flash-lite)
```

- **Android app:** sends only the selected options (`POST /generate-greeting`). It contains **no API keys**.
- **Cloudflare Worker** (`backend/gifty-ai-api`):
  - validates the input strictly and builds the prompt on the server
  - applies rate limits
  - calls Gemini and returns `{ message, source, model, language, mock }`
- **Gemini API key:** stored only as a Cloudflare Secret.

## Tech Stack

| Area | Technologies |
|---|---|
| Android | Java, Android SDK (minSdk 24 / Android 7.0, targetSdk 36), XML layouts |
| UI | Material Design Components, RecyclerView, Fragments |
| Local storage | SQLite (SQLiteOpenHelper), SharedPreferences |
| AI backend | JavaScript, Cloudflare Workers, Workers Rate Limiting |
| AI model | Google Gemini API (`gemini-3.5-flash-lite`) |
| Tooling | Gradle, Wrangler, Node.js test runner, JUnit |

## Setup

### Android app

1. Clone the repository and open it in Android Studio.
2. Let Gradle sync, then run the app on an emulator or device (Android 7.0+).
3. The backend URL is set by `GIFTY_AI_BASE_URL` in `app/build.gradle`. Change it if you deploy your own Worker.
4. Optional, offline development without the backend:

   ```bash
   ./gradlew assembleDebug -PgreetingMockMode=true
   ```

### Backend (Cloudflare Worker)

Requires Node.js 20+ and a Cloudflare account. The Free plan is sufficient.

```bash
cd backend/gifty-ai-api
npm install
npx wrangler login
npx wrangler secret put GEMINI_API_KEY
npx wrangler deploy
```

- The Gemini API key **must** be configured as a Cloudflare Secret named `GEMINI_API_KEY`. `wrangler secret put` prompts for it, or you can add it in the Cloudflare Dashboard.
- Never put the key in `wrangler.toml`, the Android code, or any committed file.
- For local development only, copy `.dev.vars.example` to `.dev.vars`. That file is git-ignored.

See [`backend/gifty-ai-api/README.md`](backend/gifty-ai-api/README.md) for the full API contract, error codes and abuse protection.

## Testing

```bash
./gradlew testDebugUnitTest
```

```bash
cd backend/gifty-ai-api && npm test
```

- **Android unit tests** cover request serialization, response parsing, error handling and threading, using a fake HTTP transport.
- **Backend tests** cover validation, prompt building, Gemini response handling, rate limits and error mapping, using a mocked Gemini API.
- No automated test calls the real Gemini API.

## Known Limitations

- Gift recommendations are rule-based on a small predefined catalogue. They are not personalised by AI.
- The backend does not yet verify that requests come from the genuine app (for example with Firebase App Check or Play Integrity). Abuse is limited by strict validation and per-install, per-IP and global rate limits, which are approximate.
- On the Gemini Free Tier, Google may use request content (including the optional recipient name) to improve its products.
- AI output length and wording can vary slightly, so users should review messages before sending.
- Rotating the screen while a message is being generated discards that request.

## Credits

- **Original application:** developed by the Gifty student team for the Mobile Application Development course, School of Informatics, Aristotle University of Thessaloniki.
  Original team repository: https://github.com/mpouzoup/AndroidApp-Gifty
- **AI Greeting Card Generator** (Android integration and Cloudflare Worker backend): later individual extension.
