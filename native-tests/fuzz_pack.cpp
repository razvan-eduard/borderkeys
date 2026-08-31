// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include <fcntl.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

#include "bkd_format.hpp"
#include "engine.hpp"

// libFuzzer entry point for the language-pack loader, for runs longer than the test suite.
//
// The header's checksums are repaired from the fuzzer's input rather than expected to be
// correct. A fuzzer that has to guess a CRC-32 spends its whole budget guessing a CRC-32; with
// them repaired, every byte it changes lands on a field that is actually interpreted.
//
// Built only with clang and -DBORDERKEYS_FUZZ=ON. `pack_corpus_test` covers the same ground
// deterministically in the ordinary test run, so an ordinary build is not missing a gate.

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    using namespace borderkeys;

    if (size < sizeof(BkdHeader) || size > kMaxPackBytes) {
        return 0;
    }
    std::string bytes(reinterpret_cast<const char*>(data), size);

    BkdHeader header;
    std::memcpy(&header, bytes.data(), sizeof(header));
    header.magic = kBkdMagic;
    header.formatVersion = kBkdVersion;
    header.headerBytes = sizeof(BkdHeader);
    header.fileBytes = bytes.size();
    header.contentCrc32 =
        crc32(bytes.data() + sizeof(BkdHeader), bytes.size() - sizeof(BkdHeader));
    header.headerCrc32 = 0;
    BkdHeader zeroed = header;
    header.headerCrc32 = crc32(&zeroed, sizeof(zeroed));
    std::memcpy(&bytes[0], &header, sizeof(header));

    char path[] = "/tmp/borderkeys_fuzz_XXXXXX";
    const int fd = mkstemp(path);
    if (fd < 0) {
        return 0;
    }
    if (write(fd, bytes.data(), bytes.size()) != static_cast<ssize_t>(bytes.size())) {
        close(fd);
        unlink(path);
        return 0;
    }
    lseek(fd, 0, SEEK_SET);

    Engine engine;
    if (engine.create()) {
        if (engine.loadLanguage("f", fd, 0, static_cast<int64_t>(bytes.size()), 1.0f) == kBkdOk) {
            const char* tags[1] = {"f"};
            const float weights[1] = {1.0f};
            engine.setActiveLanguages(tags, weights, 1);
            Candidate out[Engine::kMaxCandidates];
            const char* probes[] = {"a", "abc", "the", "zzzz", ""};
            for (const char* probe : probes) {
                const int found = engine.suggest(probe, std::strlen(probe), "the", 3, nullptr, 0,
                                                 out, Engine::kMaxCandidates);
                for (int i = 0; i < found; ++i) {
                    uint32_t length = 0;
                    const char* const text = engine.candidateText(out[i], &length);
                    volatile char sink = 0;
                    for (uint32_t k = 0; text != nullptr && k < length; ++k) {
                        sink = static_cast<volatile char>(text[k]);
                    }
                    (void)sink;
                }
            }
        }
        engine.destroy();
    }

    close(fd);
    unlink(path);
    return 0;
}
