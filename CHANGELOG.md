<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Changelog

What each release changed for the person typing, newest first. The release workflow takes a
version's section from this file as the notes of the GitHub release it publishes, and appends
the verification command and the compare link itself. Work on the way to the next release is
listed under *Unreleased* and moves under its version when it is tagged. The APKs, their
attestations and the full commit lists are on the
[releases page](https://github.com/razvan-eduard/borderkeys/releases).

## Unreleased

- The language packs carry their word triples as an index hung off the pairs, format
  version 6: smaller packs, the same predictions.
- No space of the keyboard's own in an e-mail or web-address field.
- The emoji panel searches the keywords Unicode gives each emoji, in the languages switched on.
- "Why?" on a held suggestion, in every build, answered in a panel in plain words.
- A terminal is typed into as a terminal: in Termux, ConnectBot, JuiceSSH, Termius and any
  field that declares no text class, every key goes straight through.
- The Personal dictionary screen lists the word pairs and triples the keyboard learned, each
  with a Forget of its own.
- A search box on the settings home screen finds any screen, card or row by its title.
- A tile in the quick settings, lit while BorderKeys is the keyboard in use, that opens the
  keyboard picker until it is and the settings once it is.
- A date-and-time quick action, in a pattern of your own.
- A slide on the space bar moves the caret by line up or down as well as sideways, and with
  shift held it selects instead.
- The clipboard history's search takes wildcards and regular expressions.
- Copies made in password managers and one-time-code apps the keyboard knows, or in apps you
  name, are never kept in the history.
- The smoke suite runs at API 30 and API 35, with six more cases.
- A user guide, docs/guide.md, with the store screenshots; the README keeps the promises and
  how to verify them.
- An opacity slider on the Theme screen, and a vibration switch each for the keys, for picks
  on the strip and in the panels, and for the swipe ring.
- A "More languages" card on the Languages screen, pointing at the packs the project publishes
  beyond the six inside the app, and the pipeline that builds them from a manifest.
- Thirteen more layouts: Colemak, Colemak-DH, Workman, Bépo, Turkish F and Q, and the Spanish,
  Portuguese, Nordic, Danish and Norwegian, German, Czech and Hungarian variants with their own
  letter keys.
- Greek, Cyrillic, Armenian and Georgian words fold by case, and Greek by tonos and final
  sigma, so a pack in those scripts is reached however a word was capitalised.
- Eight layouts in scripts of their own: Russian, Ukrainian, Bulgarian, Serbian, Macedonian,
  Greek, Armenian and Georgian, each with the letters its rows have no room for on the long
  press.
- Fixed: a word the personal dictionary held capitalised and a pack held in lower case was
  offered twice on the strip, and so was a contraction the corpus wrote with both kinds of
  apostrophe. Each is one suggestion now: the engine keeps one candidate per spelling
  whatever its case, the typographic apostrophes fold onto the plain one in the engine and
  in the word lists, whose rows for the two spellings are added up, and the strip never
  shows a word twice whatever the engine sends.

## v0.8.0 — 2026-09-25

- The symbols page as a number pad beside the symbols, larger strip words.
- A modifier row, searching panels, a feature tour, debug stats, faster swipe decode.
- An effects module, and the events that can use it.
- A folded pack key carries every spelling, and a word you typed is kept.
- A word must be typeable in the language that claims it.
- The ring offers the word it decoded, and says when it keeps it.
- The neural decoder is what plus swipes with; its scoring fitted, 81.2% to 90.6%; the
  geometric decoder's channel weights fitted, 55.4% to 60.2%, and it reaches a letter the path
  passes near, not only the nearest key.
- A preferred language, for where detection starts; autocorrect asks its own question, and
  the language verdict arrives.
- The person-name lists come back with the labels Wikidata moved, learn about companies,
  countries and islands, and the proper nouns the packs were already carrying are flagged.
- The personal dictionary forgets, and its search is where the list is.
- Each pack's foreign vocabulary and its own misspellings taken out; the Romanian corpus's
  mis-decoded entries reach their target; one Romanian word, one spelling.
- Fixed: a single letter is never corrected into an accented one; an edit and a completion
  may combine, once; a letter you typed is worth more than one you did not; the dictionary's
  spelling of what you typed wins; a typed mark is a decision; a word you are mid-way through
  is completed, not corrected; the outlined word is the one the space bar commits; the weight
  file states the architecture it was exported for; settings chips state their whole rule and
  the size scale goes one way.

## v0.7.1 — 2026-09-19

- Fixed: the numpad's zero keeps its column when an optional key is gone; the space you type
  is only swallowed where the keyboard really put one; reuse lint passes again.

## v0.7.0 — 2026-09-19

- Quick actions: Capital and Normalise; bookmark-tab buttons outlined by the key-outline
  toggle, with optional labels under them.
- A key popup on press, text shortcuts, three vibration strengths, a system-default vibration.
- A switch that keeps offensive words out of suggestions, corrections and learning; a switch
  for the space after a picked suggestion; the capital a name gets is a switch.
- Personal names in the shipped dictionaries capitalise on their own; first names capitalise
  on their own; autocorrect never trades an ordinary word for a name.
- The swipe model loads when its switch is on and is freed when it is off; swipe works
  wherever suggestions do.
- Particles split into per-region outline and fill layers, backup exports follow; colours
  picked through the wheel are kept; the theme's background can leave the navigation bar bare.
- Symbols-with-numpad puts enter, backspace and period next to the digits; rich text in the
  draft box.
- Fixed: crashes, silent failures and broken promises from a full audit; the settings home
  keeps its place while a setting is open; the capital after a full stop no longer depends on
  the editor having caught up; a tap that landed where the ring's preview ended left it open;
  switching to a page with fewer keys could crash mid fade-out.

## v0.6.2 — 2026-09-14

- Haptic feedback on suggestion strip taps.
- Fixed: the top row's long-press alternatives popup was hidden by the finger.

## v0.6.1 — 2026-09-13

- Fixed: a tap in the editor resolves the radial ring, not just on the keyboard; the plus
  listing's shared text is verbatim identical to the core listing's.
