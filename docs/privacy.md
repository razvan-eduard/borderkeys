<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# What the keyboard can and cannot know

A keyboard sees passwords, messages and medical forms. This is what BorderKeys does with that,
what enforces it, and — the part usually left out — what the protections do **not** cover.

- [No permissions, and what enforces it](#no-permissions-and-what-enforces-it)
- [Private fields](#private-fields)
- [What the personal dictionary holds](#what-the-personal-dictionary-holds)
- [Encryption at rest, and its limits](#encryption-at-rest-and-its-limits)
- [The clipboard](#the-clipboard)
- [The assistant](#the-assistant)
- [Threat model](#threat-model)

---

## No permissions, and what enforces it

The shipped APKs request **zero** permissions. Not "only harmless ones" — the output of
`aapt2 dump permissions` is empty.

That claim survives because three separate things assert it, at three different stages:

### 1. The manifest removes rather than omits

`app/src/main/AndroidManifest.xml` does not merely leave `INTERNET` out. It removes it:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

If any library ever merges `INTERNET` in, the merger deletes it and the resulting APK cannot open
a socket.

The same treatment is given to `androidx.core`'s own signature-level
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. It grants nothing to anyone outside our signature,
but it is still a line in `aapt2 dump permissions`, and *"this app requests no permissions at
all"* has to be true as printed, not true with a footnote.

That removal has a cost, and the manifest states it: nothing may register a dynamic
`BroadcastReceiver` through `ContextCompat` with `RECEIVER_NOT_EXPORTED`, because on API 30–32
that call throws the moment the permission is missing — it did, and it took the input method down
in `onCreate`. The one dynamic receiver there is (the wallpaper listener) goes through the
platform's own `registerReceiver` instead.

### 2. A build gate on the sources

`:app:verifyNoInternetPermission` fails the build if a dependency asks for network access — and
it fails **even though the merger would have removed it**, because a library that asked for
network access contains code that expected to have it, and silently disarming that code is not a
fix. Alongside it: `:app:verifyNoForbiddenDependencies` and `:keyboard:verifyKeyboardHasNoCompose`.

### 3. A CI gate on the artefact

The Gradle tasks check the *sources* of the APK. CI checks the *APK*. These are not the same
claim — a merged manifest with no `INTERNET` and a packaged binary with no `INTERNET` are
separated by resource shrinking, R8 and the packager. So CI runs `aapt2 dump permissions` over
both shipped flavours and fails if a single line comes back.

CI additionally greps the `core` dex for assistant type descriptors, and checks that **neither**
flavour bundles a model file.

### What needs no permission

Haptics go through `View.performHapticFeedback(KEYBOARD_TAP)`, which needs no `VIBRATE`. Nothing
in the design needs `QUERY_ALL_PACKAGES`, storage access, or an accessibility service.

---

## Private fields

`PrivateMode` (`ime/PrivateMode.kt`) decides whether the field being typed into must be forgotten
entirely. In private mode there is **no learning, no clipboard history, no suggestion from the
personal dictionary and no text assistant**.

This is a security requirement rather than a preference, and the code is shaped to say so: it is
a **pure function of the `EditorInfo`** the framework hands over. No setting switches it off, and
the test suite can enumerate every input type the platform defines.

The strip has one thing to offer in such a field: a Show button that draws the field's text on
the strip until Hide is tapped or the field changes. The text is read from the editor through the
same connection every keystroke uses and goes nowhere else; it is not learned, not searched, not
kept.

### Two independent triggers

**A password field, in all four of its spellings:**

| Class | Variation |
|---|---|
| `TYPE_CLASS_TEXT` | `TYPE_TEXT_VARIATION_PASSWORD` |
| `TYPE_CLASS_TEXT` | `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` |
| `TYPE_CLASS_TEXT` | `TYPE_TEXT_VARIATION_WEB_PASSWORD` |
| `TYPE_CLASS_NUMBER` | `TYPE_NUMBER_VARIATION_PASSWORD` |

The variation bits are checked **against the right class each time**. This is the subtle part and
the reason the function is written the way it is: `TYPE_TEXT_VARIATION_PASSWORD` is `0x80` and
`TYPE_NUMBER_VARIATION_PASSWORD` is `0x10`, different values in different classes. Comparing a
variation without its class is exactly how a numeric PIN field ends up treated as ordinary text.

**`IME_FLAG_NO_PERSONALIZED_LEARNING`** — an application saying *"do not remember this"* about a
field that is not a password: a search box in an incognito tab, a medical form, a message in a
disappearing chat. Honouring it is the whole reason the flag exists.

### The careful default

```kotlin
if (info == null) return true   // Nothing is known about the field, so assume the careful answer.
```

An unknown field is private.

### Two checks, not one

Private mode is enforced twice, independently, which is the right number for a rule whose failure
mode is a password in the personal dictionary:

1. `BorderKeysService` refuses to call `LearningBuffer.record` at all;
2. `LearningBuffer.enabled` is `false` regardless, so a call that slipped through records nothing.

A third, separate switch covers the other direction. `Engine::setPersonalModelEnabled(false)`
stops the personal model being **consulted**: what this device learned from its owner must not be
offered back into a field that asked to be forgotten. The model stays loaded and untouched — it
is simply not asked.

---

## What the personal dictionary holds

Worth stating plainly, because "the keyboard learns from you" is the sentence people are right to
be suspicious of.

**It holds counts of words, pairs and triples. There is no model being fine-tuned and no gradient
anywhere.** Every word committed raises its count. A word chosen on purpose — tapped on the
strip, or put back after a correction — raises a second count. A word no dictionary holds is
offered, and left alone by autocorrect, only once it has been chosen or written three times. See
[`architecture.md`](architecture.md#the-learning-path).

What is never recorded:

- anything typed in a [private field](#private-fields);
- any word the user has refused (`LearningBuffer.setBlockedWords`);
- the offensive-word list, while that switch is on (`OffensiveWords`);
- word order beyond three (`kMaxTrigrams`), and only the 2,048 most-used of those.

Blocked entries are folded through `WordFold` before comparison, so `Shit` at a sentence start is
the same refusal as `shit`.

`PersonalWordDecay` (`:data`) is how the dictionary forgets without being told to, and the pair
and triple tables evict least-used entries when full — so a phrase typed once years ago does not
hold a slot against one typed daily.

Everything learned is visible and deletable in the personal-dictionary screen.

---

## Encryption at rest, and its limits

The Room database is SQLCipher. The passphrase chain:

```
32 bytes from SecureRandom
  └─ stored base64 in EncryptedSharedPreferences
      └─ encrypted with a key that never leaves the Android Keystore
```

**What that buys:** the database file is useless on its own — pulled off a backup, off a rooted
filesystem, out of an ADB extraction — because the key it needs is held by hardware on one device
and cannot be exported from it.

**What it does not buy, and the code says so rather than leaving it implied:** an attacker running
code *as this app*, on *this unlocked device*, can ask the Keystore to decrypt for them. Keystore
protects the key against **exfiltration**, not against **use**. Everything in the database is
protected against the file being copied elsewhere, which is the realistic threat for a keyboard.

`:app` and `:assist` open the database independently, in separate processes, so the
read-check-generate-write of the passphrase runs behind a `FileLock` on a marker file.

---

## The clipboard

Clipboard history is stored in the same encrypted database and is subject to the same private
mode rule: nothing is captured while a private field is focused. A clip the copying app marks
sensitive — a credential copied out of a password manager, on Android 13 and later — is neither
recorded nor offered on the strip, whatever field is focused.

The keyboard holds no clipboard listener that runs when it is not the active input method — it
has no permission that would let it, and on modern Android an IME cannot read the clipboard while
not focused anyway.

---

## The assistant

`:assist` is `plus`-only and is excluded from `core` by `plusImplementation` — a single line in
one build file, asserted against the dex in CI rather than trusted.

It runs `llama.cpp` **on device**. There is no network permission in either flavour, so there is
no remote inference and could not be. **Neither flavour bundles a model**, also checked in CI: the
`plus` build ships a runtime and nothing to run until the user supplies a model themselves.

The assistant is off in private fields, like everything else.

---

## Threat model

Stated as what is and is not defended, because a privacy page that only lists strengths is not
useful.

**Defended:**

| Threat | What stops it |
|---|---|
| The keyboard exfiltrating what you type | No `INTERNET` permission in the APK, verified on the artefact |
| A password entering the personal dictionary | `PrivateMode`, enforced twice, on a pure function of `EditorInfo` |
| An app asking not to be remembered and being remembered anyway | `IME_FLAG_NO_PERSONALIZED_LEARNING` honoured |
| The database being read after being copied off the device | SQLCipher + Keystore-held key |
| A copied password landing in the history or on the strip | The platform's sensitive-content flag honoured; private fields capture nothing |
| The free build quietly containing the assistant | dex grep in CI |
| A dependency introducing network access | Build gate on sources, CI gate on the artefact |

**Not defended, and not claimed to be:**

- **Code running as this app on an unlocked device.** It can use the Keystore key. See
  [above](#encryption-at-rest-and-its-limits).
- **The lock screen after a reboot.** The keyboard is not direct-boot aware: its database key
  lives in the Keystore and is unreadable before the first unlock, so an alphanumeric device
  password is typed on the system's own keyboard until then. Below Android 13 the platform has
  no sensitive-content flag, so a copied password is recorded there like any other clip.
- **A compromised or malicious Android build.** An IME is handed keystrokes by the platform; if
  the platform is hostile, nothing here helps.
- **Screen capture, or another app with accessibility access.** Outside this app entirely.
- **Physical access to an unlocked device.** The personal dictionary screen shows what was
  learned, by design — it has to, or it could not be reviewed and deleted.
- **Traffic analysis of the assistant's model file**, if a user supplies one from a source that
  watches who downloads it. That transfer is not ours.

If you find something in the first table that does not hold, that is a security issue — see
[`CONTRIBUTING.md`](../CONTRIBUTING.md).
