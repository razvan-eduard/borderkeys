# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""The word list as the corpus generators read it: `word<TAB>count[<TAB>name]` in frequency order.

    words = corpus_words.load("dictionaries/en_US.tsv")
    words.ordinary(from_top=4000, min_length=5, max_length=9)   # lower-case letters, not a name
    words.is_word("dont")                                      # on the folded key the pack uses
"""

import importlib.util
import pathlib


def _load_fold():
    """build_dict.py's own fold, so membership here means membership in the compiled pack."""
    path = pathlib.Path(__file__).with_name("build_dict.py")
    spec = importlib.util.spec_from_file_location("build_dict", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.fold_word


class Words:
    def __init__(self, rows, fold):
        self.rows = rows
        self.fold = fold
        self.known = {fold(word) for word, _name in rows}

    def ordinary(self, from_top=None, min_length=1, max_length=None):
        """The words of lower-case letters that are not names, in frequency order."""
        picked = []
        for word, name in self.rows[:from_top]:
            if name or not word.isalpha() or not word.islower() or len(word) < min_length:
                continue
            if max_length is not None and len(word) > max_length:
                continue
            picked.append(word)
        return picked

    def is_word(self, text):
        return self.fold(text) in self.known


def load(path):
    rows = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                continue
            try:
                int(parts[1])
            except ValueError:
                continue
            rows.append((parts[0], len(parts) >= 3 and parts[2] == "name"))
    return Words(rows, _load_fold())
