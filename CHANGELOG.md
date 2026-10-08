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

## v0.11.0 — 2026-10-06

- A word typed with several letters on the wrong keys is read from where each tap landed, and
  autocorrect can now fix it; until now anything more than one or two letters off was left as
  typed. The word is applied only when it is a hundred times likelier than every other reading
  of the taps put together, the letters as typed included, and otherwise it is offered second
  on the strip; a correction that another word fits far better is now offered there instead of
  applied. Tried on a thousand real typing mistakes collected from people's phones, it now
  fixes about five more in every hundred, turns fewer of them into the wrong word, and leaves
  real words the dictionaries don't know alone as often as before. The strictest correction
  distance leaves such words alone.
- Rare words, among autocorrect's advanced settings: how many words beyond the dictionary's own
  list count as real words and stay as typed. Listed words only, the default, is how the
  keyboard behaved until now; each step up adds rarer words that the same corpora write and a
  spelling dictionary accepts, and leaves more typos that happen to spell one uncorrected. Such
  words are never suggested, and the setting names the dictionaries it changes.
- Apply the first suggestion when you press Enter: a switch of its own beside the space one, on
  the Typing and Suggestions screen and in Set up your keyboard, off by default like it. The
  space switch now says it covers punctuation too.
- Remember photos and Remember screenshots, two switches side by side on the Clipboard screen
  and in Set up your keyboard. Each keeps its kind in the clipboard history, encrypted, offers
  the newest on the strip, and deletes what it kept when turned off. A screenshot from the last
  five minutes sits beside the clipboard chip or in its place, whichever is newer, as chosen
  under its switch. Turning screenshots on asks once for your screenshots folder, in Android's
  own folder picker opened where this phone keeps them; the keyboard reads that folder alone and
  still declares no permission. Both off by default.
- Show a preview in the chip, on the Clipboard screen: the copied-photo and screenshot chips show
  a small picture of the image in front of their icon, within the strip's own height. Off by
  default.
- Particle effects is now Effects, and Animations, beside it on the settings home's Appearance
  card, is a page of its own: one switch stops every animation, and each has its own beneath it: the key press
  highlight, the photo paste animation, the moving colours around the draft box and on the plus
  build's title, the draft box's motion, and the short animations when a word is learned or
  corrected. All of them also stop when Android's animations are off.
- Photo paste animation, on the Animations page: a photo or screenshot pasted from the strip rises
  out of its chip in a lamp's shape, fading as it grows to fill the screen above the keys. The
  app underneath stays tappable throughout, and the animation is skipped when Android's
  animations are off. Off by default.
- A key's long-press strip opens straight away, with no preview of the key first, drawn in the
  held key's own colours. Sliding past either end fades it out over a short margin, where the
  end character stays chosen and lifting types it; further out it closes with nothing typed.
- The copied-photo and screenshot chips are offered only in a field that takes that kind of
  image, and never in a password or private field; both chips and both pastes follow the same
  rule. In the clipboard panel, a photo the field cannot take is shown faded and pastes nothing;
  pin and delete still work.
- Settings search finds every setting, including those titled above a picker or slider and the
  newest-screenshot option it used to miss, matches the words of each setting's explanation
  after its title, and finds a title through a typo ("screnshot", "vibraton"). The list it
  searches is built from the screens themselves every time the app is built. The results come in
  alphabetical order, and a tap opens the screen scrolled to that setting, which glows for a
  moment, with the Advanced fold it sits under already open.
- Set up your keyboard: after setup, a few pages offer the choices worth making first, each with
  what it does and, where it helps, a small preview: languages and layouts, autocorrect,
  capitals and spacing; swipe, the ring and names; learning, the heatmap, the clipboard and the
  newest screenshot; theme, height, number row, key popup, vibration, animations and the
  quick-action bar, over a live keyboard. The last page is the feature tour, now holding only
  what the pages before it do not set, with key flicks, extra keys, your own layouts, accent
  modifiers, the space-bar joystick, the backspace slide, Rare words, Keep privately and the
  particle effects added. Both open again from the About card.
- Fixed: a word picked from the strip got a space before a following comma or full stop.
- Fixed: ordinary words the name lists had flagged were capitalised in the middle of a sentence:
  "gates" came out as "Gates", "mark" as "Mark". Each list now drops the flag of every name its
  corpus writes mostly in lower case inside sentences, checked at every build: 539 English names
  and between 191 and 658 in each other bundled language.
- Fixed: Enter left the word it ended as typed: no correction, no restored apostrophe, no text
  shortcut, and an Enter that sends sent the word uncorrected. With its own switch on, Enter now
  applies what space would, before the new line or the send, and the backspace straight after
  takes it back.
- Fixed: reopening the keyboard with the caret at the end of a word left the strip unaware of
  that word until the caret moved or a key was typed.
- Swiping works on the Russian, Ukrainian, Bulgarian, Serbian, Macedonian, Greek, Armenian,
  Georgian, Hebrew and Arabic layouts, read by the geometric decoder.
- Fixed: autocorrect left a word of four to seven letters as typed when two of its letters
  were neighbouring keys hit by mistake, such as `joyse` for `house`; it now corrects it. Other
  two-letter differences in those words are still left alone, which keeps real words the
  dictionaries lack from being replaced.
- Fixed: on both Turkish layouts shift turned i into I; it now gives İ.
- Fixed: a swipe could not reach a word with a letter that sits only on another key's long
  press, such as ъ on the Russian layout's ь key; the swipe now reaches it through that key.
- Extra keys: the characters and accents your keyboard's languages use and the layout lacks
  (the diaeresis, ß and € for German; ñ for Spanish; ґ, є, і, ї for Ukrainian on the Russian
  layout) sit on a free flick of a nearby key, each with its switch on a new Extra keys screen.
- The globe key now shows by default once two or more layouts are enabled, so a fresh install
  can switch layouts from the keyboard; its switch still hides it.
- Icons that follow the text's direction (undo, redo, return, tab, line start and end) turn round
  in settings shown right to left and on the quick-actions bar of a right-to-left layout.
- Every tapped letter is read by where the tap landed: a tap near the edge between two keys
  makes the neighbouring letter a likelier correction, for autocorrect and the strip alike, in
  every build and with nothing stored. On synthetic taps, autocorrect's right word rose from
  71–85% to 89–93%.
- A heatmap, on what is now the Personal dictionary and heatmap screen: with Learning on, the
  keyboard learns where your own taps land on each key, for each orientation, placement and
  layout, and reads a key your way once it has thirty taps. The card draws it on the keyboard
  preview, a glow on each key; how much it counts, how many taps a key needs and how long taps
  are remembered are its advanced settings. Only a summary per key is kept, never the taps,
  nothing is learned in a private field, and it stays out of backups. On by default; switching
  it off asks, then forgets it.
- The learned words and the learned phrases each have a page of their own, opened from the
  Learned card: searchable, with Block and Delete on every word and Delete on every phrase, and,
  while a search filters the list, a button that deletes what it found, after asking. The lists
  are no longer cut at 200 words and a hundred pairs and triples.
- The personal dictionary moves only through Backup and transfer: its screen's own CSV export
  and import are gone.
- In the plus build, the home screen's title carries the assistant's moving colours.
- Words kept, on the Personal dictionary and heatmap screen: how many learned words the keyboard
  keeps, 20,000 by default, from 1,000 to 50,000. Past the limit, the words used least are
  forgotten with the phrases they are in; lowering it below the words already learned asks first.
- Remember detected language, on the Languages screen: the language you were writing carries
  into the next field and through a restart of the keyboard. On by default; off, every field
  starts undecided. With a Preferred dictionary every field starts from it instead.
- Once a few words have identified the language you are writing, the other dictionaries are
  still heard, but only for a word that matches what you typed more closely than anything in
  that language: a Romanian word typed in an English sentence gets its diacritics back, and an
  English slip after Romanian is corrected into English.
- Fixed: a word one slip away, "like" for "loke", was passed over for a longer word that ran on
  past the typed letters, which was then too far to apply, so nothing was corrected. Autocorrect
  now prices a letter past the last one typed as a letter left out.
- Fixed: a letter struck twice, "timmer" for "timer", was read as a different word rather than
  the one with the letter once.
- Fixed: a word with another language's ending, "orices", was taken for a form of a word in the
  first language and left uncorrected.
- Fixed: a word picked from the strip while the caret sat between two words run together,
  "loke|this", replaced both; it now replaces the first and leaves the second.
- Fixed: the clipboard chip's "Offer it only once" spent a clip in the app it was copied from,
  so it was gone before the keyboard opened anywhere else.
- Fixed: the key of an encrypted backup was derived with 210,000 rounds. A new file declares
  600,000, its header is sealed together with its contents, and a file claiming more than
  5,000,000 rounds is refused as damaged before any work is done. Files already written still
  open, and the screen says so while the key is being derived.
- Accents from other languages, under Accented characters: switch on any language whose accents
  reach a letter of your layout, and holding that letter offers them too, dictionary or not.
- Accent modifiers and a compose key for the modifier row, behind a switch of their own: twelve
  accents that go on the next letter, tapped twice or held to stay on every letter until tapped
  again, an arrow after one writing the bare mark; and a compose key after which a few keys spell
  a character from X11's full Compose list and Cyrillic and Arabic sequences besides, ' e for é,
  o c for ©, - - - for —. The pending key is drawn pressed, and the compose sequence shows on the
  strip as you type it.
- Your layouts, a new screen under Layout & keys: every layout, built in or your own, in the
  order you set, which is the order the globe key steps through them, each marked with the
  languages that draw it; write a layout of your own as the
  keyboard's own layout files are written, starting from any built-in one; the editor checks it as you type
  with the same rules the built-in ones must meet, shows it, and imports or exports a file. Any
  layout on the keyboard can then draw yours instead of its own, and another one in landscape.
  A layout may say what shift and control turn a letter into (a "modmap").
- Hold the space bar still for a moment and it becomes a joystick: lean the finger in a direction
  and the cursor keeps moving that way, faster the further you lean, at a speed you set. With it
  on, holding the space bar no longer switches the layout; the globe key does. Drag left along
  backspace to select the text behind the cursor character by character, or up and down by line,
  and lift to delete it; a plain hold still deletes word by word. Both have switches on the
  Layout screen.
- Key flicks: a short drag off any key in one of eight directions writes a text of yours, runs a
  quick action or presses another key, set per key and direction on the new Key flicks screen,
  where you tap the key on a live keyboard and then the direction. The key shows a small label at
  that edge, and a screen reader gets each flick as an action on the key. Two sliders set how far
  a drag must go before it counts and how far it may go. Nine quick actions join for them: a word
  left or right, selecting by the word or to either end of the line, deleting the word ahead,
  Escape and Tab.
- Other keyboards and Voice typing, as quick actions and as keys for the modifier row: the first
  opens the system's keyboard list, or, with its new setting on the Layout screen, goes straight
  back to the keyboard you used before; the second switches to a voice keyboard you have enabled,
  remembering your choice, and is offered only while there is one. Holding the globe key now
  switches the layout, as holding the space bar does with the joystick off; the quick panel
  stays on the settings key and on holding enter.
- Keep privately, a quick action and, once turned on, an entry in other apps' text-selection
  menu: the selection goes into the clipboard history as a private item that never touches the
  system clipboard and stays until you delete it, marked with the app it came from.
- A copied image is kept as a picture, not as a link the copying app can take back: encrypted on
  this device, with a small preview in the clipboard history, the same picture copied twice kept
  once. Largest image kept, on the Clipboard screen, sets the limit, 10 MB by default. Pasting
  into a field that takes no images now says so on the strip.
- The keyboard is there on the lock screen after a restart, before the first unlock: it draws
  with your theme, size and layout settings, and types without dictionaries, learning, clipboard,
  settings or composer until the device is unlocked, then carries on with all of them. The
  clipboard panel stays closed while the lock screen shows.
- Swiping reaches contractions: a swipe through d-o-n-t writes "don't", and a swipe through the
  letters of a bare spelling the keyboard already restores an apostrophe to, "didnt" or "im",
  writes it with the apostrophe. Those bare spellings are gone from the English list, so the
  strip no longer offers "cant" or "wont" beside the words they stand for. A swiped word that has
  an apostrophe twin keeps its place with the twin offered next to it, "its" then "it's"; in
  English the twin comes first when it is the commoner of the two, "I'll" before "ill".
- A slip weighs less against how common a word is, for autocorrect and the strip alike: the word
  you meant leads the strip after a neighbouring-key slip 76% of the time instead of 72%, and
  a letter left out is put back more often.
- Fixed: when autocorrect's first guess was refused as too far, as a name, or as a form of a
  word you know, the guesses behind it were never tried: "writet" stayed as typed where
  "writer" was next on the list.
- Fixed: a restored backup's words reached the suggestions only once the keyboard restarted;
  they now count at once.
- Fixed: Undo right after typing a word took back more than the word, and Redo after some typing
  wrote the undone text over it.
- Fixed: in fields built on the platform's own editor, backspace deleted the character before a
  selection made leftwards, as shift and a slide along the space bar make one, instead of the
  selection.
- Fixed: a rewrite the keyboard made — a text shortcut's expansion, a restored apostrophe or
  capital — was learned as a word when the caret had moved away and backspace came next: "omw"
  stored "on my way".
- Fixed: a password field got restored apostrophes, capitals, text shortcuts and the keyboard's
  own spaces; its keys now go in exactly as typed, and a caret moved back onto a word there no
  longer has it corrected.
- Fixed: under a Romanian verdict, "in" is written "în" whichever language pack was installed
  first.
- Fixed: letters a digit ends are left as typed: sha256, covid19 and i7 are no longer rewritten
  as Sha256, Covid19 and I7.
- Fixed: a space typed before the answer about the previous word arrived, with no word typed,
  wrote that word's possessive.
- The typing code is reorganised around one owner of each word, `TypingOrchestrator`, and the
  flows it drives; every case of the test corpora reads the same, to the digit, and 93 typing
  scenarios run the real flow on the JVM.

## v0.10.2 — 2026-09-28

- Fixed: in the plus build, an answer from the assistant no longer starts with a label the
  model put before it, in any language ("Respuesta:", "Réponse :", "Traducción:"); a label the
  text itself opens with is kept.
- Fixed: in the core build, the note under the swipe section's "Advanced settings" no longer
  mentions a neural model that build does not have.
- The store listings' screenshots show the current settings, and the core listing's are taken
  on the core build; the README, the store descriptions, the citation and the F-Droid build
  entries follow the current release.
- Building the settings module on its own no longer fails on the process-text shortcut's icon.

## v0.10.1 — 2026-09-28

- Nothing changes for the person typing: the build is made with Android Gradle plugin 9.3.3 and
  its R8 9.3.28, and the code it runs is the same as 0.10.0's.
- A release tag keeps its annotation and message once the release is published.
- The smoke suite answers Wait on a system "isn't responding" dialog before it looks for the
  settings field, and once more when the field does not appear.

## v0.10.0 — 2026-09-27

- Under the Hebrew and Arabic layouts the emoji and clipboard panels run from the right as the
  strip and the ring do: the emoji grid and its tabs start at the top right, and the clipboard's
  back arrow, thumbnails, pin marks and actions are mirrored. A clip's text is aligned by its
  own direction under any layout.
- Indonesian joins the downloadable packs, with the word pairs, names and grammar the others
  carry. Like English it has no accents of its own, so it adds no long-press letters.
- The smoke suite checks that Show on a password field's strip puts what was typed in place of
  the private notice, and that Hide puts the notice back.
- A Terminals card on the Typing and Suggestions screen: a terminal app the keyboard does not
  recognise can be added by its package name, and is typed into as a terminal from then on.
- The smoke suite types a command into Termux and checks each letter reaches the prompt before
  the word ends; CI installs Termux's own release build for it.
- The settings search sits in the title row of the home screen.
- The "Try it here" field names its mode inside the empty box, its label says a tap on it
  changes the mode once the field has focus, and its several-lines mode shows three rows.
- Every "Advanced settings" fold names what it holds on the line beneath it, so a closed fold
  can be read past instead of opened to find out.
- Fixed: importing a pack for a language already installed, or a file already imported, ended
  the settings application; it now takes the earlier copy's place, keeping its switch and
  weight.

## v0.9.0 — 2026-09-27

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
  press. Ukrainian has the apostrophe as a key of its own.
- Hebrew and Arabic layouts, read from the right: the strip's first suggestion sits at the
  right edge and the ring's wedges are mirrored. The engine folds away the Hebrew vowel points
  and the Arabic harakat, and reaches a word by its bare alef, yeh or waw whichever hamza form
  it is spelled with.
- Twenty downloadable language packs on the `packs` release, each with n-grams, names and
  grammar like the bundled six: Dutch, Portuguese, Swedish, Danish, Norwegian Bokmål, Finnish,
  Polish, Czech, Hungarian, Turkish, Russian, Ukrainian, Bulgarian, Serbian, Macedonian,
  Greek, Armenian, Georgian, Hebrew and Arabic. The Languages screen's "More languages" card
  says where they are and how to import one.
- The words you open sentences with are learned, and offered first when a sentence begins;
  the packs' own openers fill the remaining slots.
- The learning switch gates what is offered as well as what is recorded: off, the
  dictionaries alone suggest. Switching it off asks first, then forgets everything learned.
- Fixed: a ring left to apply its top word on its own timeout, with the finger still resting
  on the pause point, applied that word a second time when the finger later moved to a wedge
  and lifted.
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
