// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors


#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <jni.h>
#include <limits>
#include <memory>
#include <new>

#include "engine.hpp"
#include "jni_slots.hpp"

// The JNI bridge, the only file in this library that uses JNI. Methods are registered in
// JNI_OnLoad; errors cross as return values. On the typing path nothing allocates per call but
// the result strings, the personal model's growth, and a context read past kContextLinksOnStack
// links; the bulk loaders, run at start, copy their rows. Runs on the prediction thread.

namespace {

using borderkeys::Candidate;
using borderkeys::Engine;
using namespace borderkeys::bridge;

Engine* engineFrom(jlong handle) {
    return reinterpret_cast<Engine*>(static_cast<intptr_t>(handle));
}

// Copies a Java string, at most maxUnits long, into a buffer. Returns the byte length, or -1 when
// the string is null or too long.
jsize copyString(JNIEnv* env, jstring value, char* buffer, jsize bufferBytes,
                 jsize maxUnits = kMaxStringUnits) {
    if (value == nullptr) {
        return -1;
    }
    const jsize units = env->GetStringLength(value);
    // The byte count comes from GetStringUTFLength; GetStringUTFRegion does not terminate.
    const jsize bytes = env->GetStringUTFLength(value);
    if (units <= 0 || units > maxUnits || bytes <= 0 || bytes + 1 > bufferBytes) {
        return -1;
    }
    env->GetStringUTFRegion(value, 0, units, buffer);
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return -1;
    }
    buffer[bytes] = '\0';
    return bytes;
}

jlong nativeCreate(JNIEnv* env, jobject /*thiz*/) {
    (void)env;
    Engine* const engine = new (std::nothrow) Engine();
    if (engine == nullptr) {
        return 0;
    }
    if (!engine->create()) {
        delete engine;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(engine));
}

void nativeDestroy(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    (void)env;
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    engine->destroy();
    delete engine;
}

jint nativeLoadLanguage(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring tag, jint fd,
                        jlong offset, jlong length, jfloat weight) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return borderkeys::kBkdErrArgument;
    }
    char tagBuffer[kStringBufferBytes];
    if (copyString(env, tag, tagBuffer, sizeof(tagBuffer)) <= 0) {
        return borderkeys::kBkdErrArgument;
    }
    return engine->loadLanguage(tagBuffer, static_cast<int>(fd), static_cast<int64_t>(offset),
                                static_cast<int64_t>(length), static_cast<float>(weight));
}

/**
 * Describes a `.bkd` without loading it: fills `out` with { status, formatVersion, wordCount,
 * knownOnlyCount } and returns the language tag, or null when the pack was refused, `out[0]`
 * then holding the BkdStatus.
 */
jstring nativeInspectPack(JNIEnv* env, jobject /*thiz*/, jint fd, jlong offset, jlong length,
                          jintArray out) {
    constexpr jsize kInspectSlots = 4;
    if (out == nullptr || env->GetArrayLength(out) < kInspectSlots) {
        return nullptr;
    }
    borderkeys::PackInfo info = {};
    const int32_t status = borderkeys::bkdInspectPack(
        static_cast<int>(fd), static_cast<int64_t>(offset), static_cast<int64_t>(length), &info);

    jint values[kInspectSlots] = {static_cast<jint>(status), 0, 0, 0};
    if (status == borderkeys::kBkdOk) {
        values[1] = static_cast<jint>(info.formatVersion);
        values[2] = static_cast<jint>(info.wordCount);
        values[3] = static_cast<jint>(info.knownOnlyCount);
    }
    env->SetIntArrayRegion(out, 0, kInspectSlots, values);
    if (status != borderkeys::kBkdOk) {
        return nullptr;
    }
    return env->NewStringUTF(info.tag);
}

void nativeSetActiveLanguages(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray tags,
                              jfloatArray weights) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || tags == nullptr) {
        return;
    }
    const jsize total = env->GetArrayLength(tags);
    if (total <= 0) {
        // Nothing enabled: every pack closes -- see Engine::setActiveLanguages.
        engine->setActiveLanguages(nullptr, nullptr, 0);
        return;
    }
    // A longer list is cut to its first kMaxPacks tags.
    const jsize count = (total > Engine::kMaxPacks) ? Engine::kMaxPacks : total;

    char storage[Engine::kMaxPacks][kStringBufferBytes];
    const char* pointers[Engine::kMaxPacks];
    float weightValues[Engine::kMaxPacks];

    for (jsize i = 0; i < count; ++i) {
        jstring tag = static_cast<jstring>(env->GetObjectArrayElement(tags, i));
        const jsize length = copyString(env, tag, storage[i], kStringBufferBytes);
        // Each local reference is deleted as it is used.
        if (tag != nullptr) {
            env->DeleteLocalRef(tag);
        }
        if (length <= 0) {
            storage[i][0] = '\0';
        }
        pointers[i] = storage[i];
        weightValues[i] = 1.0f;
    }

    if (weights != nullptr && env->GetArrayLength(weights) >= count) {
        env->GetFloatArrayRegion(weights, 0, count, weightValues);
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            for (jsize i = 0; i < count; ++i) {
                weightValues[i] = 1.0f;
            }
        }
    }

    engine->setActiveLanguages(pointers, weightValues, static_cast<int>(count));
}

void nativeSetKeyGeometry(JNIEnv* env, jobject /*thiz*/, jlong handle, jintArray codes,
                          jfloatArray centersX, jfloatArray centersY, jfloat keyWidth,
                          jfloat keyHeight, jintArray aliasCodes, jintArray aliasBases) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || codes == nullptr || centersX == nullptr || centersY == nullptr) {
        return;
    }
    const jsize count = env->GetArrayLength(codes);
    if (count <= 0 || env->GetArrayLength(centersX) < count ||
        env->GetArrayLength(centersY) < count) {
        return;
    }
    const jsize limited =
        (count > borderkeys::KeyGeometry::kMaxKeys) ? borderkeys::KeyGeometry::kMaxKeys : count;

    jint codeBuffer[borderkeys::KeyGeometry::kMaxKeys];
    jfloat xBuffer[borderkeys::KeyGeometry::kMaxKeys];
    jfloat yBuffer[borderkeys::KeyGeometry::kMaxKeys];
    env->GetIntArrayRegion(codes, 0, limited, codeBuffer);
    env->GetFloatArrayRegion(centersX, 0, limited, xBuffer);
    env->GetFloatArrayRegion(centersY, 0, limited, yBuffer);
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return;
    }

    // The long-press letters of every key, before KeyGeometry keeps kMaxAliases a key.
    constexpr int kMaxAliasPairs = 1024;
    jint aliasCodeBuffer[kMaxAliasPairs];
    jint aliasBaseBuffer[kMaxAliasPairs];
    jsize aliasCount = 0;
    if (aliasCodes != nullptr && aliasBases != nullptr) {
        aliasCount = env->GetArrayLength(aliasCodes);
        if (env->GetArrayLength(aliasBases) < aliasCount) {
            aliasCount = env->GetArrayLength(aliasBases);
        }
        if (aliasCount > kMaxAliasPairs) {
            aliasCount = kMaxAliasPairs;
        }
        env->GetIntArrayRegion(aliasCodes, 0, aliasCount, aliasCodeBuffer);
        env->GetIntArrayRegion(aliasBases, 0, aliasCount, aliasBaseBuffer);
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            aliasCount = 0;
        }
    }

    engine->setKeyGeometry(reinterpret_cast<const int32_t*>(codeBuffer), xBuffer, yBuffer,
                           static_cast<int>(limited), static_cast<float>(keyWidth),
                           static_cast<float>(keyHeight),
                           reinterpret_cast<const int32_t*>(aliasCodeBuffer),
                           reinterpret_cast<const int32_t*>(aliasBaseBuffer),
                           static_cast<int>(aliasCount));
}

void nativeSetTouchPatterns(JNIEnv* env, jobject /*thiz*/, jlong handle, jintArray codes,
                            jfloatArray taps, jfloatArray meanX, jfloatArray meanY,
                            jfloatArray varianceX, jfloatArray varianceY, jfloatArray covariance) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || codes == nullptr || taps == nullptr || meanX == nullptr ||
        meanY == nullptr || varianceX == nullptr || varianceY == nullptr || covariance == nullptr) {
        return;
    }
    const jsize count = env->GetArrayLength(codes);
    for (jfloatArray values : {taps, meanX, meanY, varianceX, varianceY, covariance}) {
        if (env->GetArrayLength(values) < count) {
            return;
        }
    }
    const jsize limited =
        (count > borderkeys::TouchModel::kMaxKeys) ? borderkeys::TouchModel::kMaxKeys : count;

    jint codeBuffer[borderkeys::TouchModel::kMaxKeys];
    jfloat tapBuffer[borderkeys::TouchModel::kMaxKeys];
    jfloat meanXBuffer[borderkeys::TouchModel::kMaxKeys];
    jfloat meanYBuffer[borderkeys::TouchModel::kMaxKeys];
    jfloat varianceXBuffer[borderkeys::TouchModel::kMaxKeys];
    jfloat varianceYBuffer[borderkeys::TouchModel::kMaxKeys];
    jfloat covarianceBuffer[borderkeys::TouchModel::kMaxKeys];
    if (limited > 0) {
        env->GetIntArrayRegion(codes, 0, limited, codeBuffer);
        env->GetFloatArrayRegion(taps, 0, limited, tapBuffer);
        env->GetFloatArrayRegion(meanX, 0, limited, meanXBuffer);
        env->GetFloatArrayRegion(meanY, 0, limited, meanYBuffer);
        env->GetFloatArrayRegion(varianceX, 0, limited, varianceXBuffer);
        env->GetFloatArrayRegion(varianceY, 0, limited, varianceYBuffer);
        env->GetFloatArrayRegion(covariance, 0, limited, covarianceBuffer);
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            return;
        }
    }
    engine->setTouchPatterns(reinterpret_cast<const int32_t*>(codeBuffer), tapBuffer, meanXBuffer,
                             meanYBuffer, varianceXBuffer, varianceYBuffer, covarianceBuffer,
                             static_cast<int>(limited));
}

/** Stores [text] in `array[slot]` as a Java string; a failed allocation leaves the slot as is. */
void setText(JNIEnv* env, jobjectArray array, jsize slot, const char* text) {
    jstring value = env->NewStringUTF(text);
    if (value == nullptr) {
        env->ExceptionClear();
        return;
    }
    env->SetObjectArrayElement(array, slot, value);
    env->DeleteLocalRef(value);
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
    }
}

/** Writes the language whose pairs and triples the request just served read into `outTexts`. */
void writeLanguage(JNIEnv* env, const Engine& engine, jobjectArray outTexts) {
    if (outTexts != nullptr && env->GetArrayLength(outTexts) >= kTextSlots) {
        setText(env, outTexts, kTextLanguage, engine.personalLanguage());
    }
}

/**
 * Copies [candidate]'s text into `text`, which holds kStringBufferBytes, ending it with a NUL;
 * returns its length, 0 when it has none or does not fit. `source`, when given, receives the
 * engine's own copy.
 */
uint32_t copyCandidateText(const Engine& engine, const Candidate& candidate, char* text,
                           const char** source = nullptr) {
    uint32_t length = 0;
    const char* const own = engine.candidateText(candidate, &length);
    if (own == nullptr || length == 0 || length >= static_cast<uint32_t>(kStringBufferBytes)) {
        return 0;
    }
    std::memcpy(text, own, length);
    text[length] = '\0';
    if (source != nullptr) {
        *source = own;
    }
    return length;
}

/**
 * Writes the first [found] of [candidates] into `outWords`, `outScores` and `outProperNoun`, best
 * first, skipping any without text, and returns how many were written; 0 on a failed write.
 */
int writeRanking(JNIEnv* env, const Engine& engine, const Candidate* candidates, int found,
                 jobjectArray outWords, jfloatArray outScores, jbooleanArray outProperNoun) {
    float scores[Engine::kMaxCandidates];
    jboolean properNoun[Engine::kMaxCandidates];
    int written = 0;
    char text[kStringBufferBytes];
    for (int i = 0; i < found && i < Engine::kMaxCandidates; ++i) {
        if (copyCandidateText(engine, candidates[i], text) == 0) {
            continue;
        }
        // The one allocation per word, into the caller's reused array.
        jstring value = env->NewStringUTF(text);
        if (value == nullptr) {
            env->ExceptionClear();
            break;
        }
        env->SetObjectArrayElement(outWords, written, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            break;
        }
        scores[written] = candidates[i].score;
        properNoun[written] = engine.candidateIsProperNoun(candidates[i]) ? JNI_TRUE : JNI_FALSE;
        ++written;
    }
    if (written > 0) {
        env->SetFloatArrayRegion(outScores, 0, written, scores);
        if (outProperNoun != nullptr && env->GetArrayLength(outProperNoun) >= written) {
            env->SetBooleanArrayRegion(outProperNoun, 0, written, properNoun);
        }
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            return 0;
        }
    }
    return written;
}

/**
 * Fills `outTexts` for the typed [word]: how the dictionaries spell it, its possessive and the tap
 * decoder's word when the engine accepted it; a slot with none is left as it is.
 * `outCorrections` receives autocorrect's list for the request just
 * served, best first, one spelling once, `outCorrectionNames` whether each entry is a name,
 * `outCorrectionSlips` whether each was reached by neighbouring keys alone (Candidate::slipsOnly),
 * `outCorrectionConfident` whether each clears the tap decoder's weighing
 * (Engine::correctionConfident), and `outSpellingFlags` whether a dictionary spells the typed
 * letters exactly and whether that spelling is a name.
 */
void writeTexts(JNIEnv* env, const Engine& engine, const char* word, size_t length,
                jobjectArray outTexts, jobjectArray outCorrections, jbooleanArray outCorrectionNames,
                jbooleanArray outCorrectionSlips, jbooleanArray outCorrectionConfident,
                jbooleanArray outSpellingFlags) {
    if (outTexts == nullptr || env->GetArrayLength(outTexts) < kTextSlots) {
        return;
    }
    char text[kStringBufferBytes];
    const int spelled = engine.knownSpelling(word, length, text, sizeof(text) - 1);
    if (spelled > 0) {
        text[spelled] = '\0';
        setText(env, outTexts, kTextKnownSpelling, text);
    }
    const int possessive = engine.possessiveFor(word, length, text, sizeof(text) - 1);
    if (possessive > 0) {
        text[possessive] = '\0';
        setText(env, outTexts, kTextPossessive, text);
    }
    const Candidate* const decoded = engine.decodedCorrection();
    if (decoded != nullptr && copyCandidateText(engine, *decoded, text) > 0) {
        setText(env, outTexts, kTextDecoded, text);
    }
    if (outCorrections == nullptr || outCorrectionNames == nullptr ||
        outCorrectionSlips == nullptr || outCorrectionConfident == nullptr ||
        outSpellingFlags == nullptr ||
        env->GetArrayLength(outCorrections) < kCorrectionSlots ||
        env->GetArrayLength(outCorrectionNames) < kCorrectionSlots ||
        env->GetArrayLength(outCorrectionSlips) < kCorrectionSlots ||
        env->GetArrayLength(outCorrectionConfident) < kCorrectionSlots ||
        env->GetArrayLength(outSpellingFlags) < kSpellingFlagSlots) {
        return;
    }
    jboolean names[kCorrectionSlots] = {};
    jboolean slips[kCorrectionSlots] = {};
    jboolean confident[kCorrectionSlots] = {};
    jboolean spelling[kSpellingFlagSlots] = {};
    const Candidate* list = nullptr;
    const int count = engine.corrections(&list);
    const char* written[kCorrectionSlots];
    uint32_t writtenLength[kCorrectionSlots];
    int slot = 0;
    for (int i = 0; i < count && slot < kCorrectionSlots; ++i) {
        const char* source = nullptr;
        const uint32_t sourceLength = copyCandidateText(engine, list[i], text, &source);
        if (sourceLength == 0) {
            continue;
        }
        bool doubled = false;
        for (int j = 0; j < slot && !doubled; ++j) {
            doubled = borderkeys::sameSpellingIgnoringCase(written[j], writtenLength[j], source,
                                                           sourceLength);
        }
        if (doubled) {
            continue;
        }
        setText(env, outCorrections, slot, text);
        names[slot] = engine.candidateIsProperNoun(list[i]) ? JNI_TRUE : JNI_FALSE;
        slips[slot] = list[i].slipsOnly() ? JNI_TRUE : JNI_FALSE;
        confident[slot] = engine.correctionConfident(i) ? JNI_TRUE : JNI_FALSE;
        written[slot] = source;
        writtenLength[slot] = sourceLength;
        ++slot;
    }
    int exactPack = Engine::kNoPack;
    uint32_t exactWord = 0;
    if (engine.exactSpelling(word, length, &exactPack, &exactWord)) {
        spelling[kSpellingExact] = JNI_TRUE;
        const Candidate exact{exactPack, static_cast<int32_t>(exactWord), 0.0f};
        spelling[kSpellingName] = engine.candidateIsProperNoun(exact) ? JNI_TRUE : JNI_FALSE;
    }
    env->SetBooleanArrayRegion(outCorrectionNames, 0, kCorrectionSlots, names);
    env->SetBooleanArrayRegion(outCorrectionSlips, 0, kCorrectionSlots, slips);
    env->SetBooleanArrayRegion(outCorrectionConfident, 0, kCorrectionSlots, confident);
    env->SetBooleanArrayRegion(outSpellingFlags, 0, kSpellingFlagSlots, spelling);
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
    }
}

/**
 * Copies where each code point of the composing word was tapped into `xs` and `ys`, at most
 * Engine::kMaxComposing entries; returns how many, or 0 when there are no taps or the two arrays
 * differ in length.
 */
int copyTaps(JNIEnv* env, jfloatArray tapXs, jfloatArray tapYs, float* xs, float* ys) {
    if (tapXs == nullptr || tapYs == nullptr) {
        return 0;
    }
    const jsize count = env->GetArrayLength(tapXs);
    if (count != env->GetArrayLength(tapYs) || count <= 0) {
        return 0;
    }
    const jsize copied = count < Engine::kMaxComposing ? count : Engine::kMaxComposing;
    env->GetFloatArrayRegion(tapXs, 0, copied, xs);
    env->GetFloatArrayRegion(tapYs, 0, copied, ys);
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return 0;
    }
    return static_cast<int>(copied);
}

// nativeAnswer's work, for an engine already resolved.
jint answer(JNIEnv* env, Engine* engine, jstring composing, jstring prev1, jstring prev2,
            jfloatArray tapXs, jfloatArray tapYs, jobjectArray outWords, jfloatArray outScores,
            jbooleanArray outProperNoun, jobjectArray outTexts, jobjectArray outCorrections,
            jbooleanArray outCorrectionNames, jbooleanArray outCorrectionSlips,
            jbooleanArray outCorrectionConfident, jbooleanArray outSpellingFlags) {
    if (engine == nullptr || outWords == nullptr || outScores == nullptr ||
        outProperNoun == nullptr) {
        return 0;
    }

    char composingBuffer[kStringBufferBytes];
    char prev1Buffer[kStringBufferBytes];
    char prev2Buffer[kStringBufferBytes];

    // An empty composing string is legitimate: it asks for a next-word prediction.
    jsize composingLength = copyString(env, composing, composingBuffer, sizeof(composingBuffer));
    if (composingLength < 0) {
        composingBuffer[0] = '\0';
        composingLength = 0;
    }
    jsize prev1Length = copyString(env, prev1, prev1Buffer, sizeof(prev1Buffer));
    if (prev1Length < 0) {
        prev1Buffer[0] = '\0';
        prev1Length = 0;
    }
    jsize prev2Length = copyString(env, prev2, prev2Buffer, sizeof(prev2Buffer));
    if (prev2Length < 0) {
        prev2Buffer[0] = '\0';
        prev2Length = 0;
    }

    const jsize wordSlots = env->GetArrayLength(outWords);
    const jsize scoreSlots = env->GetArrayLength(outScores);
    const jsize properNounSlots = env->GetArrayLength(outProperNoun);
    jsize slots = (wordSlots < scoreSlots) ? wordSlots : scoreSlots;
    if (properNounSlots < slots) {
        slots = properNounSlots;
    }
    if (slots <= 0) {
        return 0;
    }
    if (slots > Engine::kMaxCandidates) {
        slots = Engine::kMaxCandidates;
    }

    float tapX[Engine::kMaxComposing];
    float tapY[Engine::kMaxComposing];
    const int tapCount = copyTaps(env, tapXs, tapYs, tapX, tapY);

    Candidate candidates[Engine::kMaxCandidates];
    const int found = engine->suggest(composingBuffer, static_cast<size_t>(composingLength),
                                      prev1Buffer, static_cast<size_t>(prev1Length),
                                      prev2Buffer, static_cast<size_t>(prev2Length),
                                      tapCount > 0 ? tapX : nullptr, tapCount > 0 ? tapY : nullptr,
                                      tapCount, candidates, static_cast<int>(slots));
    // The language whose pairs and triples the answer read, for every request.
    writeLanguage(env, *engine, outTexts);
    // For a typed word: how the dictionaries spell it, its possessive, and autocorrect's list.
    if (composingLength > 0) {
        writeTexts(env, *engine, composingBuffer, static_cast<size_t>(composingLength), outTexts,
                   outCorrections, outCorrectionNames, outCorrectionSlips, outCorrectionConfident,
                   outSpellingFlags);
    }
    return writeRanking(env, *engine, candidates, found, outWords, outScores, outProperNoun);
}

jint nativeAnswer(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring composing, jstring prev1,
                  jstring prev2, jfloatArray tapXs, jfloatArray tapYs, jobjectArray outWords,
                  jfloatArray outScores, jbooleanArray outProperNoun, jobjectArray outTexts,
                  jobjectArray outCorrections, jbooleanArray outCorrectionNames,
                  jbooleanArray outCorrectionSlips, jbooleanArray outCorrectionConfident,
                  jbooleanArray outSpellingFlags) {
    return answer(env, engineFrom(handle), composing, prev1, prev2, tapXs, tapYs, outWords,
                  outScores, outProperNoun, outTexts, outCorrections, outCorrectionNames,
                  outCorrectionSlips, outCorrectionConfident, outSpellingFlags);
}

// nativeAnswer for `composing` alone, with no words before it and no taps, as if `packIndex`
// were the language being written; -1 when that pack is not open and active.
jint nativeAnswerAs(JNIEnv* env, jobject /*thiz*/, jlong handle, jint packIndex,
                    jstring composing, jobjectArray outWords, jfloatArray outScores,
                    jbooleanArray outProperNoun, jobjectArray outTexts,
                    jobjectArray outCorrections, jbooleanArray outCorrectionNames,
                    jbooleanArray outCorrectionSlips, jbooleanArray outCorrectionConfident,
                    jbooleanArray outSpellingFlags) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return -1;
    }
    const Engine::AnsweringScope answering(*engine, static_cast<int>(packIndex));
    if (!answering.held()) {
        return -1;
    }
    return answer(env, engine, composing, nullptr, nullptr, nullptr, nullptr, outWords, outScores,
                  outProperNoun, outTexts, outCorrections, outCorrectionNames, outCorrectionSlips,
                  outCorrectionConfident, outSpellingFlags);
}

/**
 * [text] in lower case as the engine keys a word (lowerUtf8), whatever its length; a text that is
 * not valid UTF-8 to lowerUtf8, as one holding a character past U+FFFF, comes back as it is.
 */
jstring nativeLowerCase(JNIEnv* env, jobject /*thiz*/, jstring text) {
    if (text == nullptr) {
        return text;
    }
    const jsize units = env->GetStringLength(text);
    const jsize bytes = env->GetStringUTFLength(text);
    if (units <= 0 || bytes <= 0) {
        return text;
    }
    // Up to the longest word the engine takes, on the stack; past it, in buffers sized to the text.
    char stackSource[kStringBufferBytes];
    char stackLowered[kStringBufferBytes + borderkeys::kMaxUtf8Bytes];
    std::unique_ptr<char[]> longSource;
    std::unique_ptr<char[]> longLowered;
    char* source = stackSource;
    char* lowered = stackLowered;
    size_t loweredBytes = sizeof(stackLowered);
    if (static_cast<size_t>(bytes) + 1 > sizeof(stackSource)) {
        loweredBytes = static_cast<size_t>(bytes) + borderkeys::kMaxUtf8Bytes + 1;
        longSource.reset(new (std::nothrow) char[static_cast<size_t>(bytes) + 1]);
        longLowered.reset(new (std::nothrow) char[loweredBytes]);
        if (longSource == nullptr || longLowered == nullptr) {
            return text;
        }
        source = longSource.get();
        lowered = longLowered.get();
    }
    env->GetStringUTFRegion(text, 0, units, source);
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return text;
    }
    const int written = borderkeys::lowerUtf8(source, static_cast<size_t>(bytes), lowered,
                                              static_cast<int>(loweredBytes) - 1);
    if (written < 0) {
        return text;
    }
    lowered[written] = '\0';
    jstring result = env->NewStringUTF(lowered);
    if (result == nullptr) {
        env->ExceptionClear();
        return text;
    }
    return result;
}

jint nativeLearn(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring word, jstring prev1,
                 jstring prev2, jboolean deliberateCapital, jboolean asserted, jstring language,
                 jboolean tally) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return 0;
    }
    char wordBuffer[kStringBufferBytes];
    char prev1Buffer[kStringBufferBytes];
    char prev2Buffer[kStringBufferBytes];

    const jsize wordLength = copyString(env, word, wordBuffer, sizeof(wordBuffer));
    if (wordLength <= 0) {
        return 0;
    }
    jsize prev1Length = copyString(env, prev1, prev1Buffer, sizeof(prev1Buffer));
    if (prev1Length < 0) {
        prev1Buffer[0] = '\0';
        prev1Length = 0;
    }
    jsize prev2Length = copyString(env, prev2, prev2Buffer, sizeof(prev2Buffer));
    if (prev2Length < 0) {
        prev2Buffer[0] = '\0';
        prev2Length = 0;
    }
    char languageBuffer[kStringBufferBytes];
    jsize languageLength = copyString(env, language, languageBuffer, sizeof(languageBuffer));
    if (languageLength < 0) {
        languageBuffer[0] = '\0';
        languageLength = 0;
    }

    const Engine::Learned learned = engine->learn(
        wordBuffer, static_cast<size_t>(wordLength), prev1Buffer, static_cast<size_t>(prev1Length),
        prev2Buffer, static_cast<size_t>(prev2Length), deliberateCapital == JNI_TRUE,
        asserted == JNI_TRUE, languageBuffer, static_cast<size_t>(languageLength),
        tally == JNI_TRUE);
    return (learned.pair ? kLearnedPair : 0) | (learned.triple ? kLearnedTriple : 0);
}

/**
 * Copies string [index] of [array] into a newly allocated C string at [copy]; returns its length in
 * bytes, or zero, [copy] untouched, when the string cannot be read or copied.
 */
jsize copyStringAt(JNIEnv* env, jobjectArray array, jsize index, char** copy) {
    jstring value = static_cast<jstring>(env->GetObjectArrayElement(array, index));
    char buffer[kStringBufferBytes];
    const jsize length = copyString(env, value, buffer, sizeof(buffer));
    if (value != nullptr) {
        // One local reference at a time.
        env->DeleteLocalRef(value);
    }
    if (length <= 0) {
        return 0;
    }
    char* const copied = new (std::nothrow) char[length];
    if (copied == nullptr) {
        return 0;
    }
    std::memcpy(copied, buffer, static_cast<size_t>(length));
    *copy = copied;
    return length;
}

/**
 * Copies a Java string array into newly allocated C strings, for the bulk loaders; a string it
 * cannot read or copy is left null, with no length.
 */
void copyStringArray(JNIEnv* env, jobjectArray array, jsize count, char** storage,
                     size_t* lengths) {
    for (jsize i = 0; i < count; ++i) {
        storage[i] = nullptr;
        lengths[i] = 0;
    }
    for (jsize i = 0; i < count; ++i) {
        lengths[i] = static_cast<size_t>(copyStringAt(env, array, i, &storage[i]));
    }
}

/** One Java string array's worth of copied C strings, freed on destruction. */
class StringColumn {
public:
    StringColumn() = default;
    explicit StringColumn(jsize count) { allocate(count); }
    ~StringColumn() { release(); }

    StringColumn(const StringColumn&) = delete;
    StringColumn& operator=(const StringColumn&) = delete;

    /** Makes room for `count` strings, none copied yet; nothing for none. */
    void allocate(jsize count) {
        release();
        count_ = count;
        store_ = count > 0 ? new (std::nothrow) char*[count] : nullptr;
        lengths_ = count > 0 ? new (std::nothrow) size_t[count] : nullptr;
        if (valid()) {
            for (jsize i = 0; i < count_; ++i) {
                store_[i] = nullptr;
                lengths_[i] = 0;
            }
        }
    }

    bool valid() const { return store_ != nullptr && lengths_ != nullptr; }
    void copyFrom(JNIEnv* env, jobjectArray array) {
        copyStringArray(env, array, count_, store_, lengths_);
    }
    char** data() const { return store_; }
    size_t* lengths() const { return lengths_; }

private:
    void release() {
        if (store_ != nullptr) {
            for (jsize i = 0; i < count_; ++i) {
                delete[] store_[i];
            }
        }
        delete[] store_;
        delete[] lengths_;
        count_ = 0;
        store_ = nullptr;
        lengths_ = nullptr;
    }

    jsize count_ = 0;
    char** store_ = nullptr;
    size_t* lengths_ = nullptr;
};

/** A bulk loader's numbers, one per row, freed on destruction. */
template <typename T>
class NumberColumn {
public:
    explicit NumberColumn(jsize count)
        : count_(count), data_(count > 0 ? new (std::nothrow) T[count] : nullptr) {}
    ~NumberColumn() { delete[] data_; }

    NumberColumn(const NumberColumn&) = delete;
    NumberColumn& operator=(const NumberColumn&) = delete;

    bool valid() const { return data_ != nullptr; }
    jsize count() const { return count_; }
    T* data() const { return data_; }

private:
    jsize count_;
    T* data_;
};

/** Reads `array`'s first `column.count()` values into `column`; false when the read fails. */
bool readInts(JNIEnv* env, jintArray array, const NumberColumn<int32_t>& column) {
    env->GetIntArrayRegion(array, 0, column.count(), reinterpret_cast<jint*>(column.data()));
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

/**
 * Reads `array`'s first `column.count()` values into `column`, each held between zero and the
 * largest `T`; false when it is short or the read fails.
 */
template <typename T>
bool readLongs(JNIEnv* env, jlongArray array, const NumberColumn<T>& column) {
    if (array == nullptr || env->GetArrayLength(array) < column.count()) {
        return false;
    }
    jlong* const values = env->GetLongArrayElements(array, nullptr);
    if (values == nullptr) {
        return false;
    }
    constexpr T kLargest = std::numeric_limits<T>::max();
    T* const data = column.data();
    for (jsize i = 0; i < column.count(); ++i) {
        data[i] = values[i] <= 0 ? T{0}
                  : static_cast<uint64_t>(values[i]) >= static_cast<uint64_t>(kLargest)
                      ? kLargest
                      : static_cast<T>(values[i]);
    }
    env->ReleaseLongArrayElements(array, values, JNI_ABORT);
    return true;
}

/** Whether `languages` and every array of `words` are given. */
template <size_t kWords>
bool given(jobjectArray languages, const std::array<jobjectArray, kWords>& words) {
    return languages != nullptr && std::all_of(words.begin(), words.end(), [](jobjectArray array) {
               return array != nullptr;
           });
}

/** Whether `languages` and every array of `words` hold at least `rows`. */
template <size_t kWords>
bool hold(JNIEnv* env, jobjectArray languages, const std::array<jobjectArray, kWords>& words,
          jsize rows) {
    return env->GetArrayLength(languages) >= rows &&
           std::all_of(words.begin(), words.end(), [env, rows](jobjectArray array) {
               return env->GetArrayLength(array) >= rows;
           });
}

/** Makes room for `rows` in `language` and in every column of `words`; false when one has none. */
template <size_t kWords>
bool allocate(jsize rows, StringColumn& language, std::array<StringColumn, kWords>& words) {
    language.allocate(rows);
    bool valid = language.valid();
    for (StringColumn& column : words) {
        column.allocate(rows);
        valid = valid && column.valid();
    }
    return valid;
}

/** Copies `languages` into `language` and each array of `words` into its column. */
template <size_t kWords>
void copyRows(JNIEnv* env, jobjectArray languages, const std::array<jobjectArray, kWords>& words,
              StringColumn& language, std::array<StringColumn, kWords>& columns) {
    language.copyFrom(env, languages);
    for (size_t i = 0; i < kWords; ++i) {
        columns[i].copyFrom(env, words[i]);
    }
}

/**
 * Loads one kind of link's rows through `load`: `languages`, then `words`, the word arrays in
 * order, `counts`, each row's uses, and `contextSeen` and `languageSeen`, its stamps. An empty
 * list clears the kind, `load` given no rows.
 */
template <size_t kWords, typename Load>
void loadLinkRows(JNIEnv* env, jlong handle, jobjectArray languages,
                  const std::array<jobjectArray, kWords>& words, jintArray counts,
                  jlongArray contextSeen, jlongArray languageSeen, Load load) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || !given(languages, words) || counts == nullptr) {
        return;
    }
    const jsize rows = env->GetArrayLength(words.front());
    StringColumn languageColumn;
    std::array<StringColumn, kWords> wordColumns;
    // An empty list clears the links the engine holds.
    if (rows == 0) {
        load(*engine, languageColumn, wordColumns, nullptr, nullptr, nullptr, 0);
        return;
    }
    if (rows < 0 || !hold(env, languages, words, rows) || env->GetArrayLength(counts) < rows) {
        return;
    }
    NumberColumn<int32_t> countValues(rows);
    NumberColumn<uint64_t> contextSeenValues(rows);
    NumberColumn<uint64_t> languageSeenValues(rows);
    if (!allocate(rows, languageColumn, wordColumns) || !countValues.valid() ||
        !contextSeenValues.valid() || !languageSeenValues.valid() ||
        !readInts(env, counts, countValues) || !readLongs(env, contextSeen, contextSeenValues) ||
        !readLongs(env, languageSeen, languageSeenValues)) {
        return;
    }
    copyRows(env, languages, words, languageColumn, wordColumns);
    load(*engine, languageColumn, wordColumns, countValues.data(), contextSeenValues.data(),
         languageSeenValues.data(), static_cast<int>(rows));
}

/**
 * Loads how often each context of one kind of link has been followed, and how many times it has
 * been seen, per language, through `load`: `languages`, then `words`, the context's word arrays in
 * order, `totals` and `seen`.
 */
template <size_t kWords, typename Load>
void loadContextTotals(JNIEnv* env, jlong handle, jobjectArray languages,
                       const std::array<jobjectArray, kWords>& words, jlongArray totals,
                       jlongArray seen, Load load) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || !given(languages, words) || totals == nullptr) {
        return;
    }
    const jsize rows = env->GetArrayLength(words.front());
    if (rows <= 0 || !hold(env, languages, words, rows)) {
        return;
    }
    StringColumn languageColumn;
    std::array<StringColumn, kWords> wordColumns;
    NumberColumn<uint32_t> totalValues(rows);
    NumberColumn<uint64_t> seenValues(rows);
    if (!allocate(rows, languageColumn, wordColumns) || !totalValues.valid() ||
        !seenValues.valid() || !readLongs(env, totals, totalValues) ||
        !readLongs(env, seen, seenValues)) {
        return;
    }
    copyRows(env, languages, words, languageColumn, wordColumns);
    load(*engine, languageColumn, wordColumns, totalValues.data(), seenValues.data(),
         static_cast<int>(rows));
}

/** Drops one kind of link's rows through `drop`: `languages`, then `words` in order. */
template <size_t kWords, typename Drop>
void dropLinkRows(JNIEnv* env, jlong handle, jobjectArray languages,
                  const std::array<jobjectArray, kWords>& words, Drop drop) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || !given(languages, words)) {
        return;
    }
    const jsize rows = env->GetArrayLength(words.front());
    if (rows <= 0 || !hold(env, languages, words, rows)) {
        return;
    }
    StringColumn languageColumn;
    std::array<StringColumn, kWords> wordColumns;
    if (!allocate(rows, languageColumn, wordColumns)) {
        return;
    }
    copyRows(env, languages, words, languageColumn, wordColumns);
    drop(*engine, languageColumn, wordColumns, static_cast<int>(rows));
}

/** Loads the remembered three-word sequences, as parallel arrays, after the pairs. */
void nativeLoadUserTrigrams(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray languages,
                            jobjectArray previous2, jobjectArray previous1, jobjectArray next,
                            jintArray counts, jlongArray contextSeen, jlongArray languageSeen) {
    loadLinkRows(env, handle, languages, std::array{previous2, previous1, next}, counts,
                 contextSeen, languageSeen,
                 [](Engine& engine, const StringColumn& language, const auto& words,
                    const int32_t* uses, const uint64_t* byContext, const uint64_t* byLanguage,
                    int rows) {
                     const auto& [before2, before1, after] = words;
                     engine.loadUserTrigrams(language.data(), language.lengths(), before2.data(),
                                             before2.lengths(), before1.data(), before1.lengths(),
                                             after.data(), after.lengths(), uses, rows, byContext,
                                             byLanguage);
                 });
}

/**
 * How often each previous word of a pair has been followed, and how many times it has been seen,
 * per language; after the pairs.
 */
void nativeLoadUserPairTotals(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray languages,
                              jobjectArray previous, jlongArray totals, jlongArray seen) {
    loadContextTotals(env, handle, languages, std::array{previous}, totals, seen,
                      [](Engine& engine, const StringColumn& language, const auto& words,
                         const uint32_t* followed, const uint64_t* observed, int rows) {
                          const auto& [before] = words;
                          engine.loadUserPairTotals(language.data(), language.lengths(),
                                                    before.data(), before.lengths(), followed,
                                                    rows, observed);
                      });
}

/** [nativeLoadUserPairTotals], for each start of a triple; after the triples. */
void nativeLoadUserTripleTotals(JNIEnv* env, jobject /*thiz*/, jlong handle,
                                jobjectArray languages, jobjectArray previous2,
                                jobjectArray previous1, jlongArray totals, jlongArray seen) {
    loadContextTotals(env, handle, languages, std::array{previous2, previous1}, totals, seen,
                      [](Engine& engine, const StringColumn& language, const auto& words,
                         const uint32_t* followed, const uint64_t* observed, int rows) {
                          const auto& [before2, before1] = words;
                          engine.loadUserTripleTotals(language.data(), language.lengths(),
                                                      before2.data(), before2.lengths(),
                                                      before1.data(), before1.lengths(), followed,
                                                      rows, observed);
                      });
}

/** Each language's pair clock and triple clock, as parallel arrays; after the links. */
void nativeLoadUserClocks(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray languages,
                          jlongArray pairClocks, jlongArray tripleClocks) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || languages == nullptr) {
        return;
    }
    const jsize rows = env->GetArrayLength(languages);
    if (rows < 0) {
        return;
    }
    StringColumn languageColumn(rows);
    NumberColumn<uint64_t> pairValues(rows);
    NumberColumn<uint64_t> tripleValues(rows);
    if (rows > 0 && (!languageColumn.valid() || !pairValues.valid() || !tripleValues.valid() ||
                     !readLongs(env, pairClocks, pairValues) ||
                     !readLongs(env, tripleClocks, tripleValues))) {
        return;
    }
    languageColumn.copyFrom(env, languages);
    engine->loadUserClocks(languageColumn.data(), languageColumn.lengths(), pairValues.data(),
                           tripleValues.data(), static_cast<int>(rows));
}

/** Stores the key of personal entry `entry` in `array[slot]`; one with no key leaves it as is. */
void setEntryKey(JNIEnv* env, const Engine& engine, jobjectArray array, jsize slot,
                 int32_t entry) {
    uint32_t length = 0;
    const char* const key =
        entry < 0 ? nullptr : engine.userEntryKey(static_cast<uint32_t>(entry), &length);
    if (key == nullptr || length == 0 || length >= static_cast<uint32_t>(kStringBufferBytes)) {
        return;
    }
    char text[kStringBufferBytes];
    std::memcpy(text, key, length);
    text[length] = '\0';
    setText(env, array, slot, text);
}

/** Writes `value` into `array[slot]`, an exception cleared. */
template <typename Array, typename Value, typename Write>
void setNumber(JNIEnv* env, Array array, jsize slot, Value value, Write write) {
    (env->*write)(array, slot, 1, &value);
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
    }
}

/**
 * The links after `previous2` and `previous1` in `language`'s store, pairs when `previous2` is
 * null, in the order recorded: each next word's key into `outWords`, its count into `outCounts`
 * and how many times its context had been seen when it last was into `outContextSeen`, up to their
 * size; `outContext` gets the context's total and observations, zeros for a context not held.
 * Returns how many links follow it.
 */
jint nativeContextLinks(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring language,
                        jstring previous2, jstring previous1, jobjectArray outWords,
                        jintArray outCounts, jlongArray outContextSeen, jlongArray outContext) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || outWords == nullptr || outCounts == nullptr ||
        outContextSeen == nullptr || outContext == nullptr ||
        env->GetArrayLength(outContext) < kContextSlots) {
        return 0;
    }
    const jlong none[kContextSlots] = {};
    env->SetLongArrayRegion(outContext, 0, kContextSlots, none);
    char languageBuffer[kStringBufferBytes];
    char previous2Buffer[kStringBufferBytes];
    char previous1Buffer[kStringBufferBytes];
    const jsize languageLength =
        copyString(env, language, languageBuffer, sizeof(languageBuffer));
    const jsize previous1Length =
        copyString(env, previous1, previous1Buffer, sizeof(previous1Buffer));
    jsize previous2Length = copyString(env, previous2, previous2Buffer, sizeof(previous2Buffer));
    if (languageLength <= 0 || previous1Length <= 0) {
        return 0;
    }
    if (previous2Length < 0) {
        previous2Length = 0;
    }
    const auto read = [&](borderkeys::UserModel::ContextLink* out, int maxOut,
                          borderkeys::UserModel::ContextCount* context) {
        return engine->userContextLinks(
            languageBuffer, static_cast<size_t>(languageLength), previous2Buffer,
            static_cast<size_t>(previous2Length), previous1Buffer,
            static_cast<size_t>(previous1Length), out, maxOut, context);
    };
    borderkeys::UserModel::ContextLink onStack[kContextLinksOnStack];
    borderkeys::UserModel::ContextCount context{};
    const int held = read(onStack, kContextLinksOnStack, &context);
    std::unique_ptr<borderkeys::UserModel::ContextLink[]> spilled;
    const borderkeys::UserModel::ContextLink* links = onStack;
    if (held > kContextLinksOnStack) {
        spilled.reset(new (std::nothrow) borderkeys::UserModel::ContextLink[held]);
        if (spilled == nullptr) {
            return 0;
        }
        read(spilled.get(), held, &context);
        links = spilled.get();
    }
    jlong counted[kContextSlots] = {};
    counted[kContextTotal] = static_cast<jlong>(context.total);
    counted[kContextSeen] = static_cast<jlong>(context.seen);
    env->SetLongArrayRegion(outContext, 0, kContextSlots, counted);
    const jsize capacity = std::min({env->GetArrayLength(outWords), env->GetArrayLength(outCounts),
                                     env->GetArrayLength(outContextSeen)});
    for (jsize i = 0; i < held && i < capacity; ++i) {
        setEntryKey(env, *engine, outWords, i, links[i].nextIndex);
        setNumber(env, outCounts, i, static_cast<jint>(links[i].count),
                  &JNIEnv::SetIntArrayRegion);
        setNumber(env, outContextSeen, i, static_cast<jlong>(links[i].contextSeen),
                  &JNIEnv::SetLongArrayRegion);
    }
    return held;
}

/**
 * Whether each of `words` may be part of a learned pair or triple (Engine::learnable), into `out`
 * up to its size; a word the bridge cannot take is left as `out` holds it.
 */
void nativeLearnable(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray words,
                     jbooleanArray out) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || words == nullptr || out == nullptr) {
        return;
    }
    const jsize count = std::min(env->GetArrayLength(words), env->GetArrayLength(out));
    char buffer[kStringBufferBytes];
    for (jsize i = 0; i < count; ++i) {
        jstring word = static_cast<jstring>(env->GetObjectArrayElement(words, i));
        const jsize length = copyString(env, word, buffer, sizeof(buffer));
        if (word != nullptr) {
            env->DeleteLocalRef(word);
        }
        if (length <= 0) {
            continue;
        }
        const jboolean verdict =
            engine->learnable(buffer, static_cast<size_t>(length)) ? JNI_TRUE : JNI_FALSE;
        setNumber(env, out, i, verdict, &JNIEnv::SetBooleanArrayRegion);
    }
}

/** How many triples, or pairs, `language`'s store holds. */
jint nativeLinksHeld(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring language,
                     jboolean triples) {
    Engine* const engine = engineFrom(handle);
    char languageBuffer[kStringBufferBytes];
    const jsize languageLength =
        engine == nullptr ? -1 : copyString(env, language, languageBuffer, sizeof(languageBuffer));
    if (languageLength <= 0) {
        return 0;
    }
    return engine->userLinksHeld(languageBuffer, static_cast<size_t>(languageLength),
                                 triples == JNI_TRUE);
}

/**
 * Every triple, or pair, of `language`'s store, up to the arrays' size: the keys of the words
 * before its last into `outPrevious2` (left as is for a pair) and `outPrevious1`, its last into
 * `outWords`, its count, when its language last saw it, and its context's total. Returns how many
 * were written.
 */
jint nativeLanguageLinks(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring language,
                         jboolean triples, jobjectArray outPrevious2, jobjectArray outPrevious1,
                         jobjectArray outWords, jintArray outCounts, jlongArray outLanguageSeen,
                         jlongArray outContextTotals) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || outPrevious2 == nullptr || outPrevious1 == nullptr ||
        outWords == nullptr || outCounts == nullptr || outLanguageSeen == nullptr ||
        outContextTotals == nullptr) {
        return 0;
    }
    char languageBuffer[kStringBufferBytes];
    const jsize languageLength = copyString(env, language, languageBuffer, sizeof(languageBuffer));
    if (languageLength <= 0) {
        return 0;
    }
    const jsize capacity = std::min(
        {env->GetArrayLength(outPrevious2), env->GetArrayLength(outPrevious1),
         env->GetArrayLength(outWords), env->GetArrayLength(outCounts),
         env->GetArrayLength(outLanguageSeen), env->GetArrayLength(outContextTotals)});
    jsize written = 0;
    Engine::StoredLink link{};
    while (written < capacity &&
           engine->userLinkAt(languageBuffer, static_cast<size_t>(languageLength),
                              triples == JNI_TRUE, static_cast<int>(written), &link)) {
        setEntryKey(env, *engine, outPrevious2, written, link.previous2Index);
        setEntryKey(env, *engine, outPrevious1, written, link.previous1Index);
        setEntryKey(env, *engine, outWords, written, link.nextIndex);
        setNumber(env, outCounts, written, static_cast<jint>(link.count),
                  &JNIEnv::SetIntArrayRegion);
        setNumber(env, outLanguageSeen, written, static_cast<jlong>(link.languageSeen),
                  &JNIEnv::SetLongArrayRegion);
        setNumber(env, outContextTotals, written, static_cast<jlong>(link.contextTotal),
                  &JNIEnv::SetLongArrayRegion);
        ++written;
    }
    return written;
}

/** Drops the pairs the budget gave up, each from its language's store. */
void nativeDropUserPairs(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray languages,
                         jobjectArray previous, jobjectArray next) {
    dropLinkRows(env, handle, languages, std::array{previous, next},
                 [](Engine& engine, const StringColumn& language, const auto& words, int rows) {
                     const auto& [before, after] = words;
                     engine.dropUserPairs(language.data(), language.lengths(), before.data(),
                                          before.lengths(), after.data(), after.lengths(), rows);
                 });
}

/** Drops the triples the budget gave up, each from its language's store. */
void nativeDropUserTriples(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray languages,
                           jobjectArray previous2, jobjectArray previous1, jobjectArray next) {
    dropLinkRows(env, handle, languages, std::array{previous2, previous1, next},
                 [](Engine& engine, const StringColumn& language, const auto& words, int rows) {
                     const auto& [before2, before1, after] = words;
                     engine.dropUserTriples(language.data(), language.lengths(), before2.data(),
                                            before2.lengths(), before1.data(), before1.lengths(),
                                            after.data(), after.lengths(), rows);
                 });
}

/** The score of `candidate` as an answer to `typed`, term by term, into `out` in
 *  ScoreExplanation.kt's slot order; false when the word is not offered. */
jboolean nativeExplainScore(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring typed,
                            jstring candidate, jfloatArray out) {
    constexpr jsize kSlots = 15;
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || typed == nullptr || candidate == nullptr || out == nullptr ||
        env->GetArrayLength(out) < kSlots) {
        return JNI_FALSE;
    }
    char typedBuffer[kStringBufferBytes];
    char candidateBuffer[kStringBufferBytes];
    jsize typedLength = copyString(env, typed, typedBuffer, sizeof(typedBuffer));
    if (typedLength < 0) {
        typedBuffer[0] = '\0';
        typedLength = 0;
    }
    const jsize candidateLength =
        copyString(env, candidate, candidateBuffer, sizeof(candidateBuffer));
    if (candidateLength <= 0) {
        return JNI_FALSE;
    }
    Engine::ScoreParts parts;
    if (!engine->explainScore(typedBuffer, static_cast<size_t>(typedLength), candidateBuffer,
                              static_cast<size_t>(candidateLength), &parts)) {
        return JNI_FALSE;
    }
    const jfloat values[kSlots] = {
        parts.total,
        parts.packWeight,
        parts.languageModel,
        parts.personal,
        parts.rest,
        static_cast<jfloat>(parts.packIndex),
        static_cast<jfloat>(parts.rank),
        static_cast<jfloat>(parts.editDistance),
        static_cast<jfloat>(parts.addedCharacters),
        parts.editCost,
        static_cast<jfloat>(parts.edits),
        static_cast<jfloat>(parts.runOn),
        parts.editPenalty,
        parts.surcharge,
        parts.completion,
    };
    env->SetFloatArrayRegion(out, 0, kSlots, values);
    return JNI_TRUE;
}

/** Which of `stems`, at most kMaxStemsQuery, the pack for `tag` vouches for; see
 *  Engine::vouchesForStem. */
jint nativeKnownStems(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring tag,
                      jobjectArray words, jbooleanArray outKnown) {
    constexpr jsize kMaxStemsQuery = 64;
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || tag == nullptr || words == nullptr || outKnown == nullptr) {
        return 0;
    }
    char tagBuffer[kStringBufferBytes];
    if (copyString(env, tag, tagBuffer, sizeof(tagBuffer)) <= 0) {
        return 0;
    }
    const jsize count = env->GetArrayLength(words);
    if (count <= 0 || count > kMaxStemsQuery || env->GetArrayLength(outKnown) < count) {
        return 0;
    }
    jboolean flags[kMaxStemsQuery];
    jint known = 0;
    for (jsize i = 0; i < count; ++i) {
        jstring value = static_cast<jstring>(env->GetObjectArrayElement(words, i));
        char buffer[kStringBufferBytes];
        const jsize length = copyString(env, value, buffer, sizeof(buffer));
        if (value != nullptr) {
            env->DeleteLocalRef(value);
        }
        flags[i] = (length > 0 &&
                    engine->vouchesForStem(buffer, static_cast<size_t>(length), tagBuffer))
            ? JNI_TRUE
            : JNI_FALSE;
        known += (flags[i] == JNI_TRUE) ? 1 : 0;
    }
    env->SetBooleanArrayRegion(outKnown, 0, count, flags);
    return known;
}

/** The spelling the dictionaries hold for `word`; see Engine::knownSpelling. */
jstring nativeKnownSpelling(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring word) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || word == nullptr) {
        return nullptr;
    }
    char buffer[kStringBufferBytes];
    const jsize length = copyString(env, word, buffer, sizeof(buffer));
    if (length <= 0) {
        return nullptr;
    }
    char spelling[kStringBufferBytes];
    const int written = engine->knownSpelling(buffer, static_cast<size_t>(length), spelling,
                                              sizeof(spelling) - 1);
    if (written <= 0) {
        return nullptr;
    }
    spelling[written] = '\0';
    return env->NewStringUTF(spelling);
}

jint nativeDominantPack(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Engine* const engine = engineFrom(handle);
    return engine == nullptr ? Engine::kNoPack : static_cast<jint>(engine->dominantPack());
}

void nativeSetPhraseSuggestions(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
                                jboolean enabled) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    engine->setPhraseSuggestions(enabled == JNI_TRUE);
}

void nativeSetPersonalModelEnabled(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
                                   jboolean enabled) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    engine->setPersonalModelEnabled(enabled == JNI_TRUE);
}

void nativeSetTouchModel(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jboolean learned,
                         jfloat weight, jint minTaps) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    engine->setTouchModel(learned == JNI_TRUE, weight, minTaps);
}

jstring nativeDominantLanguageTag(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    Engine* const engine = engineFrom(handle);
    const char* const tag = engine == nullptr ? nullptr : engine->dominantLanguageTag();
    return tag == nullptr ? nullptr : env->NewStringUTF(tag);
}

/** The tag of the pack in `slot`, or null when the slot holds no open pack. */
jstring nativePackTag(JNIEnv* env, jobject /*thiz*/, jlong handle, jint slot) {
    Engine* const engine = engineFrom(handle);
    const char* const tag = engine == nullptr ? nullptr : engine->packTag(static_cast<int>(slot));
    return tag == nullptr ? nullptr : env->NewStringUTF(tag);
}

/** Loads tier B's weights for [script] from a byte array; false in a `core` build. */
jboolean nativeLoadSwipeWeights(JNIEnv* env, jobject /*thiz*/, jlong handle, jint script,
                                jbyteArray weights) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || weights == nullptr) {
        return JNI_FALSE;
    }
    const jsize length = env->GetArrayLength(weights);
    if (length <= 0) {
        return JNI_FALSE;
    }
    jbyte* const bytes = env->GetByteArrayElements(weights, nullptr);
    if (bytes == nullptr) {
        return JNI_FALSE;
    }
    const bool loaded = engine->loadSwipeWeights(
        static_cast<int>(script), reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(weights, bytes, JNI_ABORT);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

/** Whether the last gesture decode went through tier B. */
jboolean nativeLastDecodeUsedNeural(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Engine* const engine = engineFrom(handle);
    return (engine != nullptr && engine->lastDecodeUsedNeural()) ? JNI_TRUE : JNI_FALSE;
}

/** Switches tier B on or off; off frees its weights. A no-op in a `core` build. */
void nativeSetSwipeModelEnabled(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
                                jboolean enabled) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    engine->setSwipeModelEnabled(enabled == JNI_TRUE);
}

/** Which script's model the layout now set decodes with; one not loaded decodes with tier A. */
void nativeSelectSwipeScript(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint script) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    engine->selectSwipeScript(static_cast<int>(script));
}

/** Whether a model for [script] is loaded. */
jboolean nativeHasSwipeModel(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint script) {
    Engine* const engine = engineFrom(handle);
    return (engine != nullptr && engine->hasSwipeModel(static_cast<int>(script))) ? JNI_TRUE : JNI_FALSE;
}

/** Runs one discarded decode through tier B; false when there is nothing to warm. */
jboolean nativeWarmSwipeModel(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return JNI_FALSE;
    }
    return engine->warmSwipeModel() ? JNI_TRUE : JNI_FALSE;
}

void nativeSetLearnAfterUses(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint uses) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    engine->setLearnAfterUses(static_cast<int>(uses));
}

void nativeSetKnownWordReach(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
                             jfloat minimumLogProb) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    engine->setKnownWordReach(static_cast<float>(minimumLogProb));
}

void nativeSetCorrectionStrictness(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle,
                                   jfloat scale) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    engine->setCorrectionStrictness(static_cast<float>(scale));
}

void nativeSetLanguageLock(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jfloat minimum,
                           jboolean strict) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    engine->setLanguageLock(static_cast<float>(minimum), strict == JNI_TRUE);
}

void nativeSetPreferredLanguage(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring tag) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    // A null tag clears the preference.
    if (tag == nullptr) {
        engine->setPreferredLanguage(nullptr);
        return;
    }
    char buffer[kStringBufferBytes];
    const jsize length = copyString(env, tag, buffer, sizeof(buffer));
    engine->setPreferredLanguage(length > 0 ? buffer : nullptr);
}

void nativeResetLanguageEvidence(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    engine->resetLanguageEvidence();
}

jfloat nativeLanguageEvidence(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring tag) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || tag == nullptr) {
        return 0.0f;
    }
    char buffer[kStringBufferBytes];
    const jsize length = copyString(env, tag, buffer, sizeof(buffer));
    return (length > 0) ? static_cast<jfloat>(engine->languageEvidence(buffer)) : 0.0f;
}

void nativeSetLanguageEvidence(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring tag,
                               jfloat evidence) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || tag == nullptr) {
        return;
    }
    char buffer[kStringBufferBytes];
    const jsize length = copyString(env, tag, buffer, sizeof(buffer));
    if (length > 0) {
        engine->setLanguageEvidence(buffer, static_cast<float>(evidence));
    }
}

void nativeLoadUserBigrams(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray languages,
                           jobjectArray previous, jobjectArray next, jintArray counts,
                           jlongArray contextSeen, jlongArray languageSeen) {
    loadLinkRows(env, handle, languages, std::array{previous, next}, counts, contextSeen,
                 languageSeen,
                 [](Engine& engine, const StringColumn& language, const auto& words,
                    const int32_t* uses, const uint64_t* byContext, const uint64_t* byLanguage,
                    int rows) {
                     const auto& [before, after] = words;
                     engine.loadUserBigrams(language.data(), language.lengths(), before.data(),
                                            before.lengths(), after.data(), after.lengths(), uses,
                                            rows, byContext, byLanguage);
                 });
}

void nativeLoadUserWords(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray words,
                         jintArray counts, jintArray deliberateCapitals, jintArray asserted) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || words == nullptr || counts == nullptr ||
        deliberateCapitals == nullptr || asserted == nullptr) {
        return;
    }
    const jsize wordCount = env->GetArrayLength(words);
    const jsize countLength = env->GetArrayLength(counts);
    const jsize capsLength = env->GetArrayLength(deliberateCapitals);
    const jsize assertedLength = env->GetArrayLength(asserted);
    // An empty list clears the words the engine holds.
    if (wordCount == 0) {
        engine->loadUserWords(nullptr, nullptr, nullptr, 0, nullptr, nullptr);
        return;
    }
    if (wordCount < 0 || countLength < wordCount || capsLength < wordCount ||
        assertedLength < wordCount || wordCount > kMaxUserWordsPerCall) {
        return;
    }

    StringColumn column(wordCount);
    NumberColumn<int32_t> countValues(wordCount);
    NumberColumn<int32_t> capsValues(wordCount);
    NumberColumn<int32_t> assertedValues(wordCount);
    if (!column.valid() || !countValues.valid() || !capsValues.valid() ||
        !assertedValues.valid()) {
        return;
    }

    if (!readInts(env, counts, countValues) || !readInts(env, deliberateCapitals, capsValues) ||
        !readInts(env, asserted, assertedValues)) {
        return;
    }

    // Compacted: a dropped word's count, capital flag and assertion drop with it.
    char** const storage = column.data();
    size_t* const lengths = column.lengths();
    int32_t* const counts32 = countValues.data();
    int32_t* const caps32 = capsValues.data();
    int32_t* const asserted32 = assertedValues.data();
    jsize kept = 0;
    for (jsize i = 0; i < wordCount; ++i) {
        char* copy = nullptr;
        const jsize length = copyStringAt(env, words, i, &copy);
        if (length <= 0) {
            continue;
        }
        storage[kept] = copy;
        lengths[kept] = static_cast<size_t>(length);
        counts32[kept] = counts32[i];
        caps32[kept] = caps32[i];
        asserted32[kept] = asserted32[i];
        ++kept;
    }

    engine->loadUserWords(storage, lengths, counts32, static_cast<int>(kept), caps32,
                          asserted32);
}

/** Replaces the blocked words; an empty array clears them. */
void nativeSetBlockedWords(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray words) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || words == nullptr) {
        return;
    }
    const jsize count = env->GetArrayLength(words);
    if (count == 0) {
        engine->setBlockedWords(nullptr, nullptr, 0);
        return;
    }
    if (count < 0 || count > kMaxUserWordsPerCall) {
        return;
    }
    StringColumn column(count);
    if (!column.valid()) {
        return;
    }
    column.copyFrom(env, words);
    engine->setBlockedWords(column.data(), column.lengths(), static_cast<int>(count));
}

jint nativeDecodeGesture(JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray xs,
                         jfloatArray ys, jlongArray ts, jint count, jstring prev1, jstring prev2,
                         jobjectArray outWords, jfloatArray outScores,
                         jbooleanArray outProperNoun, jobjectArray outTexts) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || xs == nullptr || ys == nullptr || outWords == nullptr ||
        outScores == nullptr || count < 2) {
        return 0;
    }
    jsize points = count;
    if (points > kMaxGesturePoints) {
        points = kMaxGesturePoints;
    }
    if (env->GetArrayLength(xs) < points || env->GetArrayLength(ys) < points) {
        return 0;
    }

    // Copied into stack buffers.
    jfloat pointsX[kMaxGesturePoints];
    jfloat pointsY[kMaxGesturePoints];
    jlong timestamps[kMaxGesturePoints];
    env->GetFloatArrayRegion(xs, 0, points, pointsX);
    env->GetFloatArrayRegion(ys, 0, points, pointsY);
    if (ts != nullptr && env->GetArrayLength(ts) >= points) {
        env->GetLongArrayRegion(ts, 0, points, timestamps);
    } else {
        std::memset(timestamps, 0, sizeof(jlong) * static_cast<size_t>(points));
    }
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return 0;
    }

    char prev1Buffer[kStringBufferBytes];
    char prev2Buffer[kStringBufferBytes];
    jsize prev1Length = copyString(env, prev1, prev1Buffer, sizeof(prev1Buffer));
    if (prev1Length < 0) {
        prev1Buffer[0] = '\0';
        prev1Length = 0;
    }
    jsize prev2Length = copyString(env, prev2, prev2Buffer, sizeof(prev2Buffer));
    if (prev2Length < 0) {
        prev2Buffer[0] = '\0';
        prev2Length = 0;
    }

    const jsize wordSlots = env->GetArrayLength(outWords);
    const jsize scoreSlots = env->GetArrayLength(outScores);
    jsize slots = (wordSlots < scoreSlots) ? wordSlots : scoreSlots;
    if (slots <= 0) {
        return 0;
    }
    if (slots > Engine::kMaxCandidates) {
        slots = Engine::kMaxCandidates;
    }

    Candidate candidates[Engine::kMaxCandidates];
    const int found = engine->decodeGesture(
        pointsX, pointsY, reinterpret_cast<const int64_t*>(timestamps), static_cast<int>(points),
        prev1Buffer, static_cast<size_t>(prev1Length), prev2Buffer,
        static_cast<size_t>(prev2Length), candidates, static_cast<int>(slots));
    writeLanguage(env, *engine, outTexts);
    // Scores already in [0, 1000].
    return writeRanking(env, *engine, candidates, found, outWords, outScores, outProperNoun);
}

const JNINativeMethod kMethods[] = {
    {"nativeCreate", "()J", reinterpret_cast<void*>(nativeCreate)},
    {"nativeDestroy", "(J)V", reinterpret_cast<void*>(nativeDestroy)},
    {"nativeLoadLanguage", "(JLjava/lang/String;IJJF)I",
     reinterpret_cast<void*>(nativeLoadLanguage)},
    {"nativeInspectPack", "(IJJ[I)Ljava/lang/String;",
     reinterpret_cast<void*>(nativeInspectPack)},
    {"nativeSetActiveLanguages", "(J[Ljava/lang/String;[F)V",
     reinterpret_cast<void*>(nativeSetActiveLanguages)},
    {"nativeSetKeyGeometry", "(J[I[F[FFF[I[I)V", reinterpret_cast<void*>(nativeSetKeyGeometry)},
    {"nativeAnswer",
     "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[F[F[Ljava/lang/String;[F[Z"
     "[Ljava/lang/String;[Ljava/lang/String;[Z[Z[Z[Z)I",
     reinterpret_cast<void*>(nativeAnswer)},
    {"nativeSetTouchModel", "(JZFI)V", reinterpret_cast<void*>(nativeSetTouchModel)},
    {"nativeSetTouchPatterns", "(J[I[F[F[F[F[F[F)V",
     reinterpret_cast<void*>(nativeSetTouchPatterns)},
    {"nativeLearn",
     "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZLjava/lang/String;Z)I",
     reinterpret_cast<void*>(nativeLearn)},
    {"nativeLoadUserWords", "(J[Ljava/lang/String;[I[I[I)V",
     reinterpret_cast<void*>(nativeLoadUserWords)},
    {"nativeSetBlockedWords", "(J[Ljava/lang/String;)V",
     reinterpret_cast<void*>(nativeSetBlockedWords)},
    {"nativeLoadUserBigrams",
     "(J[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[I[J[J)V",
     reinterpret_cast<void*>(nativeLoadUserBigrams)},
    {"nativeLoadUserPairTotals", "(J[Ljava/lang/String;[Ljava/lang/String;[J[J)V",
     reinterpret_cast<void*>(nativeLoadUserPairTotals)},
    {"nativeDropUserPairs", "(J[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V",
     reinterpret_cast<void*>(nativeDropUserPairs)},
    {"nativeSetLearnAfterUses", "(JI)V",
     reinterpret_cast<void*>(nativeSetLearnAfterUses)},
    {"nativeSetCorrectionStrictness", "(JF)V",
     reinterpret_cast<void*>(nativeSetCorrectionStrictness)},
    {"nativeSetKnownWordReach", "(JF)V", reinterpret_cast<void*>(nativeSetKnownWordReach)},
    {"nativeSetLanguageLock", "(JFZ)V",
     reinterpret_cast<void*>(nativeSetLanguageLock)},
    {"nativeSetPreferredLanguage", "(JLjava/lang/String;)V",
     reinterpret_cast<void*>(nativeSetPreferredLanguage)},
    {"nativeResetLanguageEvidence", "(J)V",
     reinterpret_cast<void*>(nativeResetLanguageEvidence)},
    {"nativeLanguageEvidence", "(JLjava/lang/String;)F",
     reinterpret_cast<void*>(nativeLanguageEvidence)},
    {"nativeSetLanguageEvidence", "(JLjava/lang/String;F)V",
     reinterpret_cast<void*>(nativeSetLanguageEvidence)},
    {"nativeSetPhraseSuggestions", "(JZ)V",
     reinterpret_cast<void*>(nativeSetPhraseSuggestions)},
    {"nativeLoadSwipeWeights", "(JI[B)Z", reinterpret_cast<void*>(nativeLoadSwipeWeights)},
    {"nativeSelectSwipeScript", "(JI)V", reinterpret_cast<void*>(nativeSelectSwipeScript)},
    {"nativeHasSwipeModel", "(JI)Z", reinterpret_cast<void*>(nativeHasSwipeModel)},
    {"nativeSetSwipeModelEnabled", "(JZ)V",
     reinterpret_cast<void*>(nativeSetSwipeModelEnabled)},
    {"nativeLastDecodeUsedNeural", "(J)Z",
     reinterpret_cast<void*>(nativeLastDecodeUsedNeural)},
    {"nativeWarmSwipeModel", "(J)Z", reinterpret_cast<void*>(nativeWarmSwipeModel)},
    {"nativeKnownStems", "(JLjava/lang/String;[Ljava/lang/String;[Z)I",
     reinterpret_cast<void*>(nativeKnownStems)},
    {"nativeExplainScore", "(JLjava/lang/String;Ljava/lang/String;[F)Z",
     reinterpret_cast<void*>(nativeExplainScore)},
    {"nativeLoadUserTrigrams",
     "(J[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[I[J[J)V",
     reinterpret_cast<void*>(nativeLoadUserTrigrams)},
    {"nativeLoadUserTripleTotals",
     "(J[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[J[J)V",
     reinterpret_cast<void*>(nativeLoadUserTripleTotals)},
    {"nativeDropUserTriples",
     "(J[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V",
     reinterpret_cast<void*>(nativeDropUserTriples)},
    {"nativeLoadUserClocks", "(J[Ljava/lang/String;[J[J)V",
     reinterpret_cast<void*>(nativeLoadUserClocks)},
    {"nativeContextLinks",
     "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[I[J[J)I",
     reinterpret_cast<void*>(nativeContextLinks)},
    {"nativeLinksHeld", "(JLjava/lang/String;Z)I", reinterpret_cast<void*>(nativeLinksHeld)},
    {"nativeLearnable", "(J[Ljava/lang/String;[Z)V", reinterpret_cast<void*>(nativeLearnable)},
    {"nativeLanguageLinks",
     "(JLjava/lang/String;Z[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[I[J[J)I",
     reinterpret_cast<void*>(nativeLanguageLinks)},
    {"nativeDecodeGesture",
     "(J[F[F[JILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;[F[Z[Ljava/lang/String;)I",
     reinterpret_cast<void*>(nativeDecodeGesture)},
    {"nativeSetPersonalModelEnabled", "(JZ)V",
     reinterpret_cast<void*>(nativeSetPersonalModelEnabled)},
    {"nativeDominantLanguageTag", "(J)Ljava/lang/String;",
     reinterpret_cast<void*>(nativeDominantLanguageTag)},
    {"nativePackTag", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(nativePackTag)},
    {"nativeAnswerAs",
     "(JILjava/lang/String;[Ljava/lang/String;[F[Z[Ljava/lang/String;[Ljava/lang/String;[Z[Z"
     "[Z[Z)I",
     reinterpret_cast<void*>(nativeAnswerAs)},
    {"nativeKnownSpelling", "(JLjava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(nativeKnownSpelling)},
    {"nativeDominantPack", "(J)I", reinterpret_cast<void*>(nativeDominantPack)},
};

const JNINativeMethod kTextMethods[] = {
    {"lowerCase", "(Ljava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(nativeLowerCase)},
};

/**
 * Registers [methods] on [className] when the class path holds it, as a module's own tests leave
 * the other module's class out. False when a class held refuses them.
 */
bool registerOn(JNIEnv* env, const char* className, const JNINativeMethod* methods, jint count) {
    jclass found = env->FindClass(className);
    if (found == nullptr) {
        env->ExceptionClear();
        return true;
    }
    // A signature that does not match its Kotlin declaration fails the load.
    const jint registered = env->RegisterNatives(found, methods, count);
    env->DeleteLocalRef(found);
    return registered == JNI_OK;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    const jint methods = sizeof(kMethods) / sizeof(kMethods[0]);
    const jint textMethods = sizeof(kTextMethods) / sizeof(kTextMethods[0]);
    if (!registerOn(env, kPredictorClass, kMethods, methods) ||
        !registerOn(env, kTextClass, kTextMethods, textMethods)) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
