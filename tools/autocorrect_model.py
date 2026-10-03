#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""
The autocorrect decision and the strip's ranking, computed from their rules alone, to check
the engine against. Standard library only.

Packs are read from `suggest_eval <dict dir> --words <tag>` dumps: word, unigram log-probability
in nats, name flag. Keys are the phone QWERTY of native-tests/test_support.hpp unless a
`.layout` file is given.

    autocorrect_model.py --pack en-US=words_en.tsv --autocorrect corpus.tsv
    autocorrect_model.py --pack en-US=words_en.tsv corpus.tsv
    autocorrect_model.py --pack en-US=words_en.tsv --explain loke

The autocorrect form prints what a delimiter commits per case, as `suggest_eval --autocorrect`
does; the bare form prints where the strip ranks the expected word; `--explain` prints the
decision's steps and the strip for one typed word.

The decision, first step that decides wins:
  1. a word a pack spells, case aside, is left alone;
  2. typed longer than the minimum length and a length difference of 1 or more: the most
     frequent pack word one letter of a doubled pair shorter wins;
  3. an English pack holding an inflection stem of 4 letters or more leaves the word alone;
  4. a possessive (last apostrophe at index 2 or later, suffix "s" or nothing): a known base
     leaves it alone, else the base alone is decided and the suffix put back;
  5. shorter than the minimum length: left alone;
  6. the sweep, over every pack word whose first `prefix` letters are the typed ones and whose
     length differs by at most the length difference: a same-length adjacent swap scores
     1 - 0.15 / length; any other same-length word with at least half its letters in place and
     at most two substitutions scores the mean of 1 - key distance per letter; a word of another
     length scores 1 - d / longer length when its key-weighted edit distance d is at most its
     length difference + 0.5. Below the threshold it drops. The best wins when it leads every
     other by more than 0.10; otherwise, within 0.10 of the best, a swap removes the
     two-substitution candidates, then the most frequent wins, then the best score, then the
     first in code point order;
  7. the winner must reach the frequency floor;
  8. typed all in capitals: the word in capitals; a first capital: the word with one.

Key distance is the distance between the two keys over the farthest pair of letter keys; an
accented letter sits on its base letter's key, and a letter on no key is 1.0 from any other.

Frequency: rank = int((1 - ln(p * 1e8 + 1) / ln(p_max * 1e8 + 1)) * 255), p the word's
probability and p_max the pack's highest; frequency = 1,000,000 - 3,900 * rank.

The strip: every word starting with the typed letters, then, for 3 to 24 typed letters, all
letters, with fewer than 5 such words or the best of them less frequent than the word at the top
5% of its pack, the typo search: key-weighted optimal string alignment (same letter 0, accent
variant 0.25, neighbouring key 0.5, adjacent swap 0.6, anything else 1.0), a word's cost the
least over its prefixes within the typed length's budget (3: 0.6, 4: 1.0, 5-7: 1.5, 8-24: 2.0),
its first 4 letters within 1.0 and its first 5-7 within 1.5. The best 48 by penalised prefix
score times frequency factor are scored. Prefix score: the typed word 1000; a completion
800 + 50 * typed length - max(0, (length - 6) * 10); a typo candidate 1000 when the whole word is
within the budget at its cost, else the completion score, times 1 - 0.25 * cost. Score = prefix
* (1 + ln1p(frequency / 100)) * pack weight over the largest. Sorted by score, words starting
with the typed letters first on ties; the top 5, and when the typo search ran and fewer than two
of its words made it, the next best of them take the lowest places, re-sorted.
"""

import argparse
import math
import sys
import unicodedata

EXACT_RATIO = 0.5
MAX_SUBSTITUTIONS = 2
SWAP_PENALTY = 0.15
LENGTH_BUDGET = 0.5
BAND = 0.10
FLOOR_LOW = 100
FLOOR_HIGH = 2000
FLOOR_SHARE = 0.6

ACCENT_COST = 0.25
NEIGHBOUR_COST = 0.5
SWAP_COST = 0.6
EDIT_COST = 1.0
NEIGHBOUR_PITCHES = 1.5
TYPO_MIN = 3
TYPO_MAX = 24
STRONG_SHARE = 0.05
STRONG_COUNT = 5
POOL = 48
SHOWN = 5
RESERVED = 2
TYPO_PENALTY = 0.25
FREQUENCY_SCALE = 100.0


def base_letter(c):
    """The letter without its marks: é is e, ț is t."""
    decomposed = unicodedata.normalize("NFD", c)
    stripped = "".join(ch for ch in decomposed if not unicodedata.combining(ch))
    return stripped[:1] or c


class Keys:
    """Key centres by letter, the farthest pair and the neighbour radius."""

    def __init__(self, layout_path=None):
        self.centres = {}
        if layout_path is None:
            rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
            indent = [0.0, 0.5, 1.5]
            for r, row in enumerate(rows):
                for i, c in enumerate(row):
                    self.centres[c] = ((indent[r] + i + 0.5) * 108.0, (r + 0.5) * 160.0)
        else:
            self.centres = read_layout(layout_path)
        points = list(self.centres.values())
        self.farthest = max(
            (math.dist(a, b) for i, a in enumerate(points) for b in points[i + 1:]), default=1.0
        )
        nearest = sorted(
            min(math.dist(a, b) for j, b in enumerate(points) if j != i)
            for i, a in enumerate(points)
        )
        pitch = nearest[len(nearest) // 2]
        self.radius = NEIGHBOUR_PITCHES * pitch / self.farthest
        self.cache = {}

    def centre(self, c):
        c = c.lower()
        if c in self.centres:
            return self.centres[c]
        return self.centres.get(base_letter(c))

    def distance(self, a, b):
        """0 for the same letter, else the key distance over the farthest pair, 1.0 off the keys."""
        if a == b:
            return 0.0
        key = (a, b)
        found = self.cache.get(key)
        if found is not None:
            return found
        pa, pb = self.centre(a), self.centre(b)
        value = 1.0 if pa is None or pb is None else min(1.0, math.dist(pa, pb) / self.farthest)
        self.cache[key] = value
        return value

    def neighbours(self, a, b):
        return a.lower() != b.lower() and self.distance(a, b) <= self.radius


def read_layout(path):
    """A native-tests `.layout` file: `code x y` per key, letters only kept."""
    centres = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.split()
            if len(parts) < 3 or line.startswith("#"):
                continue
            try:
                code = int(parts[0])
                x, y = float(parts[1]), float(parts[2])
            except ValueError:
                continue
            c = chr(code)
            if c.isalpha():
                centres[c.lower()] = (x, y)
    return centres


class Pack:
    """One pack's words by lowercase spelling, with frequency and name flag."""

    def __init__(self, tag, path, weight):
        self.tag = tag
        self.weight = weight
        self.words = {}
        entries = []
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 3:
                    continue
                entries.append((parts[0], float(parts[1]), parts[2] == "1"))
        top = max(math.exp(log_prob) for _, log_prob, _ in entries)
        denominator = math.log(top * 1e8 + 1)
        for text, log_prob, name in entries:
            p = math.exp(log_prob)
            rank = int((1 - math.log(p * 1e8 + 1) / denominator) * 255)
            rank = min(255, max(0, rank))
            frequency = 1_000_000 - 3_900 * rank
            key = text.lower()
            held = self.words.get(key)
            if held is None or frequency > held[1]:
                self.words[key] = (text, frequency, name)
        ordered = sorted((f for _, f, _ in self.words.values()), reverse=True)
        self.strong = ordered[int(len(ordered) * STRONG_SHARE)] if ordered else 0
        self.highest = ordered[0] if ordered else 0
        self.english = tag.lower().startswith("en")


class Trie:
    """The lowercase spellings of every pack, each terminal carrying its pack entries."""

    def __init__(self, packs):
        self.root = {}
        for index, pack in enumerate(packs):
            for key in pack.words:
                node = self.root
                for c in key:
                    node = node.setdefault(c, {})
                node.setdefault(None, []).append(index)

    def node(self, text):
        node = self.root
        for c in text:
            node = node.get(c)
            if node is None:
                return None
        return node


class Model:
    def __init__(self, packs, keys, settings):
        self.packs = packs
        self.keys = keys
        self.settings = settings
        self.trie = Trie(packs)
        self.largest = max(p.weight for p in packs)

    # ----- what the packs hold ---------------------------------------------------------------

    def entry(self, key):
        """The most frequent pack entry spelled `key`, case aside: (text, frequency, pack)."""
        best = None
        for pack in self.packs:
            held = pack.words.get(key)
            if held is not None and (best is None or held[1] > best[1]):
                best = (held[0], held[1], pack)
        return best

    def known(self, word):
        return self.entry(word.lower()) is not None

    # ----- the decision ----------------------------------------------------------------------

    def decide(self, typed, trace=None):
        """What a delimiter commits for `typed`, or None to leave it; `trace` collects the steps."""
        note = trace.append if trace is not None else (lambda _: None)
        lower = typed.lower()
        s = self.settings
        if not lower:
            return None
        if self.known(lower):
            note("known word")
            return None
        if len(lower) > s.min_length and s.length_difference >= 1:
            best = None
            for i in range(len(lower) - 1):
                if lower[i] != lower[i + 1] or not lower[i].isalpha():
                    continue
                collapsed = lower[:i] + lower[i + 1:]
                held = self.entry(collapsed)
                if held is not None and (best is None or held[1] > best[1]):
                    best = held
            if best is not None:
                note(f"doubled letter: {best[0]}")
                return cased(typed, best[0])
        for pack in self.packs:
            if pack.english and any(len(stem) >= 4 and stem in pack.words for stem in stems(lower)):
                note("inflection of a known word")
                return None
        apostrophe = max(lower.rfind("'"), lower.rfind("’"))
        if apostrophe >= 2:
            suffix = lower[apostrophe + 1:]
            if suffix in ("s", ""):
                base = typed[:apostrophe]
                if self.known(base):
                    note("possessive of a known word")
                    return None
                corrected = self.decide(base, trace)
                if corrected is None:
                    note("possessive, base left alone")
                    return None
                note(f"possessive: {corrected}")
                return corrected + typed[apostrophe:]
        if len(lower) < s.min_length:
            note("shorter than the minimum length")
            return None
        winner = self.sweep(lower, trace)
        if winner is None:
            note("nothing reached the threshold")
            return None
        text, score, frequency, pack, _ = winner
        floor = floor_for(s.min_frequency, pack.highest)
        if frequency < floor:
            note(f"{text} below the frequency floor {floor}")
            return None
        note(f"sweep: {text} at {score:.3f}")
        return cased(typed, text)

    def sweep(self, lower, trace=None):
        s = self.settings
        n = len(lower)
        prefix = lower[: min(s.prefix, n)]
        found = {}

        def offer(key, score, kind):
            held = self.entry(key)
            if held is None or score < s.threshold:
                return
            current = found.get(key)
            if current is None or score > current[1]:
                found[key] = (held[0], score, held[1], held[2], kind)

        # Same length: at most two substitutions, a swap counting as two.
        def same(node, depth, word, mismatches):
            if depth == n:
                if None in node:
                    key = "".join(word)
                    if is_swap(lower, key):
                        offer(key, 1 - SWAP_PENALTY / n, "swap")
                    else:
                        exact = n - mismatches
                        if exact / n >= EXACT_RATIO and mismatches <= MAX_SUBSTITUTIONS:
                            total = sum(1 - self.keys.distance(a, b) for a, b in zip(lower, key))
                            offer(key, total / n, "several" if mismatches >= 2 else "one")
                return
            for c, child in node.items():
                if c is None:
                    continue
                if depth < len(prefix) and c != prefix[depth]:
                    continue
                more = mismatches + (c != lower[depth])
                if more > MAX_SUBSTITUTIONS:
                    continue
                word.append(c)
                same(child, depth + 1, word, more)
                word.pop()

        same(self.trie.root, 0, [], 0)

        # Other lengths: key-weighted edit distance, abandoned past the widest budget.
        if s.length_difference >= 1:
            widest = s.length_difference + LENGTH_BUDGET
            first = [float(j) for j in range(n + 1)]

            def other(node, depth, word, row):
                if None in node and depth != n and abs(depth - n) <= s.length_difference:
                    distance = row[n]
                    if distance <= abs(depth - n) + LENGTH_BUDGET:
                        key = "".join(word)
                        offer(key, max(0.0, 1 - distance / max(n, depth)), "length")
                if depth >= n + s.length_difference:
                    return
                for c, child in node.items():
                    if c is None:
                        continue
                    if depth < len(prefix) and c != prefix[depth]:
                        continue
                    nxt = [row[0] + 1.0]
                    for j in range(1, n + 1):
                        nxt.append(min(
                            row[j - 1] + self.keys.distance(lower[j - 1], c),
                            nxt[j - 1] + 1.0,
                            row[j] + 1.0,
                        ))
                    if min(nxt) > widest:
                        continue
                    word.append(c)
                    other(child, depth + 1, word, nxt)
                    word.pop()

            other(self.trie.root, 0, [], first)

        if not found:
            return None
        ranked = sorted(found.values(), key=lambda e: -e[1])
        best = ranked[0][1]
        band = [e for e in ranked if e[1] >= best - BAND - 1e-9]
        if trace is not None:
            shown = ", ".join(f"{e[0]} {e[1]:.3f} f{e[2]}" for e in band[:8])
            trace.append(f"band of {len(band)}: {shown}")
        if len(band) == 1:
            return band[0]
        if any(e[4] == "swap" for e in band):
            band = [e for e in band if e[4] != "several"]
        band.sort(key=lambda e: (-e[2], -e[1], e[0]))
        return band[0]

    # ----- the strip -------------------------------------------------------------------------

    def strip(self, typed):
        lower = typed.lower()
        n = len(lower)
        entries = {}

        def factor(pack):
            return pack.weight / self.largest

        node = self.trie.node(lower) if lower else None
        exact = []
        if node is not None:
            stack = [(node, lower)]
            while stack:
                here, text = stack.pop()
                for c, child in here.items():
                    if c is None:
                        exact.append(text)
                    else:
                        stack.append((child, text + c))
        for key in exact:
            held = self.entry(key)
            prefix = 1000.0 if key == lower else completion(n, len(key))
            score = prefix * (1 + math.log1p(held[1] / FREQUENCY_SCALE)) * factor(held[2])
            entries[key] = (held[0], score, False)

        budget = budget_for(n)
        ran = False
        if budget > 0 and lower.isalpha():
            best_exact = max((self.entry(k)[1] for k in exact), default=0)
            strong = max(p.strong for p in self.packs)
            if len(exact) < STRONG_COUNT or best_exact < strong:
                ran = True
        typo = []
        if ran:
            costs = self.typo_search(lower, budget)
            pool = []
            for key, cost in costs.items():
                if key in entries:
                    continue
                held = self.entry(key)
                whole = abs(len(key) - n) <= budget and self.osa(lower, key) <= cost + 1e-9
                prefix = (1000.0 if whole else completion(n, len(key))) * (1 - TYPO_PENALTY * cost)
                rough = prefix * (1 + math.log1p(held[1] / FREQUENCY_SCALE))
                pool.append((rough, key, prefix, held))
            pool.sort(key=lambda e: (-e[0], e[1]))
            for _, key, prefix, held in pool[:POOL]:
                score = prefix * (1 + math.log1p(held[1] / FREQUENCY_SCALE)) * factor(held[2])
                typo.append((held[0], score, True))

        ordered = sorted(
            list(entries.values()) + typo, key=lambda e: (-e[1], e[2], e[0])
        )
        top = ordered[:SHOWN]
        if ran:
            shown_typo = sum(1 for e in top if e[2])
            if shown_typo < RESERVED:
                spare = [e for e in ordered[SHOWN:] if e[2]][: RESERVED - shown_typo]
                for e in spare:
                    for i in range(len(top) - 1, -1, -1):
                        if not top[i][2]:
                            top[i] = e
                            break
                top.sort(key=lambda e: (-e[1], e[2], e[0]))
        return top

    def substitution(self, typed_c, word_c):
        if typed_c == word_c:
            return 0.0
        a, b = typed_c.lower(), word_c.lower()
        if a == b:
            return 0.0
        if not (a.isascii() and b.isascii()) and base_letter(a) == base_letter(b):
            return ACCENT_COST
        return NEIGHBOUR_COST if self.keys.neighbours(a, b) else EDIT_COST

    def next_row(self, typed, path, rows):
        """The alignment row for the word letters in `path` against every typed prefix."""
        n = len(typed)
        d = len(path)
        prev = rows[d - 1]
        cd = path[d - 1]
        row = [d * EDIT_COST]
        for j in range(1, n + 1):
            v = min(prev[j - 1] + self.substitution(typed[j - 1], cd), prev[j] + EDIT_COST,
                    row[j - 1] + EDIT_COST)
            if d >= 2 and j >= 2 and cd == typed[j - 2] and path[d - 2] == typed[j - 1] \
                    and cd != typed[j - 1]:
                v = min(v, rows[d - 2][j - 2] + SWAP_COST)
            row.append(v)
        return row

    def osa(self, typed, word):
        rows = [[j * EDIT_COST for j in range(len(typed) + 1)]]
        for d in range(1, len(word) + 1):
            rows.append(self.next_row(typed, word[:d], rows))
        return rows[-1][len(typed)]

    def typo_search(self, typed, budget):
        """Word -> least prefix cost within the budget and the depth budgets."""
        n = len(typed)
        out = {}
        max_depth = n + int(budget / EDIT_COST) + 1
        rows = [[j * EDIT_COST for j in range(n + 1)]]
        path = []

        def bound(d):
            here = min(rows[d])
            return min(here, min(rows[d - 1]) + SWAP_COST) if d >= 2 else here

        def collect(node, text, cost):
            stack = [(node, text)]
            while stack:
                here, t = stack.pop()
                for c, child in here.items():
                    if c is None:
                        if cost < out.get(t, math.inf):
                            out[t] = cost
                    else:
                        stack.append((child, t + c))

        def descend(node, best):
            d = len(path)
            if d >= max_depth:
                return
            for c, child in node.items():
                if c is None:
                    continue
                path.append(c)
                rows.append(self.next_row(typed, path, rows))
                low = bound(d + 1)
                if low <= depth_budget(d + 1, n):
                    cost = rows[d + 1][n]
                    here_best = min(best, cost)
                    if cost <= budget and cost <= low + 1e-9:
                        collect(child, "".join(path), here_best)
                    else:
                        if None in child and here_best <= budget:
                            key = "".join(path)
                            if here_best < out.get(key, math.inf):
                                out[key] = here_best
                        descend(child, here_best)
                rows.pop()
                path.pop()

        descend(self.trie.root, math.inf)
        return out


def stems(word):
    """The English inflection stems of `word`, two letters or more."""
    w = word.lower()
    if len(w) < 3:
        return []
    out = []

    def add(stem):
        if stem not in out:
            out.append(stem)

    if w.endswith("ies") and len(w) >= 4:
        add(w[:-3] + "y")
    if w.endswith("es"):
        add(w[:-2])
    if w.endswith("s") and not w.endswith("ss"):
        add(w[:-1])
    if w.endswith("ied") and len(w) >= 4:
        add(w[:-3] + "y")
    if w.endswith("ed"):
        add(w[:-2])
        add(w[:-1])
    if w.endswith("ing") and len(w) >= 4:
        add(w[:-3])
        add(w[:-3] + "e")
    if w.endswith("est") and len(w) >= 4:
        add(w[:-3])
        add(w[:-2])
    if w.endswith("er"):
        add(w[:-2])
        add(w[:-1])
    if w.endswith("ily") and len(w) >= 4:
        add(w[:-3] + "y")
    if w.endswith("ly"):
        add(w[:-2])
    return [s for s in out if len(s) >= 2]


def is_swap(a, b):
    """Whether b is a with two adjacent, different letters swapped and nothing else changed."""
    if len(a) != len(b) or len(a) < 2:
        return False
    i = 0
    while i < len(a) and a[i] == b[i]:
        i += 1
    if i >= len(a) - 1:
        return False
    if a[i] != b[i + 1] or a[i + 1] != b[i] or a[i] == a[i + 1]:
        return False
    return a[i + 2:] == b[i + 2:]


def floor_for(setting, highest):
    if highest <= 0:
        return 0
    share = min(1.0, max(0.0, (setting - FLOOR_LOW) / (FLOOR_HIGH - FLOOR_LOW)))
    return int(share * FLOOR_SHARE * highest)


def cased(typed, word):
    letters = [c for c in typed if c.isalpha()]
    if letters and all(c.isupper() for c in letters):
        return word.upper()
    if typed[:1].isupper():
        return word[:1].upper() + word[1:]
    return word


def completion(typed_length, word_length):
    return 800.0 + 50.0 * typed_length - max(0, (word_length - 6) * 10)


def budget_for(n):
    if n < TYPO_MIN or n > TYPO_MAX:
        return 0.0
    if n == 3:
        return 0.6
    if n == 4:
        return 1.0
    if n <= 7:
        return 1.5
    return 2.0


def depth_budget(depth, n):
    return min(budget_for(n), budget_for(min(max(depth, 4), n)))


def read_corpus(path):
    cases = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 2:
                cases.append((parts[0], parts[1]))
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--pack", action="append", required=True,
                        help="tag=dump.tsv[:weight], the first pack leading")
    parser.add_argument("--layout", help="a native-tests .layout file; the test QWERTY otherwise")
    parser.add_argument("--autocorrect", metavar="CORPUS")
    parser.add_argument("--explain", metavar="TYPED")
    parser.add_argument("corpus", nargs="?")
    parser.add_argument("--min-length", type=int, default=2)
    parser.add_argument("--threshold", type=float, default=0.65)
    parser.add_argument("--length-difference", type=int, default=2)
    parser.add_argument("--prefix", type=int, default=0)
    parser.add_argument("--min-frequency", type=int, default=100)
    args = parser.parse_args()

    packs = []
    for spec in args.pack:
        tag, _, rest = spec.partition("=")
        path, _, weight = rest.partition(":")
        packs.append(Pack(tag, path, float(weight) if weight else 1.0))
    model = Model(packs, Keys(args.layout), args)

    if args.explain is not None:
        trace = []
        committed = model.decide(args.explain, trace)
        for step in trace:
            print(f"  {step}")
        print(f"autocorrect commits {committed if committed else '(nothing)'}")
        for text, score, typo in model.strip(args.explain):
            print(f"  {text:<20} {score:12.1f} {'typo' if typo else ''}")
        return 0

    if args.autocorrect is not None:
        cases = read_corpus(args.autocorrect)
        right = wrong = none = 0
        print(f"{'typed':<18} {'expected':<18} autocorrect commits")
        for typed, expected in cases:
            committed = model.decide(typed)
            if committed is None:
                none += 1
            elif committed == expected:
                right += 1
            else:
                wrong += 1
            print(f"{typed:<18} {expected:<18} {committed if committed else '(nothing)'}")
        total = len(cases) or 1
        print()
        print(f"cases            {len(cases)}")
        print(f"commits expected {right}  ({100.0 * right / total:.1f}%)")
        print(f"commits other    {wrong}  ({100.0 * wrong / total:.1f}%)")
        print(f"commits nothing  {none}  ({100.0 * none / total:.1f}%)")
        return 0

    if args.corpus is None:
        parser.error("a corpus, --autocorrect or --explain is needed")
    cases = read_corpus(args.corpus)
    first = three = 0
    print(f"{'typed':<18} {'expected':<18} rank")
    for typed, expected in cases:
        shown = [text for text, _, _ in model.strip(typed)]
        rank = shown.index(expected) if expected in shown else -1
        first += rank == 0
        three += 0 <= rank < 3
        print(f"{typed:<18} {expected:<18} {rank + 1 if rank >= 0 else '-'}")
    total = len(cases) or 1
    print()
    print(f"cases     {len(cases)}")
    print(f"first     {first}  ({100.0 * first / total:.1f}%)")
    print(f"top three {three}  ({100.0 * three / total:.1f}%)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
