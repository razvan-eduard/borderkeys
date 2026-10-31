// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_ROWS_FILE_HPP
#define BORDERKEYS_ROWS_FILE_HPP

#include <cstdio>
#include <initializer_list>
#include <string>

namespace borderkeys_eval {

/** The places a rank counts as the top three. */
constexpr int kTopThree = 3;

/** Whether a zero-based [rank], -1 for a word not offered, is among the first kTopThree. */
inline bool rankInTopThree(int rank) { return rank >= 0 && rank < kTopThree; }

/** What a reading writes for a zero-based [rank]: the place from 1, or `absent` for -1. */
inline std::string placeOf(int rank) { return rank < 0 ? std::string("absent") : std::to_string(rank + 1); }

/**
 * A rows file for scripts/compare_readings.py, as the measuring tools write one with `--rows`:
 * the measures line, the fields line when there are fields, then per row one verdict per
 * measure, the key, the fields and the reading, tab-separated. A place in a reading counts from
 * 1 ([placeOf]). With no file, nothing.
 */
class RowsFile {
public:
    explicit RowsFile(FILE* file) : file_(file) {}

    void measures(std::initializer_list<const char*> names) const {
        if (file_ == nullptr) {
            return;
        }
        std::fputs("# measures:", file_);
        for (const char* name : names) {
            std::fprintf(file_, " %s", name);
        }
        std::fputc('\n', file_);
    }

    /** The names of the fields each row carries after its key; written after [measures]. */
    void fields(std::initializer_list<const char*> names) const {
        if (file_ == nullptr) {
            return;
        }
        std::fputs("# fields:", file_);
        for (const char* name : names) {
            std::fprintf(file_, " %s", name);
        }
        std::fputc('\n', file_);
    }

    /** One row; [fields], when not empty, is its fields' values, tab-separated. */
    void row(std::initializer_list<bool> verdicts, const std::string& key,
             const std::string& reading, const std::string& fields = std::string()) const {
        if (file_ == nullptr) {
            return;
        }
        for (const bool verdict : verdicts) {
            std::fputs(verdict ? "1\t" : "0\t", file_);
        }
        std::fprintf(file_, "%s\t", key.c_str());
        if (!fields.empty()) {
            std::fprintf(file_, "%s\t", fields.c_str());
        }
        std::fprintf(file_, "%s\n", reading.c_str());
    }

    bool writes() const { return file_ != nullptr; }

private:
    FILE* file_;
};

}  // namespace borderkeys_eval

#endif  // BORDERKEYS_ROWS_FILE_HPP
