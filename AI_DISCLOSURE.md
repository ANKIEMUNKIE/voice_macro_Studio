# AI Disclosure

## Runtime behavior

Voice Macro Studio does **not** call a generative-AI model or paid AI API at runtime. It does not require an API key, billing account, cloud database, or target-app SDK.

The app uses Android platform speech recognition and text-to-speech services. Learned-flow matching, quantity parsing, selector scoring, sensitive-screen classification, replay decisions, and UI postcondition checks are deterministic Kotlin code. Learned flows are stored locally in the app's private Room database.

## Development assistance

GitHub Copilot and OpenAI Codex were used during development for code suggestions, debugging support, test and documentation drafting, repository packaging, and presentation creation.

The team reviewed the generated material, ran the reported tests, performed the documented Android 16 device/emulator checks, and accepts responsibility for the submitted source, claims, limitations, and demonstration. No user credential, OTP, password, or payment value was intentionally provided to an AI tool or stored in the project documentation.

## Evidence boundary

The submission reports only the scenarios documented in the README and project memory. It does not claim general reliability across every Android app or layout. Zomato is the validated target for the quantity demo; Swiggy replay remains unsupported on the tested device because its accessibility tree was unreadable, so the app blocks automation.
