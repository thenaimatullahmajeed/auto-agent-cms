# Auto Agent CMS — build notes (prepared 2026-10-13)

Personal Android automation agent. User's plan: WhatsApp-instructed agent app that
reads WhatsApp messages, routes understanding/execution through Gemini + Groq LLM
pools with key failover, and operates other apps via AccessibilityService +
NotificationListener, reporting back on WhatsApp. Full prompt lives in main chat
(2026-10-13, user message with "PROMPT FOR CHATGPT"). Code generation is being
done by ChatGPT in the user's paid session (browser, logged in 2026-10-13 ~14:35 PT).
User order: "youtube ko choro, focus on just our app auto agent cms" — Ink Epoch /
unamii work is DEPRIORITIZED until he says otherwise. APK must be delivered
however possible; he installs/tests on his own phone (no on-device testing here).

## Build environment (verified 2026-10-13 ~14:40 PT)
- JDK: ~/jdk (Temurin 17.0.11) — set JAVA_HOME=~/jdk
- SDK: ~/android-sdk — build-tools 34.0.0, platforms/android-34, platform-tools,
  cmdline-tools/latest, licenses accepted
- Gradle: ~/workspace/gradle-dists/gradle-8.7/bin/gradle (downloading 2026-10-13;
  verify it finished before building)
- /tmp is a 512MB tmpfs — NEVER stage builds/downloads there. Use ~/workspace.
  Set GRADLE_USER_HOME=~/workspace/.gradle for builds.
- sdkmanager is unusable here (Java proxy tunneling broken); install SDK packages
  by direct curl from dl.google.com only.
- OAuth egress via raw exec is denied (403 policy_denied) — irrelevant here, but
  do not attempt Google token refresh from plain exec.

## Build recipe (once ChatGPT code lands in this dir)
1. Drop ChatGPT's files here preserving its project tree.
2. If no gradle wrapper: init with local gradle:
   `~/workspace/gradle-dists/gradle-8.7/bin/gradle wrapper --gradle-version 8.7`
   or build directly with the local gradle binary.
3. Build: `GRADLE_USER_HOME=~/workspace/.gradle JAVA_HOME=~/jdk \
   ANDROID_SDK_ROOT=~/android-sdk ./gradlew assembleDebug --no-daemon`
   (ALWAYS pass --no-daemon: the Gradle daemon's localhost socket fails in this
   sandbox — "Could not receive a message from the daemon", verified 2026-10-13.
   --no-daemon builds work.)
4. Expect compile errors on first pass (5k–10k LOC from an LLM) — fix iteratively.
   Output APK: app/build/outputs/apk/debug/app-debug.apk (debug-signed, sideload-ready).

## Known risks already flagged to user (main chat 2026-10-13)
- WhatsApp unofficial automation = ban risk; use a SECONDARY number, human-like pacing.
- Reliability won't be 100% on complex multi-app flows; each UI step = an LLM call (minutes per task).
- Aggressive OEM battery killers (Tecno/Infinix/Xiaomi/Samsung) — battery exemption + sticky service needed.
- Sideload only; Play Store won't accept this permission set.

## Env smoke test (in progress 2026-10-13 ~14:45 PT)
Minimal hello-world project at ~/workspace/apk-smoke-test/ running `assembleDebug`
in background (exec session proc_01087d7ca6ea; also stray sleep sessions
proc_aec7032b5f8d, proc_a91f495d832d — harmless). Verify with:
`ls ~/workspace/apk-smoke-test/app/build/outputs/apk/debug/*.apk`
If the APK exists, the full toolchain (JDK+SDK+Gradle+Maven) is proven. If the
build failed, read the session output / rerun the same gradle command in
~/workspace/apk-smoke-test with --stacktrace and fix the env before ChatGPT code arrives.

## Pending handoff
- ChatGPT: architecture critique → full code generation (in progress in browser).
- Then: save code here, compile-fix loop, deliver APK to user.
- User tests on his phone; crash logs/screenshots come back here for fixes.
