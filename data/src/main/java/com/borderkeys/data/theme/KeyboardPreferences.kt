// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/**
 * Behaviour the user can change, as opposed to appearance, which is [KeyboardTheme].
 *
 * Every default here is the conservative one. Anything that records more about the user than the
 * feature strictly needs starts off, and turning it on is an explicit act with an explanation
 * next to it.
 */
@Serializable
data class KeyboardPreferences(
    /** Minutes an unpinned clipboard entry survives. */
    val clipboardRetentionMinutes: Int = 60,
    val clipboardEnabled: Boolean = true,

    /**
     * Whether copied images are remembered alongside copied text.
     *
     * Off, although the history around it is on. A screenshot is the most revealing thing a
     * clipboard holds -- a message, a balance, a code, a face -- and the difference between
     * remembering a word and remembering a picture of a screen is large enough that it should
     * be asked for rather than assumed.
     *
     * Turning it off deletes the images already remembered, because a switch that stops
     * collecting but keeps what it collected is not the switch anyone thought they turned off.
     */
    val clipboardImages: Boolean = false,
    /** Hard cap on unpinned history, independent of the retention window. */
    val clipboardMaxEntries: Int = 60,
    /** Whether confirmed words are written to the personal dictionary at all. */
    val learningEnabled: Boolean = true,

    /**
     * Whether the built-in list of offensive words is kept out of suggestions, corrections and
     * learning. Off by default: this keyboard does not decide for anyone what they may write,
     * and a word typed letter by letter is never touched either way -- the switch only stops
     * the keyboard from *offering* one. The lists are per language, in the keyboard's own
     * assets, and follow the languages that are turned on.
     */
    val blockOffensiveWords: Boolean = false,

    /**
     * How readily what you write starts to outrank what the dictionary says.
     *
     * One of the LEARNING_ constants below. It does not change *what* is recorded -- every
     * confirmed word and pair is stored either way -- only how quickly the record starts
     * leading the suggestion strip.
     *
     * The reason it is a setting rather than a constant is that the right answer is a matter of
     * taste and nothing else. Someone who writes the same few phrases all day wants the first
     * repetition to count. Someone who writes about many things wants a keyboard that does not
     * rearrange itself around a sentence they wrote once. Neither is wrong, and picking one for
     * both is how a keyboard ends up feeling either stubborn or twitchy.
     */
    val learningSpeed: Int = LEARNING_BALANCED,
    val swipeEnabled: Boolean = true,

    /**
     * Whether the backspace pressed right after a swiped word removes the whole word (and the
     * space the swipe put in front of it) rather than its last letter. Off by default: one
     * letter is what backspace does everywhere else, and it keeps a swiped word editable in
     * place. On is the other common convention, where a swipe is accepted or rejected whole.
     */
    val swipeBackspaceDeletesWord: Boolean = false,
    /**
     * Reserved, and read by nothing.
     *
     * A per-app language memory was designed -- a hash of the target package against learned
     * weights, opt-in because it is a behavioural profile however small -- and never built. The
     * switch that set this was offered anyway, which made it a setting that did nothing, and the
     * Privacy screen described a feature that did not exist. Both are gone; the field stays only
     * so a store written while the switch existed still parses. Wire the feature up before
     * offering it again.
     */
    val perAppLanguageMemory: Boolean = false,
    val hapticFeedback: Boolean = true,

    /**
     * How firm the keypress vibration is: [HAPTIC_SYSTEM] (the phone's own keyboard tap, the
     * default), [HAPTIC_LIGHT], [HAPTIC_MEDIUM] or [HAPTIC_STRONG].
     *
     * The platform's own feedback classes rather than amplitudes: an amplitude needs the
     * VIBRATE permission and this application asks for none, while `performHapticFeedback`
     * needs nothing and the phone renders each class in its own calibrated way -- the tap
     * every keyboard gives, a faint tick, a medium click, a firm buzz. Clamped on read.
     */
    val hapticStrength: Int = HAPTIC_SYSTEM,

    /**
     * Whether a keypress makes a sound.
     *
     * Off. The system has its own keypress sound setting and this respects it when both are
     * on, but a keyboard that starts making noise on a phone that was quiet is a keyboard
     * someone has to go and switch off.
     */
    val keySound: Boolean = false,

    /**
     * Whether the first letter of a sentence is capitalised for you.
     *
     * On, and read from the editor rather than assumed: a field can ask for every sentence,
     * every word, or every character capitalised, and all three are honoured. A field that
     * asks for none of them -- a password, a URL -- gets none.
     */
    val autoCapitalise: Boolean = true,

    /**
     * Whether the first letter of a sentence is capitalised even in a field that never asked
     * for it.
     *
     * Off. [autoCapitalise] already honours a field's own request faithfully -- this is for the
     * fields that never make one: a chat box or a search bar built without
     * `TYPE_TEXT_FLAG_CAP_SENTENCES` set, which is common enough that it is worth a switch of
     * its own rather than folding it into the setting above and taking away the choice to keep
     * the faithful behaviour. Has no effect where [autoCapitalise] itself is off, and still
     * leaves a password field alone -- overriding what a field asks for is one thing, silently
     * changing what gets typed into one is another.
     */
    val forceCapitaliseSentences: Boolean = false,

    /**
     * Whether a word the dictionaries flag as a name is offered capitalised wherever it lands,
     * rather than only at the start of a sentence or after a shift.
     *
     * On by default: a name written mid-sentence is a name, and having to shift for every
     * "Maria" is the thing this exists to fix. Off for anyone who finds the dictionaries wrong
     * about which words are names often enough to be worse than the problem -- Wikidata knows
     * a person called almost anything, and the classifier that decides which of those may carry
     * the flag (see docs/dictionaries.md) is judgement, not fact.
     *
     * It governs the *capital* and nothing else. A name stays a word the dictionaries know, is
     * still suggested, and is still held to the rule that it may only ever correct its own
     * letters -- "everyone" may not become "Everton" either way.
     */
    val capitaliseNames: Boolean = true,

    /**
     * Whether two spaces become a full stop and a space.
     *
     * On, and undone by the backspace that follows it, like any other substitution this
     * keyboard makes.
     */
    val doubleSpacePeriod: Boolean = true,

    /**
     * Whether a space is added after a full stop, comma or the rest of the sentence marks.
     *
     * On. It is the space you were going to type, and it is what makes removing one before the
     * next mark worth doing -- the two settings are halves of the same idea and both are here
     * so either can be switched off alone.
     */
    val spaceAfterPunctuation: Boolean = true,

    /**
     * Whether that space is added when the mark follows a digit.
     *
     * Off. Every mark the setting above spaces is also one numbers are written with, so a space
     * there splits "12.55" into "12. 55" and "10:30" into "10: 30". A sentence that ends in a
     * number costs one space typed by hand; a decimal is broken every time it is written. Here
     * rather than assumed because a list of figures is a real way to write, and someone who
     * wants the space everywhere should be able to say so.
     */
    val spaceInsideNumbers: Boolean = false,

    /**
     * What the keyboard shows when it has news -- a word learned, a correction applied.
     *
     * Here rather than in its own store because it is behaviour the user chose, the same as
     * everything else on this class, and it reaches the IME on the flow that already carries
     * them. A field added to a serialized class reads back as its default from a file written
     * before it existed, so an upgrade keeps the one effect that already shipped and adds no
     * others.
     */
    val effects: EffectsSettings = EffectsSettings(),

    /**
     * Whether picking a suggestion from the strip also puts a space after the word, so typing
     * carries straight on. On, as every keyboard does it; off for someone who picks a word and
     * then wants to type the punctuation or suffix that follows it themselves. Never a second
     * space: one already there is left alone either way.
     */
    val spaceAfterSuggestion: Boolean = true,

    /**
     * What a space typed straight after one this keyboard added itself does -- after a
     * sentence mark, a picked suggestion, a swiped word, or the double-space full stop.
     * [AUTO_SPACE_SWALLOW_FIRST] (default) drops that one habitual space and keeps any after
     * it; [AUTO_SPACE_SWALLOW_ALL] keeps dropping spaces until something else is typed;
     * [AUTO_SPACE_KEEP] never drops one. Clamped to a valid value on read.
     */
    val autoSpaceHabit: Int = AUTO_SPACE_SWALLOW_FIRST,

    /**
     * Whether a space before a punctuation mark is removed when the mark is typed.
     *
     * On. "word ." is not something anyone means, and it is what a keyboard that adds spaces
     * after words produces if it does not also take them back.
     */
    val removeSpaceBeforePunctuation: Boolean = true,

    /**
     * Whether sliding along the space bar moves the cursor.
     *
     * On. It costs nothing when unused -- the slide has to travel further than a tap ever
     * does before it counts -- and it is the only way to place a cursor precisely without
     * covering the text with a finger.
     */
    val spaceCursorControl: Boolean = true,

    /**
     * The emoji used most recently, newest first.
     *
     * Stored with the settings rather than in the encrypted database: it is a list of pictures
     * someone sent, not of words they wrote, and the database exists for the second.
     */
    val emojiRecents: List<String> = emptyList(),

    /**
     * Whether the emoji key sits beside the space bar.
     *
     * On. Off removes it rather than hiding it, and the width it took goes back to the space
     * bar -- which is where it came from, and which is the key most worth having wide.
     */
    val emojiKey: Boolean = true,

    /**
     * Whether the globe key sits beside the space bar.
     *
     * Off. It cycles this keyboard's layouts, which is a thing some people do daily and most
     * never do at all, and it was also a second way into the panel -- which holding the enter
     * key now does. Holding the space bar cycles the layouts, so nothing is lost by giving the
     * key's width back to the space bar.
     */
    val languageKey: Boolean = false,

    // ---- size and position -------------------------------------------------------------
    //
    // A keyboard is the one part of the screen a person's thumb has to reach a hundred times a
    // minute, and whose right size depends on the hand holding the phone rather than on the
    // phone. These are the settings that let it be moved rather than endured.

    /** Multiplier on the row height. Larger keys, fewer of them on screen. */
    val heightScale: Float = 1f,
    /**
     * Fraction of the screen width the keyboard occupies. Only meaningful away from
     * [MODE_DOCKED], where a keyboard narrower than the screen would just leave a gap.
     */
    val widthScale: Float = 1f,
    /** One of the MODE_ constants below. */
    val positionMode: Int = MODE_DOCKED,
    /** How far the keyboard sits above the bottom edge, in dp. */
    val bottomOffsetDp: Float = 0f,
    /** Horizontal offset from centre, in dp. Floating mode only. */
    val horizontalOffsetDp: Float = 0f,

    /**
     * Landscape's own height, width, position, offsets and split gap -- everything above this
     * field, kept for portrait. See [KeyboardPlacement]'s own doc for why portrait stayed flat
     * instead of moving in here alongside it.
     */
    val landscape: KeyboardPlacement = KeyboardPlacement(),

    /**
     * Whether the empty strip beside a narrowed keyboard offers an arrow to move it across.
     *
     * On, because the alternative for a left-handed moment on a right-handed setting is opening
     * settings with the hand that cannot reach. The arrow appears only where there is empty
     * space to put it, which is only when the keyboard has been narrowed.
     */
    val edgeArrows: Boolean = true,

    /**
     * Whether what shows through beside a narrowed keyboard is blurred.
     *
     * Off, and not because it looks worse. A background blur is composited by the system on
     * every frame the window is visible, which on a keyboard means most of the time the screen
     * is on, and it is the kind of cost that does not appear in any number this project
     * measures. Someone who wants it can have it; nobody gets it without asking.
     *
     * The system can also refuse: cross-window blur is disabled on low-end devices and in
     * battery saver, and asking for it there does nothing at all rather than falling back to
     * something slower.
     */
    val blurBehindKeyboard: Boolean = false,

    /**
     * The language the interface is written in, as a catalogue code, or empty to follow the
     * phone.
     *
     * Empty by default, and empty is not the same as "en": following the phone means a phone
     * later switched to a language BorderKeys ships picks it up, where a stored "en" would
     * stay English forever. What the phone asks for and what is shipped are reconciled by
     * LanguageResolution, which falls back to English only when nothing else matches.
     *
     * This is the interface language and nothing else. Which dictionaries predict words is a
     * separate setting on the Languages screen, because the two are genuinely independent:
     * plenty of people read an English interface while writing Romanian.
     */
    val uiLanguage: String = "",

    /**
     * How readily the keyboard stops offering words from the languages you are not writing in.
     *
     * On by default, and balanced rather than quick: a wrong guess is worse than a slow one,
     * because it removes words rather than adding them. Off is a real choice -- someone who
     * writes two languages inside one sentence is not served by the keyboard picking a side.
     *
     * What you have written yourself is never filtered by this. A phrase you repeat is
     * evidence about you, which outranks any guess about the sentence.
     */
    val languageLock: Int = LANGUAGE_LOCK_BALANCED,

    /**
     * The language tag to start from before anything has been recognised, or empty for none.
     *
     * Empty by default, and staying empty is a real choice rather than an unfinished setup: with
     * no preference every dictionary is offered until the evidence decides, which is what someone
     * who writes two languages interchangeably wants and is exactly the behaviour that shipped
     * before this existed.
     *
     * *Preferred*, not primary. It decides where detection starts and never what wins: the moment
     * [languageLock]'s evidence names a language, that one answers instead, and a word the
     * preferred dictionary does not hold still falls through to the others. It is also not a
     * weight -- a pack's weight is a term in every candidate's score, so using it to say "start
     * here" also biased every one of that language's words for ever, which is the confusion this
     * setting exists to end.
     *
     * A tag naming a pack that has since been removed or switched off simply behaves as none.
     */
    val preferredLanguageTag: String = "",

    /**
     * What happens to a correction already applied once [languageLock]'s own evidence decides
     * the conversation was actually in a different language all along.
     *
     * Off by default: rewriting text after the cursor has already moved past it is a stronger
     * version of the one failure a keyboard that corrects as you type is built to avoid, so this
     * stays opt-in rather than inherited from [languageLock] being on. `Ask` offers the affected
     * word back without touching the field; `Auto-apply` edits it immediately, same as any other
     * correction, and either way the edit lands on the field's own undo history.
     */
    val languageSwitchCorrectionMode: Int = LANGUAGE_SWITCH_OFF,

    /**
     * Whether swipe typing is decoded by the trained neural model (tier B) instead of the
     * geometric one (tier A) that always ships. `plus`-only in effect -- a `core` build has no
     * tier B compiled in at all, so this setting does nothing there.
     *
     * On by default in `plus`. Measured against 500 recorded traces and the shipped English
     * pack, tier B reaches 76.2% top-1 where tier A reaches 51.6%, and it is ahead at every word
     * length -- including one-letter gestures, which tier A cannot decode at all. The name keeps
     * "experimental" because the stored preference key does; renaming it would discard the
     * choice of anyone who has already set it.
     */
    val experimentalSwipeModelEnabled: Boolean = true,

    /**
     * Set by the keyboard, never chosen by anyone: tier B's weights could not be read or were
     * not valid, so the option above is disabled for good on this installation.
     *
     * Permanent on purpose. A `model.bkw` that does not parse is a property of the installed
     * build, not of the moment -- retrying it on every keyboard start would re-read two and a
     * half megabytes to fail again, and leaving the switch live would offer a feature that
     * cannot work. What the user gets instead is a disabled row saying so and asking them to
     * report it. Cleared by installing the application again, which is also the only thing that
     * could plausibly fix the underlying file; the backup restore path clears it too, since a
     * file copied from another phone says nothing about this build's assets.
     */
    val swipeModelFailed: Boolean = false,

    /**
     * An alternative/addition to the suggestion strip for a swipe: pausing mid-gesture shows a
     * quick preview near the finger, and lifting turns it into a real menu -- a tap picks a
     * candidate, or the top one applies itself after [radialPickTimeoutMillis] if nothing is
     * tapped. Off by default: the strip already does this job, and a menu appearing over the
     * keys uninvited is not a change to make without asking first.
     */
    val radialMenuEnabled: Boolean = false,

    /**
     * How many words the ring offers at once. Clamped to [MIN_RADIAL_SUGGESTIONS]..
     * [MAX_RADIAL_SUGGESTIONS] on read -- a lower ceiling than [suggestionCount]'s own, on
     * purpose: a wedge around a circle gets unreadable and mis-tappable far sooner than a narrow
     * rectangle in a row does.
     */
    val radialSuggestionCount: Int = DEFAULT_RADIAL_SUGGESTIONS,

    /**
     * How long the finger has to hold still mid-swipe before the preview shows, in milliseconds.
     * Clamped to [MIN_RADIAL_PAUSE_DWELL_MILLIS]..[MAX_RADIAL_PAUSE_DWELL_MILLIS] on read.
     */
    val radialPauseDwellMillis: Int = DEFAULT_RADIAL_PAUSE_DWELL_MILLIS,

    /**
     * How far a swipe has to have already travelled -- its own bounding-box diagonal -- before
     * the pause-dwell timer is ever armed at all, in letters rather than pixels: nobody knows
     * how many pixels their screen has, but everybody knows roughly how wide a key is. Converted
     * to a real pixel distance at the keyboard itself, against that layout's own measured
     * average key width -- see `KeyboardCanvasView`'s own doc for the conversion. Guards against
     * a slow-starting swipe reading as an instant pause: the very first samples of a real swipe
     * are, by definition, still close to where the finger went down. Clamped to
     * [MIN_RADIAL_MIN_PATH_LETTERS]..[MAX_RADIAL_MIN_PATH_LETTERS] on read; `0` removes the
     * guard entirely (any pause, however early, can open the ring).
     */
    val radialMinPathLetters: Float = DEFAULT_RADIAL_MIN_PATH_LETTERS,

    /**
     * How long the real menu waits, after a lift, before applying the top candidate on its own.
     * Clamped to [MIN_RADIAL_PICK_TIMEOUT_MILLIS]..[MAX_RADIAL_PICK_TIMEOUT_MILLIS] on read.
     */
    val radialPickTimeoutMillis: Int = DEFAULT_RADIAL_PICK_TIMEOUT_MILLIS,

    /**
     * Where the ring is centred. [RADIAL_ANCHOR_FINGER] (default) puts it where the swipe
     * paused or ended -- the whole point of a radial menu being "around the finger" in the
     * first place. [RADIAL_ANCHOR_CENTER] fixes it to the middle of the keyboard regardless of
     * where the gesture happened, and [RADIAL_ANCHOR_TANGENT_LEFT]/[RADIAL_ANCHOR_TANGENT_RIGHT]
     * fix its horizontal position to one side (vertically it still follows the gesture) --
     * useful for someone who always swipes one-handed and would rather the ring never lands
     * under the thumb doing the swiping. Clamped to a valid value on read the same way
     * [languageSwitchCorrectionMode] is; an unrecognised value falls back to
     * [RADIAL_ANCHOR_FINGER].
     */
    val radialMenuAnchor: Int = RADIAL_ANCHOR_FINGER,

    /**
     * How big the ring is drawn, as one of [RADIAL_SIZE_SMALL]/[RADIAL_SIZE_MEDIUM]/
     * [RADIAL_SIZE_LARGE] -- a named size rather than a free slider, the same reasoning
     * [KeyboardPreferences]'s own text-size settings elsewhere in this file already use: a
     * handful of tested, legible sizes is a better set of choices than a continuous range
     * whose in-between values were never actually checked for tap-target size. Clamped to a
     * valid value on read; an unrecognised value falls back to [RADIAL_SIZE_MEDIUM].
     */
    val radialMenuSize: Int = RADIAL_SIZE_MEDIUM,

    /**
     * What happens if the ring is resolved -- released, or the pick-timeout elapses -- with
     * neither a wedge nor the centre Cancel button touched. [RADIAL_TIMEOUT_APPLY_TOP] (default)
     * applies rank #1, the same outcome as if the pause had never happened at all: passivity is
     * never destructive unless this is changed. [RADIAL_TIMEOUT_CANCEL] makes passivity discard
     * instead -- getting a word then always requires deliberately steering to a wedge. The centre
     * Cancel button is the *only* deliberate way to cancel either way; this setting only decides
     * what *not* choosing does. Clamped to a valid value on read; an unrecognised value falls
     * back to [RADIAL_TIMEOUT_APPLY_TOP].
     */
    val radialTimeoutDefault: Int = RADIAL_TIMEOUT_APPLY_TOP,

    /**
     * What the ring does with rank #1, which is already composing in the field and is never one
     * of the wedges.
     *
     * [RADIAL_TRUSTED_CHIP] (default) gives it a wedge of its own, outlined the way the strip
     * outlines the chip that would act on its own, so keeping the decoded word is a tap like any
     * other choice. [RADIAL_TRUSTED_AUTO_APPLY] closes the ring instead and keeps that word,
     * leaving the alternatives to the strip. Clamped to a valid value on read; an unrecognised
     * value falls back to [RADIAL_TRUSTED_CHIP].
     */
    val radialTrustedWord: Int = RADIAL_TRUSTED_CHIP,

    /**
     * How a completed swipe offers its alternatives when nothing is deliberately chosen at lift.
     *
     * Off (default): resolves immediately. A paused gesture lifted in the dead zone applies
     * [radialTimeoutDefault] right away; a confident, no-pause gesture never shows a ring at all
     * -- the strip already offers the same alternatives.
     *
     * On: the ring is shown -- kept open if a pause had already opened it, or opened fresh and
     * tap-only after a confident no-pause lift -- and waits indefinitely for a deliberate tap on
     * a word or the centre Cancel button. Nothing resolves it on its own; no clock, no default
     * applied for you. While it waits it is modal: a touch anywhere else -- on the keyboard or
     * in the text field -- only closes it, typing nothing and leaving the swiped word exactly as
     * it is; the centre X remains the only thing that removes that word. This is the same shape
     * the ring had before the single-stroke redesign, for anyone who would rather look at the
     * alternatives for as long as they want rather than race a countdown.
     */
    val radialLiftKeepsOpen: Boolean = false,

    /**
     * Whether the keys behind the ring are blurred while it is open, on API 31+.
     *
     * On by default -- unlike [blurBehindKeyboard] below, which this shares the same
     * `RenderEffect` mechanism with: that one runs every frame the keyboard window is visible,
     * this one only for as long as the ring itself is up, so the same rendering cost that is
     * opt-in there is worth defaulting on here. The ring's own semi-transparent scrim still dims
     * things on its own with this off, or below API 31 where `RenderEffect` does not exist at
     * all -- this only ever removes the extra blur on top of that, never the dimming itself.
     */
    val radialBlurBackground: Boolean = true,

    /**
     * Whether a tap outside the ring, on the keyboard, also takes the keyboard down. Off by
     * default: the tap only closes the ring, and the keys are right there for the next word. A
     * tap in the text field, and the keyboard being hidden for any reason, always close the ring
     * regardless of this.
     */
    val radialOutsideTapHidesKeyboard: Boolean = false,

    /**
     * Whether the ring closes when the text field moves on screen while it is open -- the one
     * signal a keyboard gets for a tap elsewhere in the app that scrolled the page. On by
     * default. A tap that neither scrolls nor takes focus nor hides the keyboard is invisible to
     * every keyboard, and no setting can change that.
     */
    val radialCloseOnEditorMove: Boolean = true,

    /**
     * Debug builds only: keeps a sample ring open on every field so its particle effects can be
     * seen and tuned without swiping a real gesture each time -- the ring is the one surface an
     * emulator cannot open on demand (a paused swipe needs a real continuous finger). The
     * settings row that flips this is only shown in a debuggable build, and the keyboard
     * ignores it in a release one, so a stray `true` in a backup can never leave a ring stuck
     * open for a user.
     */
    val debugForceRadialRing: Boolean = false,

    // Particle effects moved out to their own top-level ParticleEffectsSettings/DataStore --
    // five regions x two layers each grew far past what belonged bolted onto this class. See
    // that class's own doc.

    /**
     * Whether the first slot of the suggestion strip offers what is on the clipboard.
     *
     * Off by default, and not out of caution about the feature: the strip is glanced at while
     * typing, and putting something there that inserts text nobody just wrote is a change to
     * what that row means. Someone who wants it can have it.
     *
     * It takes one of the slots rather than adding one, so turning it on does not narrow every
     * target on the row. Suppressed entirely in a password field, along with everything else.
     */
    val clipboardSuggestion: Boolean = false,

    /**
     * Whether the clipboard is emptied after its content is inserted from the chip.
     *
     * Off by default: the clipboard belongs to the system and to every other app, and a
     * keyboard that quietly empties it is a keyboard that loses someone's copied text when
     * they meant to paste it twice. On, it is a reasonable hygiene setting for anyone who
     * copies things they would rather not leave lying there.
     */
    val clearClipboardAfterInsert: Boolean = false,

    /**
     * Whether the clipboard chip is withdrawn after it has been used, or once the keyboard has
     * been closed.
     *
     * On. Something copied is usually pasted once, and a chip that stays for the rest of the
     * session is a slot the suggestions could have had. It withdraws the *offer* only: what was
     * copied is still in the history panel, and the system clipboard is untouched -- the switch
     * for emptying that is the one below.
     */
    val clipboardSuggestionOnce: Boolean = true,

    /**
     * Whether inserting the current clipboard item also deletes it from the history panel, if it
     * is not pinned.
     *
     * Off by default, and separate from every other clipboard switch here: [clipboardSuggestionOnce]
     * only withdraws the chip's *offer*, [clearClipboardAfterInsert] only empties the *system*
     * clipboard, and the retention timer expires whatever is old regardless of whether it was
     * ever used. This is the one that removes the row itself, and only that one row -- the rest
     * of the history is untouched -- the moment the item is actually pasted. For a one-time code
     * or a password copied to hand off once: used, then gone, rather than sitting in an encrypted
     * table until its timer or a manual delete catches up with it.
     */
    val clipboardDeleteAfterUse: Boolean = false,

    /**
     * Whether the clipboard history is emptied when the keyboard closes.
     *
     * Off, and a much blunter instrument than the retention timer beside it: everything
     * unpinned goes the moment you leave the field, whether it was copied a second ago or an
     * hour. For someone who wants the history while they are writing and nothing afterwards.
     */
    val clearClipboardOnClose: Boolean = false,

    /**
     * Whether the Compose quick action can open the draft box at all.
     *
     * On. It costs nothing while the box is not open, and turning it off is for someone who
     * would rather the quick actions row not offer a way out to a separate screen at all.
     */
    val composerEnabled: Boolean = true,

    /**
     * The buttons on the draft box's control bar, in order, as [ComposerAction] ids -- or, since
     * a custom action can be pinned here too, one of [customActions]' own ids. The two id spaces
     * never overlap ([CustomAction.nextId] draws from a range clear of [ComposerAction]'s 1-10),
     * so this stays one flat `List<Int>` rather than needing its own persisted shape change;
     * [ComposerBar.resolve] is what turns an id back into whichever kind it names.
     *
     * Ids rather than ordinals, and read back through the enum (or the custom-action list), for
     * the same reason the quick actions are: a bar written by a later build must open rather
     * than fail.
     */
    val composerBar: List<Int> = ComposerAction.DEFAULT.map { it.id },

    /** The draft box's own text size, one of the `COMPOSER_TEXT_SIZE_*` steps below. */
    val composerTextSize: Int = COMPOSER_TEXT_SIZE_MEDIUM,

    /**
     * Whether a selection in the draft box grows out to whole words before an action runs on it.
     *
     * On: a selection that starts or ends inside a word is pushed to that word's edge first, so
     * what a model is asked to translate or correct is always a whole phrase. Off: the selection
     * is sent exactly as it was made, mid-word or not.
     */
    val composerSnapSelectionToWords: Boolean = true,

    /**
     * Instructions the user wrote and kept, in the order they were saved -- each addressable by
     * its own stable id, so one can also be pinned onto [composerBar] as a real button. Field
     * name kept as `savedPrompts` on the wire ([SerialName]) so an existing install's file still
     * decodes; see [CustomAction]'s own doc for the rest of that story.
     */
    @SerialName("savedPrompts") val customActions: List<CustomAction> = emptyList(),

    /** Whether the row of quick actions is shown at all. */
    val quickActionsEnabled: Boolean = false,

    /**
     * The actions on the bar, in order, as [QuickAction] ids -- or, since a custom macro can be
     * pinned here too, one of [customQuickActions]' own ids. The two id spaces never overlap
     * ([CustomQuickAction.nextId] draws from a range clear of [QuickAction]'s 1-21), so this
     * stays one flat `List<Int>` rather than needing its own persisted shape change, the same
     * trick [composerBar] already plays; [QuickActionBar.resolve] is what turns an id back into
     * whichever kind it names.
     *
     * Ids rather than ordinals so that removing an action from the enum later does not turn
     * someone's saved bar into a different bar; an id this build does not know is dropped when
     * the list is read.
     */
    val quickActions: List<Int> = QuickAction.DEFAULT.map { it.id },

    /**
     * Macros the user built for the quick-action bar -- each an ordered list of [QuickAction]
     * (or other custom action) ids, addressable by its own stable id so one can be pinned onto
     * [quickActions] as a real button. See [CustomQuickAction] for the shape and
     * [QuickActionBar.flatten] for how a macro turns into the steps that actually run.
     */
    val customQuickActions: List<CustomQuickAction> = emptyList(),

    /**
     * Words that expand into longer text when a delimiter follows them -- see [TextShortcut].
     * Sanitised on read: a trigger with whitespace in it, an empty expansion, or a second
     * shortcut for the same trigger (compared without case) is dropped.
     */
    val textShortcuts: List<TextShortcut> = emptyList(),

    /** Whether the bar starts open or as a single button that opens it. */
    val quickActionsMode: Int = QUICK_ACTIONS_COLLAPSED,

    /** Which edge the bar, and the button that stands in for it, sit against. */
    val quickActionsPlacement: Int = QUICK_ACTIONS_ABOVE_STRIP,

    /**
     * How much room the bar gives each button, on whichever axis its thickness is --
     * [QUICK_ACTIONS_SIZE_DEFAULT] is today's bar unchanged; each step past it is a little
     * taller (or wider, down a side) with a little more space around a button that is, in
     * turn, a little smaller -- the room comes from somewhere, and it is not the bar's own
     * length, which the keyboard's width already spoken for.
     */
    val quickActionsSize: Int = QUICK_ACTIONS_SIZE_DEFAULT,

    /**
     * Whether each button on the bar also says what it is, in small text under its icon --
     * the same idea as the labels under the draft box's own action bar.
     *
     * Off by default: the labels cost the bar a little extra thickness. A bar down a side
     * has no room under an icon at all, so a vertical bar stays icons-only whatever this
     * says.
     */
    val quickActionsLabels: Boolean = false,

    /**
     * A permanent row of digits above the letters.
     *
     * Off by default. It costs about a fifth of the keyboard's height, and on a touch surface
     * key size is accuracy -- so it is a choice, and the digits are reachable by long press
     * either way.
     */
    val numberRow: Boolean = false,

    /**
     * A row of hardware keys above the letters: escape, tab, control, alt and the arrows.
     *
     * Off by default. It is for terminals and editors, which read those keys, and it costs
     * the same height as the number row.
     */
    val modifierRow: Boolean = false,

    /**
     * Where the modifier row sits: [MODIFIER_ROW_ABOVE] the letters, under the strip, or
     * [MODIFIER_ROW_BELOW] the keyboard, under the space row.
     */
    val modifierRowPosition: Int = MODIFIER_ROW_ABOVE,

    /**
     * The keys on the modifier row, left to right, by the names [ModifierRowKeys] lists. Read
     * through [ModifierRowKeys.sanitised]; an empty result shows the default row.
     */
    val modifierRowKeys: List<String> = ModifierRowKeys.DEFAULT,

    /** Whether the feature tour shown after setup has been dismissed for good. */
    val featuresTourSeen: Boolean = false,

    /**
     * Where the digits sit on the number-and-symbols page.
     *
     * [SYMBOLS_NUMBER_RIGHT] and [SYMBOLS_NUMBER_LEFT] put the digits in a 3x3 block with the
     * symbols beside it, nine near-square keys a row, backspace and enter down the right edge,
     * on whichever side a thumb prefers. [SYMBOLS_NUMBER_TOP] is a row above the symbols, the
     * arrangement a physical keyboard uses. One of the three.
     */
    val symbolsNumberPosition: Int = SYMBOLS_NUMBER_RIGHT,

    /**
     * The diacritics merged onto the letter keys' long press, taken from the enabled language
     * packs. On, a Romanian pack puts ă, â on `a`; off, the letter keys carry only their
     * symbols. The base layout has none of its own -- see the `accents/` assets.
     */
    val accentedCharacters: Boolean = true,

    /** The small character drawn in a key's corner showing what its long press would type. */
    val longPressHints: Boolean = true,

    /**
     * Whether a pressed key shows itself enlarged above the finger for as long as it is held,
     * the way most keyboards do. On: it is the one piece of feedback a finger covering the
     * key cannot get any other way. Letters, digits and symbols only -- shift, backspace,
     * space and enter say what they are by what happens.
     */
    val keyPopup: Boolean = true,

    /**
     * How long a key must be held before the long press fires, in milliseconds. Clamped to
     * [MIN_LONG_PRESS_MILLIS]..[MAX_LONG_PRESS_MILLIS] on read.
     */
    val longPressMillis: Int = DEFAULT_LONG_PRESS_MILLIS,

    /**
     * What Enter does. [ENTER_KEY_AUTO] follows the field: its declared action (Send, Done, Go...)
     * runs, unless the field also set `IME_FLAG_NO_ENTER_ACTION` to say Enter should still be a
     * plain newline even though it declared one -- the usual reason being a chat-style compose
     * box with its own separate send button. [ENTER_KEY_FORCE_ACTION] always runs the field's
     * action where one exists, that flag included, falling back to a newline only where there
     * genuinely is no action to run. [ENTER_KEY_FORCE_NEWLINE] never runs one at all. The two
     * force modes exist for someone who has decided a field is wrong about what Enter should do
     * more often than the field itself would ever admit to.
     */
    val enterKeyBehavior: Int = ENTER_KEY_AUTO,

    /** Switch to a numeric keypad automatically in numeric and phone fields. */
    val numericKeypad: Boolean = true,
    val showSuggestionStrip: Boolean = true,

    /**
     * How many suggestions the strip offers at once.
     *
     * Three by default, and more is not obviously better: the strip is a fixed width, so every
     * extra slot makes each one narrower and each target smaller. Eight fits on a phone only
     * because most words are short. The right number depends on how wide the screen is and how
     * accurate the person's thumb is, which is why it is a setting and not a constant.
     */
    val suggestionCount: Int = DEFAULT_SUGGESTIONS,

    /**
     * Whether a suggestion may be two words rather than one.
     *
     * Off by default. It offers "vreau să" where it would otherwise offer "vreau", but only from
     * phrases this person has written repeatedly -- never from the dictionary, because frequency
     * can chain any two common pairs into something grammatical and meaningless.
     *
     * The second word is held to twice the evidence of the first, so a two-word suggestion needs
     * about four repetitions where a one-word one needs two. Twice the evidence for twice the
     * guess: a wrong single word costs a glance, a wrong pair costs the glance and the suspicion
     * that the keyboard is inventing things.
     */
    val phraseSuggestions: Boolean = false,

    /**
     * Whether a delimiter applies the leading suggestion instead of committing what was typed.
     *
     * Off, and the default is the argument. A keyboard that rewrites what you wrote because it
     * has a better idea is the failure mode this project was written against, and the ordinary
     * behaviour -- space commits your letters, a suggestion is applied only when tapped -- is
     * the one that never surprises anyone.
     *
     * It is offered anyway because the objection to autocorrect is really an objection to
     * *irreversible* autocorrect: the correction lands, the sentence moves on, and undoing it
     * costs more keystrokes than typing it did. With [revertCorrectionOnBackspace] the next
     * backspace puts back exactly what was typed, so the cost of a wrong correction is one key.
     */
    val autoCorrectOnSpace: Boolean = false,

    /**
     * Whether the backspace immediately after an applied correction restores what was typed.
     *
     * Only meaningful with [autoCorrectOnSpace], and on by default because a correction that
     * cannot be taken back in one key is the thing worth refusing. Turning this off leaves
     * backspace deleting one character at a time, which is what it does everywhere else.
     */
    val revertCorrectionOnBackspace: Boolean = true,

    /**
     * How far a correction may be from what was typed before a delimiter applies it, counted in
     * single-letter edits (a letter missing, added, wrong, or two swapped) after case and accents
     * are set aside. [CORRECTION_DISTANCE_STRICT] allows one edit; [CORRECTION_DISTANCE_NORMAL]
     * (default) one edit, or two in a word of eight letters or more; [CORRECTION_DISTANCE_LOOSE]
     * two edits always. This is a ceiling on top of [correctionStrictness], which only decides
     * how a candidate is *ranked*: without it, a correct word the dictionaries simply do not
     * know ("snobul") was replaced by whatever ranked first, however far away it was ("noul").
     * Clamped to a valid value on read.
     */
    val correctionDistance: Int = CORRECTION_DISTANCE_NORMAL,

    /**
     * The shortest word a delimiter will replace.
     *
     * Three by default: one- and two-letter words are where a correction is least likely to be
     * right and most annoying when it is not -- half the alphabet is one edit away from "a" or
     * "la", and the strip is full of them. Someone typing a language with a lot of short real
     * words can raise it; someone who wants every word considered can lower it to one.
     */
    val minCorrectionLength: Int = 3,

    /**
     * How much evidence an edit needs before it outranks a word spelled exactly as typed.
     *
     * 1.0 is the engine's own calibrated default. Below it, a correction needs a smaller
     * frequency gap to win -- more of what is typed gets corrected, including some that should
     * not have been. Above it, the gap has to be bigger -- fewer corrections, and the ones that
     * still happen are closer to certain. It does not change which words exist, only how
     * cautious the strip is about preferring one spelling over another.
     */
    val correctionStrictness: Float = DEFAULT_CORRECTION_STRICTNESS,

    /**
     * How far the text assistant's model may wander from the single most likely next word.
     *
     * `plus` only -- read by [com.borderkeys.assist.TextAssistService], not by anything in this
     * module. Kept here rather than in a separate store because it is a preference like any
     * other on this screen, not model state: it survives across models and is applied to
     * whichever one is loaded next.
     */
    val assistTemperature: Float = DEFAULT_ASSIST_TEMPERATURE,

    /** The nucleus (top-p) the same model samples from. See [assistTemperature]. */
    val assistTopP: Float = DEFAULT_ASSIST_TOP_P,

    /**
     * The imported model, by file name, that translation runs on -- empty to run it on the
     * active model like everything else. Only has an effect with more than one model imported;
     * a name that no longer matches an imported model is ignored. See
     * [com.borderkeys.data.assist.AssistCategory].
     */
    val assistTranslateModel: String = "",

    /** The model, by file name, that correcting, rewriting and summarising run on. Empty for the
     *  active model. See [assistTranslateModel]. */
    val assistWriteModel: String = "",

    /**
     * Whether the keyboard's palette follows the phone's wallpaper instead of the theme's own
     * stored colours.
     *
     * Off by default, and deliberately not a preset: turning it on does not overwrite the
     * colours in [KeyboardTheme] on disk, it only changes which ones the draw path reads for as
     * long as this stays on. Turning it back off is what gets the theme's own colours back
     * exactly as they were, not a preset applied on top of them. See
     * `com.borderkeys.theme.DynamicColors`, which is where the actual reading happens -- this
     * flag lives here rather than on [KeyboardTheme] because it is a mode, not a colour, and
     * [KeyboardTheme] is deliberately nothing but colours and shape.
     */
    val followSystemColors: Boolean = false,

    /**
     * Whether the keyboard switches between [KeyboardTheme] and the separate light theme on its
     * own, following the phone's own dark/light setting, rather than always showing whichever
     * one was picked by hand.
     *
     * [THEME_MODE_MANUAL] by default: the single stored theme, exactly as today. In
     * [THEME_MODE_AUTO_SYSTEM], [KeyboardTheme] is shown when the system is in dark mode and
     * `ThemeRepository.lightTheme` when it is not -- two themes a person can each customise on
     * their own screen, switched between rather than one theme algorithmically inverted, because
     * a keyboard's colours are a choice and dark-mode CSS tricks on somebody's carefully picked
     * palette produce a theme nobody picked. Composes with [followSystemColors]: which of the
     * two themes is showing is decided first, dynamic colours are layered on top of it second,
     * the same order the resolving code applies them in.
     */
    val themeMode: Int = THEME_MODE_MANUAL,
) {
    fun sanitised(): KeyboardPreferences {
        // Portrait's own six fields are [KeyboardPlacement]'s -- clamped by asking that class,
        // the same way [landscape] two lines below already does for its own copy, rather than
        // by a second set of coerceIn calls next to it that could drift from what that class
        // considers sane.
        val portrait = placementFor(isLandscape = false).sanitised()
        // Backfilled/bounded before composerBar below is sanitised against it -- a copy(...)'s
        // named arguments each read this instance's ORIGINAL properties, not each other's new
        // values, so composerBar cannot validate against a customActions this same call is also
        // rewriting unless that rewrite happens here, first, as its own local.
        val sanitisedCustomActions = CustomAction.backfillLegacyIds(customActions)
            .filter { it.name.isNotBlank() && it.instruction.isNotBlank() }
            .map {
                it.copy(
                    name = it.name.take(CustomAction.MAX_NAME_CHARS),
                    instruction = it.instruction.take(CustomAction.MAX_INSTRUCTION_CHARS),
                )
            }
            .take(CustomAction.MAX_CUSTOM_ACTIONS)
        // Same reasoning as sanitisedCustomActions above, and for the same reason: quickActions
        // below validates its ids against this, so the steps inside each macro have to be
        // resolved first, against each other, as their own local.
        val sanitisedCustomQuickActions = customQuickActions
            .filter { it.name.isNotBlank() }
            .map { it.copy(name = it.name.take(CustomQuickAction.MAX_NAME_CHARS)) }
            .take(CustomQuickAction.MAX_CUSTOM_QUICK_ACTIONS)
            .let { candidates ->
                candidates.map { candidate ->
                    candidate.copy(
                        steps = QuickActionBar.sanitisedSteps(candidate.id, candidate.steps, candidates),
                    )
                }
            }
        // One shortcut per trigger, the first one wins, compared the way the keyboard matches
        // them -- without case -- so "OMW" and "omw" cannot both be stored and only one fire.
        val seenTriggers = HashSet<String>()
        val sanitisedTextShortcuts = textShortcuts
            .map { it.copy(trigger = it.trigger.trim(), expansion = it.expansion.trim().take(TextShortcut.MAX_EXPANSION_CHARS)) }
            .filter { TextShortcut.isValidTrigger(it.trigger) && it.expansion.isNotEmpty() }
            .filter { seenTriggers.add(it.trigger.lowercase()) }
            .take(TextShortcut.MAX_SHORTCUTS)
        return copy(
            hapticStrength = if (hapticStrength in HAPTIC_LIGHT..HAPTIC_SYSTEM) hapticStrength else HAPTIC_SYSTEM,
            textShortcuts = sanitisedTextShortcuts,
        minCorrectionLength = minCorrectionLength.coerceIn(MIN_CORRECTION_LENGTH, MAX_CORRECTION_LENGTH),
        correctionStrictness = if (correctionStrictness > 0f) {
            correctionStrictness.coerceIn(MIN_CORRECTION_STRICTNESS, MAX_CORRECTION_STRICTNESS)
        } else {
            DEFAULT_CORRECTION_STRICTNESS
        },
        assistTemperature = if (assistTemperature > 0f) {
            assistTemperature.coerceIn(MIN_ASSIST_TEMPERATURE, MAX_ASSIST_TEMPERATURE)
        } else {
            DEFAULT_ASSIST_TEMPERATURE
        },
        assistTopP = if (assistTopP > 0f) {
            assistTopP.coerceIn(MIN_ASSIST_TOP_P, MAX_ASSIST_TOP_P)
        } else {
            DEFAULT_ASSIST_TOP_P
        },
        assistTranslateModel = assistTranslateModel.take(MAX_MODEL_FILE_NAME_CHARS),
        assistWriteModel = assistWriteModel.take(MAX_MODEL_FILE_NAME_CHARS),
        themeMode = if (themeMode == THEME_MODE_AUTO_SYSTEM) THEME_MODE_AUTO_SYSTEM else THEME_MODE_MANUAL,
        clipboardRetentionMinutes = clipboardRetentionMinutes.coerceIn(1, 60 * 24 * 30),
        clipboardMaxEntries = clipboardMaxEntries.coerceIn(1, 1000),
        // heightScale, widthScale, positionMode, bottomOffsetDp and horizontalOffsetDp all
        // come from `portrait` above instead of their own coerceIn here -- see that val's
        // comment.
        heightScale = portrait.heightScale,
        widthScale = portrait.widthScale,
        positionMode = portrait.positionMode,
        symbolsNumberPosition = if (symbolsNumberPosition in SYMBOLS_NUMBER_TOP..SYMBOLS_NUMBER_RIGHT) {
            symbolsNumberPosition
        } else {
            SYMBOLS_NUMBER_RIGHT
        },
        longPressMillis = longPressMillis.coerceIn(MIN_LONG_PRESS_MILLIS, MAX_LONG_PRESS_MILLIS),
        enterKeyBehavior = if (enterKeyBehavior in ENTER_KEY_AUTO..ENTER_KEY_FORCE_NEWLINE) {
            enterKeyBehavior
        } else {
            ENTER_KEY_AUTO
        },
        suggestionCount = suggestionCount.coerceIn(MIN_SUGGESTIONS, MAX_SUGGESTIONS),
        radialSuggestionCount = radialSuggestionCount.coerceIn(
            MIN_RADIAL_SUGGESTIONS, MAX_RADIAL_SUGGESTIONS,
        ),
        radialPauseDwellMillis = radialPauseDwellMillis.coerceIn(
            MIN_RADIAL_PAUSE_DWELL_MILLIS, MAX_RADIAL_PAUSE_DWELL_MILLIS,
        ),
        radialMinPathLetters = radialMinPathLetters.coerceIn(
            MIN_RADIAL_MIN_PATH_LETTERS, MAX_RADIAL_MIN_PATH_LETTERS,
        ),
        radialPickTimeoutMillis = radialPickTimeoutMillis.coerceIn(
            MIN_RADIAL_PICK_TIMEOUT_MILLIS, MAX_RADIAL_PICK_TIMEOUT_MILLIS,
        ),
        radialMenuAnchor = if (radialMenuAnchor in RADIAL_ANCHOR_FINGER..RADIAL_ANCHOR_TANGENT_RIGHT) {
            radialMenuAnchor
        } else {
            RADIAL_ANCHOR_FINGER
        },
        radialMenuSize = if (radialMenuSize in RADIAL_SIZE_SMALL..RADIAL_SIZE_LARGE) {
            radialMenuSize
        } else {
            RADIAL_SIZE_MEDIUM
        },
        autoSpaceHabit = if (autoSpaceHabit in AUTO_SPACE_SWALLOW_FIRST..AUTO_SPACE_KEEP) {
            autoSpaceHabit
        } else {
            AUTO_SPACE_SWALLOW_FIRST
        },
        correctionDistance = if (correctionDistance in CORRECTION_DISTANCE_STRICT..CORRECTION_DISTANCE_LOOSE) {
            correctionDistance
        } else {
            CORRECTION_DISTANCE_NORMAL
        },
        radialTimeoutDefault = if (radialTimeoutDefault in
            RADIAL_TIMEOUT_APPLY_TOP..RADIAL_TIMEOUT_CANCEL
        ) {
            radialTimeoutDefault
        } else {
            RADIAL_TIMEOUT_APPLY_TOP
        },
        radialTrustedWord = if (radialTrustedWord in
            RADIAL_TRUSTED_CHIP..RADIAL_TRUSTED_AUTO_APPLY
        ) {
            radialTrustedWord
        } else {
            RADIAL_TRUSTED_CHIP
        },
        learningSpeed = if (learningSpeed in LEARNING_CAUTIOUS..LEARNING_IMMEDIATE) {
            learningSpeed
        } else {
            LEARNING_BALANCED
        },
        bottomOffsetDp = portrait.bottomOffsetDp,
        horizontalOffsetDp = portrait.horizontalOffsetDp,
        landscape = landscape.sanitised(),
        // A language code, not free text. Bounded so a corrupt file cannot carry an arbitrarily
        // long string into every lookup; an unknown code resolves to English anyway.
        uiLanguage = uiLanguage.take(MAX_LANGUAGE_TAG),
        // Same reasoning as uiLanguage above: a language tag, not free text, bounded so a corrupt
        // file cannot carry an arbitrarily long string into the engine. An unrecognised tag names
        // no open pack and is treated as no preference at all.
        preferredLanguageTag = preferredLanguageTag.take(MAX_LANGUAGE_TAG),
        languageLock = if (languageLock in LANGUAGE_LOCK_OFF..LANGUAGE_LOCK_STRICT) {
            languageLock
        } else {
            LANGUAGE_LOCK_BALANCED
        },
        languageSwitchCorrectionMode =
            if (languageSwitchCorrectionMode in LANGUAGE_SWITCH_OFF..LANGUAGE_SWITCH_AUTO_APPLY) {
                languageSwitchCorrectionMode
            } else {
                LANGUAGE_SWITCH_OFF
            },
        // Read through both id spaces, which drops ids neither recognises, then bounded: a
        // stored file is not a trusted file, and a bar of four hundred buttons is a bar with no
        // buttons on it. Computed above, before this copy(...), so quickActions can be sanitised
        // against the same, final list -- see sanitisedCustomActions's own comment for why.
        quickActions =
            QuickActionBar.sanitisedIds(quickActions, sanitisedCustomQuickActions).take(MAX_QUICK_ACTIONS),
        customQuickActions = sanitisedCustomQuickActions,
        composerBar = ComposerBar.sanitisedIds(composerBar, sanitisedCustomActions),
        composerTextSize =
            if (composerTextSize in COMPOSER_TEXT_SIZE_SMALL..COMPOSER_TEXT_SIZE_LARGE) {
                composerTextSize
            } else {
                COMPOSER_TEXT_SIZE_MEDIUM
            },
        // Bounded on the way in as well as on the way out. These are written by the user, so
        // the file is as trustworthy as the rest of it -- which is to say bounded and read back
        // rather than trusted. Computed above, before this copy(...), so composerBar can be
        // sanitised against the same, final list.
        customActions = sanitisedCustomActions,
        emojiRecents = emojiRecents.filter { it.isNotEmpty() }.take(MAX_EMOJI_RECENTS),
        quickActionsMode = if (quickActionsMode in QUICK_ACTIONS_FULL..QUICK_ACTIONS_COLLAPSED) {
            quickActionsMode
        } else {
            QUICK_ACTIONS_COLLAPSED
        },
        quickActionsPlacement =
            if (quickActionsPlacement in QUICK_ACTIONS_ABOVE_STRIP..QUICK_ACTIONS_RIGHT) {
                quickActionsPlacement
            } else {
                QUICK_ACTIONS_ABOVE_STRIP
            },
        quickActionsSize =
            if (quickActionsSize in QUICK_ACTIONS_SIZE_DEFAULT..QUICK_ACTIONS_SIZE_HUGE) {
                quickActionsSize
            } else {
                QUICK_ACTIONS_SIZE_DEFAULT
            },
        )
    }

    val isOneHanded: Boolean
        get() = positionMode == MODE_ONE_HANDED_LEFT || positionMode == MODE_ONE_HANDED_RIGHT

    /**
     * The size/position values [isLandscape] selects -- portrait's own flat fields, packed into
     * the same shape [landscape] already is, or [landscape] itself. One place that answers
     * "which orientation reads which fields", read by the service placing the real keyboard and
     * by the settings screen previewing either tab.
     */
    fun placementFor(isLandscape: Boolean): KeyboardPlacement = if (isLandscape) {
        landscape
    } else {
        KeyboardPlacement(heightScale, widthScale, positionMode, bottomOffsetDp, horizontalOffsetDp)
    }

    /** [transform] applied to whichever orientation's placement [isLandscape] selects, written
     *  back to portrait's flat fields or to [landscape] -- the other half of [placementFor]. */
    fun withPlacement(
        isLandscape: Boolean,
        transform: (KeyboardPlacement) -> KeyboardPlacement,
    ): KeyboardPreferences {
        val updated = transform(placementFor(isLandscape))
        return if (isLandscape) {
            copy(landscape = updated)
        } else {
            copy(
                heightScale = updated.heightScale,
                widthScale = updated.widthScale,
                positionMode = updated.positionMode,
                bottomOffsetDp = updated.bottomOffsetDp,
                horizontalOffsetDp = updated.horizontalOffsetDp,
            )
        }
    }

    /**
     * Moves [isLandscape]'s placement to [mode], narrowing the keyboard the first time it
     * leaves the dock.
     *
     * Without the narrowing, choosing "one-handed" while the width is still 100% changes nothing
     * at all: the mode is set, the keyboard is pushed to a side it already fills, and the
     * feature reads as broken. So the first departure from the dock also picks a width that a
     * thumb can cross, and every later change is left alone -- a user who has already set 70% or
     * deliberately gone back to 100% keeps what they chose.
     */
    fun withPositionMode(mode: Int, isLandscape: Boolean = false): KeyboardPreferences =
        withPlacement(isLandscape) { placement ->
            // "Effectively full width" rather than exactly 1, because the resize handles leave
            // whatever the finger stopped at -- 0.99 after a drag to the edge is a keyboard the
            // user thinks is full width, and it should still narrow when they go one-handed.
            val narrowing = placement.positionMode == MODE_DOCKED && mode != MODE_DOCKED &&
                placement.widthScale >= NEARLY_FULL_WIDTH
            // The width survives the move. It is one value across every mode now that the resize
            // handles honour it in the dock as well, so docking is a change of position and
            // nothing else; a keyboard that is too narrow is widened by dragging its edge.
            placement.copy(
                positionMode = mode,
                widthScale = if (narrowing) ONE_HANDED_WIDTH_SCALE else placement.widthScale,
            )
        }

    companion object {
        /** Full width, flush with the bottom edge. What a keyboard normally is. */
        const val MODE_DOCKED = 0

        /**
         * Narrowed and pushed to one side, so every key is inside a thumb's arc.
         *
         * Left and right are separate modes rather than a handedness flag because people switch
         * hands: the setting is "where the keyboard is now", not "which hand you have".
         */
        const val MODE_ONE_HANDED_LEFT = 1
        const val MODE_ONE_HANDED_RIGHT = 2

        /** Lifted off the bottom edge and movable, for a large screen or a split view. */
        const val MODE_FLOATING = 3

        /**
         * Several repetitions before a word or phrase leads. For someone who writes about many
         * things and does not want the keyboard rearranged by one sentence.
         */
        const val LEARNING_CAUTIOUS = 0

        /** The default. A phrase written twice starts to lead. */
        const val LEARNING_BALANCED = 1

        /** The first time counts. For someone who writes the same things every day. */
        const val LEARNING_IMMEDIATE = 2

        /**
         * The multiplier each setting applies to how fast the personal model gains ground.
         *
         * One number rather than one per curve, so the setting means the same thing everywhere:
         * it scales both how quickly a word climbs and how many repetitions a phrase needs
         * before it leads. Two knobs that could disagree would be two knobs to explain.
         */
        /** Every dictionary is consulted for every word, whatever language the sentence is in. */
        const val LANGUAGE_LOCK_OFF = 0

        /** Waits for clear evidence -- roughly five or six telling words. */
        const val LANGUAGE_LOCK_PATIENT = 1

        /** Decides after about three words that belong to one language and no other. */
        const val LANGUAGE_LOCK_BALANCED = 2

        /** Decides on the first telling word. For someone who rarely mixes languages. */
        const val LANGUAGE_LOCK_QUICK = 3

        /**
         * Never offers a language that has not been identified.
         *
         * The others all consult every dictionary until they have decided. This one does not:
         * before anything is identified it uses the heaviest dictionary alone, and switches as
         * soon as the evidence says something else. Nothing is ever offered from a language
         * that has not been recognised in what is being written.
         */
        const val LANGUAGE_LOCK_STRICT = 4

        /** Corrections already applied are never revisited, whatever [languageLock] later
         *  decides about the sentence they were part of. */
        const val LANGUAGE_SWITCH_OFF = 0

        /** Offers an affected word back, without touching the field until it is tapped. */
        const val LANGUAGE_SWITCH_ASK = 1

        /** Edits an affected word immediately -- still one step on the field's own undo history. */
        const val LANGUAGE_SWITCH_AUTO_APPLY = 2

        /**
         * How much one-sided evidence the engine wants before it stops consulting the other
         * dictionaries, or a value at or below zero to never stop.
         *
         * Evidence is counted in words that exactly one active dictionary knows, aged by 0.85
         * per word written. The numbers are therefore roughly "how many telling words", not
         * "how many words" -- most of a sentence belongs to several dictionaries at once and
         * counts for neither.
         */
        /** The bar is drawn in full, always. */
        const val QUICK_ACTIONS_FULL = 0

        /**
         * One button stands in for the bar and opens it.
         *
         * The default, because the bar competes for height with the keys, and height is
         * accuracy. Opening it costs a tap; leaving it open costs a row on every screen.
         */
        const val QUICK_ACTIONS_COLLAPSED = 1

        /** Above the suggestion strip, spanning the keyboard. */
        const val QUICK_ACTIONS_ABOVE_STRIP = 0

        /** Below the keys, against the bottom edge. */
        const val QUICK_ACTIONS_BELOW_KEYS = 1

        /** A column down the left of the keys, for a thumb that lives on that side. */
        const val QUICK_ACTIONS_LEFT = 2

        /** A column down the right. */
        const val QUICK_ACTIONS_RIGHT = 3

        /** How many actions the bar will hold before it starts dropping them. */
        const val MAX_QUICK_ACTIONS = 10

        /** Today's bar, unchanged. */
        const val QUICK_ACTIONS_SIZE_DEFAULT = 0

        const val QUICK_ACTIONS_SIZE_SMALL = 1

        const val QUICK_ACTIONS_SIZE_MEDIUM = 2

        const val QUICK_ACTIONS_SIZE_HUGE = 3

        /** A model file name longer than any real one; a stored value past it is truncated. */
        const val MAX_MODEL_FILE_NAME_CHARS = 255

        const val COMPOSER_TEXT_SIZE_SMALL = 0
        const val COMPOSER_TEXT_SIZE_MEDIUM = 1
        const val COMPOSER_TEXT_SIZE_LARGE = 2

        /** Digits as a row above the symbols. */
        const val MODIFIER_ROW_ABOVE = 0
        const val MODIFIER_ROW_BELOW = 1

        const val SYMBOLS_NUMBER_TOP = 0

        /** Digits as a number pad down the left of the symbols. */
        const val SYMBOLS_NUMBER_LEFT = 1

        /** Digits as a number pad down the right of the symbols. */
        const val SYMBOLS_NUMBER_RIGHT = 2

        const val DEFAULT_LONG_PRESS_MILLIS = 380

        /** The keypress vibration classes -- see [hapticStrength]. [HAPTIC_SYSTEM] is last in
         *  the numbering only because the three explicit ones came first; it is the default. */
        const val HAPTIC_LIGHT = 0
        const val HAPTIC_MEDIUM = 1
        const val HAPTIC_STRONG = 2
        const val HAPTIC_SYSTEM = 3

        /** Follow the field: its action, unless it flagged Enter to stay a newline regardless. */
        const val ENTER_KEY_AUTO = 0

        /** The field's action, every time one exists -- ignoring a no-enter-action flag. */
        const val ENTER_KEY_FORCE_ACTION = 1

        /** A newline, every time, whatever the field asked for. */
        const val ENTER_KEY_FORCE_NEWLINE = 2

        /** Fast enough that a deliberate tap never trips it. */
        const val MIN_LONG_PRESS_MILLIS = 150

        /** Slow enough to be a wait, not so slow the key feels stuck. */
        const val MAX_LONG_PRESS_MILLIS = 700

        /** How many recent emoji are kept: a row and a half on most phones. */
        const val MAX_EMOJI_RECENTS = 24

        /**
         * How long unpinned entries are kept, as the values a slider steps through.
         *
         * Steps rather than a range, because the useful span is fifteen minutes to a month and
         * a linear slider over 43,200 values cannot be aimed: every pixel would be about two
         * hours at the top and the bottom third would be unreachable. These are the answers
         * someone actually has to "how long".
         */
        val RETENTION_STEPS: List<Int> = listOf(
            15, 30, 60, 4 * 60, 12 * 60, 24 * 60,
            3 * 24 * 60, 7 * 24 * 60, 14 * 24 * 60, 30 * 24 * 60,
        )

        /**
         * How many entries are kept, as the values a slider steps through.
         *
         * The same reasoning: the difference between 60 and 61 is nothing, and the difference
         * between 10 and 200 is the whole decision.
         */
        val HISTORY_SIZE_STEPS: List<Int> = listOf(10, 20, 30, 50, 75, 100, 150, 200, 500)

        /** The step nearest [value], for putting a stored number back on a slider. */
        fun nearestStep(steps: List<Int>, value: Int): Int {
            var best = 0
            for (index in steps.indices) {
                if (kotlin.math.abs(steps[index] - value) <
                    kotlin.math.abs(steps[best] - value)
                ) {
                    best = index
                }
            }
            return best
        }

        fun languageLockEvidence(lock: Int): Float = when (lock) {
            LANGUAGE_LOCK_OFF -> 0f
            LANGUAGE_LOCK_PATIENT -> 3.4f
            LANGUAGE_LOCK_QUICK, LANGUAGE_LOCK_STRICT -> 0.9f
            else -> 1.8f
        }

        /**
         * Whether an undecided detector falls back to one dictionary rather than to all of them.
         *
         * The difference between "wait until you know" and "never guess": every other setting
         * offers every language until it has decided, which is a sensible default and is
         * exactly what someone who writes one language does not want to see.
         */
        fun languageLockStrict(lock: Int): Boolean = lock == LANGUAGE_LOCK_STRICT

        fun learningSpeedFactor(speed: Int): Float = when (speed) {
            LEARNING_CAUTIOUS -> 0.35f
            LEARNING_IMMEDIATE -> 3f
            else -> 1f
        }

        /** How much [radialMenuSize] scales the ring's base radius. */
        fun radialSizeScale(size: Int): Float = when (size) {
            RADIAL_SIZE_SMALL -> 0.75f
            RADIAL_SIZE_LARGE -> 1.3f
            else -> 1f
        }

        /** Longest language code accepted from the stored file: `pt-BR` and friends fit easily. */
        const val MAX_LANGUAGE_TAG = 16

        /**
         * Below three the strip stops being a choice and becomes an announcement; above eight
         * the slots are narrower than a fingertip on any phone this runs on. [MAX_SUGGESTIONS]
         * doubles as the hard ceiling `SuggestionStripView`'s own fixed-size buffers are sized
         * for -- read from here rather than a second `8` typed in `:keyboard`, so a setting that
         * allowed more than the view can actually hold is a contradiction the compiler would
         * have to be told to create, not a number someone forgot to update twice.
         */
        const val MIN_SUGGESTIONS = 3
        const val MAX_SUGGESTIONS = 8
        const val DEFAULT_SUGGESTIONS = 3

        /** A lower ceiling than [MAX_SUGGESTIONS] -- see [radialSuggestionCount]'s own doc. */
        const val MIN_RADIAL_SUGGESTIONS = 3
        const val MAX_RADIAL_SUGGESTIONS = 6
        const val DEFAULT_RADIAL_SUGGESTIONS = 5

        /**
         * How long a real pause has to hold before the ring opens. Tunable, the same reason
         * [MIN_LONG_PRESS_MILLIS]/[MAX_LONG_PRESS_MILLIS] are: thumb speed and typing style vary
         * as much for this as they do for a long press. Stored in milliseconds -- what
         * [android.os.Handler.postDelayed] actually wants -- but every bound here is a clean
         * multiple of 100 on purpose: the settings screen shows and steps this in whole tenths
         * of a second, and a bound that did not land on that grid would make one end of the
         * slider unreachable.
         *
         * [MIN_RADIAL_PAUSE_DWELL_MILLIS] is deliberately `0`, not some small positive floor:
         * `postDelayed(runnable, 0)` still posts rather than running inline, but fires on the
         * very next looper pass, which in practice means the ring opens the moment
         * [radialMinPathLetters] is crossed rather than waiting for genuine stillness. That is
         * the bypass -- no separate on/off switch needed, since a value of zero already says it.
         */
        const val MIN_RADIAL_PAUSE_DWELL_MILLIS = 0
        const val MAX_RADIAL_PAUSE_DWELL_MILLIS = 2000
        const val DEFAULT_RADIAL_PAUSE_DWELL_MILLIS = 200

        /** The range [radialMinPathLetters] is clamped to -- `0` at the low end is a real,
         *  supported value (no minimum at all), not just a defensive floor. Half-letter steps:
         *  finer than that is not a distinction anyone steering a real thumb could feel. */
        const val MIN_RADIAL_MIN_PATH_LETTERS = 0f
        const val MAX_RADIAL_MIN_PATH_LETTERS = 3f
        const val DEFAULT_RADIAL_MIN_PATH_LETTERS = 1f

        /** How long the real menu waits before applying the top candidate on its own. Same
         *  "stored in milliseconds, shown in tenths of a second" shape as the pause dwell above
         *  -- these bounds already happened to be clean multiples of 100. */
        const val MIN_RADIAL_PICK_TIMEOUT_MILLIS = 500
        const val MAX_RADIAL_PICK_TIMEOUT_MILLIS = 3000
        const val DEFAULT_RADIAL_PICK_TIMEOUT_MILLIS = 1200

        /** [radialMenuAnchor] values. */
        const val RADIAL_ANCHOR_FINGER = 0
        const val RADIAL_ANCHOR_CENTER = 1
        const val RADIAL_ANCHOR_TANGENT_LEFT = 2
        const val RADIAL_ANCHOR_TANGENT_RIGHT = 3

        /** [radialMenuSize] values. */
        const val RADIAL_SIZE_SMALL = 0
        const val RADIAL_SIZE_MEDIUM = 1
        const val RADIAL_SIZE_LARGE = 2

        /** [radialTimeoutDefault] values. */
        /** [autoSpaceHabit] values. */
        const val AUTO_SPACE_SWALLOW_FIRST = 0
        const val AUTO_SPACE_SWALLOW_ALL = 1
        const val AUTO_SPACE_KEEP = 2

        /** [correctionDistance] values. */
        const val CORRECTION_DISTANCE_STRICT = 0
        const val CORRECTION_DISTANCE_NORMAL = 1
        const val CORRECTION_DISTANCE_LOOSE = 2

        const val RADIAL_TIMEOUT_APPLY_TOP = 0
        const val RADIAL_TIMEOUT_CANCEL = 1

        /** [radialTrustedWord] values. */
        const val RADIAL_TRUSTED_CHIP = 0
        const val RADIAL_TRUSTED_AUTO_APPLY = 1

        /** The range [minCorrectionLength] is clamped to. */
        const val MIN_CORRECTION_LENGTH = 1
        const val MAX_CORRECTION_LENGTH = 5

        /** The range [correctionStrictness] is clamped to, and the value "Reset" restores. */
        const val MIN_CORRECTION_STRICTNESS = 0.5f
        const val MAX_CORRECTION_STRICTNESS = 2.0f
        const val DEFAULT_CORRECTION_STRICTNESS = 1.0f

        /** The range [assistTemperature] is clamped to, and the value "Reset" restores. */
        const val MIN_ASSIST_TEMPERATURE = 0.1f
        const val MAX_ASSIST_TEMPERATURE = 1.5f
        const val DEFAULT_ASSIST_TEMPERATURE = 0.3f

        /** The range [assistTopP] is clamped to, and the value "Reset" restores. */
        const val MIN_ASSIST_TOP_P = 0.1f
        const val MAX_ASSIST_TOP_P = 1.0f
        const val DEFAULT_ASSIST_TOP_P = 0.9f

        /** [themeMode] values. */
        const val THEME_MODE_MANUAL = 0
        const val THEME_MODE_AUTO_SYSTEM = 1

        const val MIN_HEIGHT_SCALE = 0.65f
        const val MAX_HEIGHT_SCALE = 1.6f
        const val MIN_WIDTH_SCALE = 0.55f

        /**
         * The width the keyboard takes the first time it leaves the dock.
         *
         * Roughly a thumb's reach across a 6-inch phone held in one hand: narrow enough that the
         * far column is reachable, wide enough that the keys do not shrink below the touch
         * target the hit-testing assumes.
         */
        const val ONE_HANDED_WIDTH_SCALE = 0.82f

        /** Close enough to the full width that the user means the full width. */
        const val NEARLY_FULL_WIDTH = 0.98f
        const val MAX_BOTTOM_OFFSET_DP = 220f

        /** A floating keyboard's own reach either side of centre, in dp. */
        const val MIN_HORIZONTAL_OFFSET_DP = -160f
        const val MAX_HORIZONTAL_OFFSET_DP = 160f
    }
}

object KeyboardPreferencesSerializer : Serializer<KeyboardPreferences> {

    private val json = PERSISTED_JSON

    override val defaultValue: KeyboardPreferences = KeyboardPreferences()

    override suspend fun readFrom(input: InputStream): KeyboardPreferences {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) {
            return defaultValue
        }
        return try {
            json.decodeFromString(KeyboardPreferences.serializer(), bytes.decodeToString())
                .sanitised()
        } catch (error: SerializationException) {
            throw CorruptionException("the keyboard preferences file could not be parsed", error)
        } catch (error: IllegalArgumentException) {
            throw CorruptionException("the keyboard preferences file is not valid UTF-8", error)
        }
    }

    override suspend fun writeTo(t: KeyboardPreferences, output: OutputStream) {
        output.write(json.encodeToString(KeyboardPreferences.serializer(), t).encodeToByteArray())
    }
}
