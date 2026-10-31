<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Coding standards

Read at review. Each rule names what the code or the doc does; the reviewer checks every changed
line against them.

## Code

- **Constants are named.** A C++ constant lives in a header, a Kotlin string in its `Strings.kt`
  file, halves and doubles go through `:geometry` (`halves.hpp` in the engine), times through
  `TimeUnit`, settings spacing through the one scale in `Common.kt`. An expression holds names,
  not literal numbers. A constant mirrored across the bridge stays only where a test reads both
  sides.
- **One rule, one place.** A rule lives whole in C++ or whole in Kotlin, and the other side calls
  it. Two copies of one rule are one too many, inside a language as well as across the bridge.
- **Comments say what the code does,** in one or two plain sentences. The reasons, the history and
  the measurements belong in the commit message.
- **Text lives in the `:i18n` catalogues,** keys through `tools/gen_keys.py`.
- **Typing-side state goes through the orchestrator** (`FieldPolicy`, `FieldSession`, the flow
  hooks).
- **Borrowed rules are named in our own terms;** the reference keyboard's names stay out of the
  code.
- **The hot path allocates nothing, and prediction is deterministic** (`CONTRIBUTING.md`).
- **A test reads the real packs** for a dictionary claim, and declares any file it reads by path in
  `unitTests.all {}`.

## Docs

- **A doc says what the code does, at the meta level,** with the rule in place of a literal
  example, and in English throughout. A passage that is edited is brought to this rule.
- **"The swipe model" is named by its script:** "the Latin model".

## Settings

- **Interacting settings share one card.** A gated switch is greyed, never hidden, with no note,
  and rows a sub-switch gates sit in its card under it. A short fixed list of values gets the node
  slider with a default and a reset.
- **When a fix makes the code do what a setting's text says,** the text stays.
