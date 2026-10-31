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
#include <vector>

#include "engine.hpp"
#include "jni_slots.hpp"

// The JNI bridge, the only file in this library that uses JNI. Methods are registered in
// JNI_OnLoad; errors cross as return values. On the typing path nothing allocates per call but
// the result strings; a slice pushed, a word put and the loads copy their arrays. Runs on the
// prediction thread.

namespace {

using borderkeys::Candidate;
using borderkeys::ContextSlice;
using borderkeys::Engine;
using borderkeys::WordTable;
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

/**
 * Writes the tag of the pack the request just served leaned on (Engine::answerPack) into
 * `outTexts`; nothing with no pack active.
 */
void writeAnswerPack(JNIEnv* env, const Engine& engine, jobjectArray outTexts) {
    const char* const tag = engine.packTag(engine.answerPack());
    if (tag != nullptr && outTexts != nullptr && env->GetArrayLength(outTexts) >= kTextSlots) {
        setText(env, outTexts, kTextAnswerPack, tag);
    }
}

/**
 * The context the request's `personalContext` names: its kPersonalContextSlots ints, the
 * language and the two words before as the slice's header holds them; none when it is null,
 * short or unreadable.
 */
ContextSlice::Key personalContextOf(JNIEnv* env, jintArray personalContext) {
    ContextSlice::Key key;
    if (personalContext == nullptr ||
        env->GetArrayLength(personalContext) < kPersonalContextSlots) {
        return key;
    }
    jint slots[kPersonalContextSlots] = {};
    env->GetIntArrayRegion(personalContext, 0, kPersonalContextSlots, slots);
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return key;
    }
    key.language = slots[ContextSlice::kLanguageField];
    key.context1 = slots[ContextSlice::kContext1Field];
    key.context2 = slots[ContextSlice::kContext2Field];
    return key;
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
            jintArray personalContext, jfloatArray tapXs, jfloatArray tapYs, jobjectArray outWords,
            jfloatArray outScores,
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
    const ContextSlice::Key personal = personalContextOf(env, personalContext);

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
                                      tapCount, candidates, static_cast<int>(slots), personal);
    // The pack the answer leaned on, for every request.
    writeAnswerPack(env, *engine, outTexts);
    // For a typed word: how the dictionaries spell it, its possessive, and autocorrect's list.
    if (composingLength > 0) {
        writeTexts(env, *engine, composingBuffer, static_cast<size_t>(composingLength), outTexts,
                   outCorrections, outCorrectionNames, outCorrectionSlips, outCorrectionConfident,
                   outSpellingFlags);
    }
    return writeRanking(env, *engine, candidates, found, outWords, outScores, outProperNoun);
}

jint nativeAnswer(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring composing, jstring prev1,
                  jstring prev2, jintArray personalContext, jfloatArray tapXs, jfloatArray tapYs,
                  jobjectArray outWords, jfloatArray outScores, jbooleanArray outProperNoun,
                  jobjectArray outTexts, jobjectArray outCorrections,
                  jbooleanArray outCorrectionNames, jbooleanArray outCorrectionSlips,
                  jbooleanArray outCorrectionConfident, jbooleanArray outSpellingFlags) {
    return answer(env, engineFrom(handle), composing, prev1, prev2, personalContext, tapXs, tapYs,
                  outWords, outScores, outProperNoun, outTexts, outCorrections,
                  outCorrectionNames, outCorrectionSlips, outCorrectionConfident,
                  outSpellingFlags);
}

// nativeAnswer for `composing` alone, with no words before it and no taps, as if `packIndex`
// were the language being written; -1 when that pack is not open and active.
jint nativeAnswerAs(JNIEnv* env, jobject /*thiz*/, jlong handle, jint packIndex,
                    jstring composing, jintArray personalContext, jobjectArray outWords,
                    jfloatArray outScores,
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
    return answer(env, engine, composing, nullptr, nullptr, personalContext, nullptr, nullptr,
                  outWords, outScores, outProperNoun, outTexts, outCorrections,
                  outCorrectionNames, outCorrectionSlips, outCorrectionConfident,
                  outSpellingFlags);
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

/** Writes `value` into `array[slot]`, an exception cleared. */
template <typename Array, typename Value, typename Write>
void setNumber(JNIEnv* env, Array array, jsize slot, Value value, Write write) {
    (env->*write)(array, slot, 1, &value);
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
    }
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

static_assert(Engine::kMaxPacks < 32, "each pack's bit fits a Java int");

/**
 * The active packs that spell each of `words` (Engine::packsSpelling), one bit per slot, into
 * `outSlots` up to its size; a word the bridge cannot take is left as `outSlots` holds it.
 */
void nativePacksSpelling(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray words,
                         jintArray outSlots) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || words == nullptr || outSlots == nullptr) {
        return;
    }
    const jsize count = std::min(env->GetArrayLength(words), env->GetArrayLength(outSlots));
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
        const jint slots =
            static_cast<jint>(engine->packsSpelling(buffer, static_cast<size_t>(length)));
        setNumber(env, outSlots, i, slots, &JNIEnv::SetIntArrayRegion);
    }
}

/** The score of `candidate` as an answer to `typed`, term by term, into `out` in
 *  ScoreExplanation.kt's slot order; false when the word is not offered. */
jboolean nativeExplainScore(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring typed,
                            jstring candidate, jintArray personalContext, jfloatArray out) {
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
                              static_cast<size_t>(candidateLength), &parts,
                              personalContextOf(env, personalContext))) {
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
        static_cast<jfloat>(parts.pastTyped),
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

static_assert(kWordCount == WordTable::kCountField &&
                  kWordDeliberateCapitals == WordTable::kDeliberateCapitalsField &&
                  kWordAsserted == WordTable::kAssertedField &&
                  kWordAdded == WordTable::kAddedField && kWordFields == WordTable::kFields &&
                  kNewWordId == WordTable::kNewWord,
              "the bridge's word slots are the word table's");

/**
 * A word table call's rows, read from Kotlin: each row's spelling, null or unreadable for none,
 * and its kWordFields counts; the ids and known bits the engine writes back, kNewWordId and
 * false until it does.
 */
class WordRows {
public:
    WordRows(JNIEnv* env, jobjectArray spellings, jintArray fields, jsize rows)
        : rows_(rows),
          spellings_(rows),
          fields_(rows * kWordFields),
          ids_(rows),
          known_(rows > 0 ? new (std::nothrow) bool[rows] : nullptr) {
        valid_ = rows > 0 && spellings_.valid() && fields_.valid() && ids_.valid() &&
                 known_ != nullptr && readInts(env, fields, fields_);
        if (valid_) {
            spellings_.copyFrom(env, spellings);
            std::fill(ids_.data(), ids_.data() + rows, kNewWordId);
            std::fill(known_.get(), known_.get() + rows, false);
        }
    }

    bool valid() const { return valid_; }
    const char* const* spellings() const { return spellings_.data(); }
    const size_t* lengths() const { return spellings_.lengths(); }
    const int32_t* fields() const { return fields_.data(); }
    int32_t* ids() const { return ids_.data(); }
    bool* known() const { return known_.get(); }

    /** Writes the ids, and the known bits when `outKnown` is given, back to Kotlin. */
    void writeBack(JNIEnv* env, jintArray outIds, jbooleanArray outKnown) const {
        env->SetIntArrayRegion(outIds, 0, rows_, reinterpret_cast<const jint*>(ids_.data()));
        if (outKnown != nullptr) {
            std::unique_ptr<jboolean[]> bits(new (std::nothrow) jboolean[rows_]);
            if (bits != nullptr) {
                for (jsize i = 0; i < rows_; ++i) {
                    bits[i] = known_[i] ? JNI_TRUE : JNI_FALSE;
                }
                env->SetBooleanArrayRegion(outKnown, 0, rows_, bits.get());
            }
        }
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
        }
    }

private:
    jsize rows_;
    StringColumn spellings_;
    NumberColumn<int32_t> fields_;
    NumberColumn<int32_t> ids_;
    std::unique_ptr<bool[]> known_;
    bool valid_ = false;
};

/**
 * Whether a word table call's arrays agree: `fields` holds kWordFields ints per row of at most
 * kMaxWordRowsPerCall (wordFieldsAgree), `outIds` one id per row, `outKnown`, when given, one bit
 * per row, and `ids`, when given, one id per row.
 */
bool rowsAgree(JNIEnv* env, jsize rows, jintArray fields, jintArray outIds, jbooleanArray outKnown,
               jintArray ids) {
    return fields != nullptr && outIds != nullptr &&
           wordFieldsAgree(rows, env->GetArrayLength(fields)) &&
           env->GetArrayLength(outIds) == rows &&
           (outKnown == nullptr || env->GetArrayLength(outKnown) == rows) &&
           (ids == nullptr || env->GetArrayLength(ids) == rows);
}

jint nativeLoadWordTable(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray spellings,
                         jintArray fields, jintArray outIds, jbooleanArray outKnown) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || spellings == nullptr) {
        return 0;
    }
    const jsize rows = env->GetArrayLength(spellings);
    if (!rowsAgree(env, rows, fields, outIds, outKnown, nullptr)) {
        return 0;
    }
    // An empty table clears the words the engine holds.
    if (rows == 0) {
        return engine->loadWordTable(nullptr, nullptr, nullptr, 0, nullptr);
    }
    WordRows read(env, spellings, fields, rows);
    if (!read.valid()) {
        return 0;
    }
    const int held = engine->loadWordTable(read.spellings(), read.lengths(), read.fields(),
                                           static_cast<int>(rows), read.ids(), read.known());
    read.writeBack(env, outIds, outKnown);
    return held;
}

jint nativePutWords(JNIEnv* env, jobject /*thiz*/, jlong handle, jintArray ids,
                    jobjectArray spellings, jintArray fields, jintArray outIds,
                    jbooleanArray outKnown) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || ids == nullptr || spellings == nullptr) {
        return 0;
    }
    const jsize rows = env->GetArrayLength(spellings);
    if (rows == 0 || !rowsAgree(env, rows, fields, outIds, outKnown, ids)) {
        return 0;
    }
    WordRows read(env, spellings, fields, rows);
    NumberColumn<int32_t> named(rows);
    if (!read.valid() || !named.valid() || !readInts(env, ids, named)) {
        return 0;
    }
    const int held = engine->putWords(named.data(), read.spellings(), read.lengths(),
                                      read.fields(), static_cast<int>(rows), read.ids(),
                                      read.known());
    read.writeBack(env, outIds, outKnown);
    return held;
}

/**
 * The personal word table's entry for `word`, in any case: its kWordFields counts into
 * `outFields`, its spelling into `outSpelling[0]`, and whether an active pack knows it into
 * `outKnown[0]`. Returns its id, kNewWordId for a word the table does not hold.
 */
jint nativeWordEntry(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring word, jintArray outFields,
                     jobjectArray outSpelling, jbooleanArray outKnown) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || outFields == nullptr || outSpelling == nullptr ||
        outKnown == nullptr || env->GetArrayLength(outFields) < kWordFields ||
        env->GetArrayLength(outSpelling) < 1 || env->GetArrayLength(outKnown) < 1) {
        return kNewWordId;
    }
    char buffer[kStringBufferBytes];
    const jsize length = copyString(env, word, buffer, sizeof(buffer));
    const WordTable& words = engine->personalWords();
    const int32_t id =
        length > 0 ? words.entryIndexFor(buffer, static_cast<size_t>(length)) : kNewWordId;
    if (id < 0) {
        return kNewWordId;
    }
    const uint32_t entry = static_cast<uint32_t>(id);
    jint fields[kWordFields] = {};
    fields[kWordCount] = static_cast<jint>(words.entryCount(entry));
    fields[kWordDeliberateCapitals] = static_cast<jint>(words.deliberateCapitals(entry));
    fields[kWordAsserted] = static_cast<jint>(words.asserted(entry));
    fields[kWordAdded] = static_cast<jint>(words.added(entry));
    env->SetIntArrayRegion(outFields, 0, kWordFields, fields);
    bool known = false;
    engine->markKnown(&id, 1, &known);
    const jboolean bit = known ? JNI_TRUE : JNI_FALSE;
    env->SetBooleanArrayRegion(outKnown, 0, 1, &bit);
    uint32_t textLength = 0;
    const char* const text = words.entryText(entry, &textLength);
    if (text != nullptr && textLength < static_cast<uint32_t>(kStringBufferBytes)) {
        char spelled[kStringBufferBytes];
        std::memcpy(spelled, text, textLength);
        spelled[textLength] = '\0';
        setText(env, outSpelling, 0, spelled);
    }
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
    }
    return id;
}

static_assert(kSliceHeaderFields == ContextSlice::kHeaderFields &&
                  kSliceLinkFields == ContextSlice::kLinkFields &&
                  kSliceHopFields == ContextSlice::kHopFields &&
                  kSliceContextSeenFields == ContextSlice::kContextSeenFields &&
                  kSliceMaxHops == ContextSlice::kMaxHops && kSliceNone == ContextSlice::kNone &&
                  kPersonalContextSlots == ContextSlice::kContext2Field + 1,
              "the bridge's slice slots are the slice's");

/** A Java int or long array's values, read whole, released on destruction. */
template <typename Array, typename Value>
class HandedArray {
public:
    using Get = Value* (JNIEnv::*)(Array, jboolean*);
    using Release = void (JNIEnv::*)(Array, Value*, jint);

    HandedArray(JNIEnv* env, Array array, Get get, Release release)
        : env_(env), array_(array), release_(release),
          length_(array != nullptr ? env->GetArrayLength(array) : 0),
          values_(array != nullptr ? (env->*get)(array, nullptr) : nullptr) {}
    ~HandedArray() {
        if (values_ != nullptr) {
            (env_->*release_)(array_, values_, JNI_ABORT);
        }
    }

    HandedArray(const HandedArray&) = delete;
    HandedArray& operator=(const HandedArray&) = delete;

    bool valid() const { return array_ != nullptr && values_ != nullptr; }
    const Value* values() const { return values_; }
    size_t length() const { return static_cast<size_t>(length_); }

private:
    JNIEnv* env_;
    Array array_;
    Release release_;
    jsize length_;
    Value* values_;
};

/**
 * How many of an array of `held` values a slice takes when its header counts `wanted`: `wanted`
 * when the array holds that many, else all it holds, which the slice then refuses.
 */
size_t sliceLength(int64_t wanted, size_t held) {
    const bool room = wanted >= 0 && static_cast<uint64_t>(wanted) <= held;
    return room ? static_cast<size_t>(wanted) : held;
}

/**
 * Replaces the slice the requests read their personal pairs and triples from with `header` and
 * the links, hops and stamps it counts from the start of `links`, `hops` and `seen`, each array
 * holding at least as many (Engine::pushSlice). False, the slice emptied, when one holds fewer or
 * the header is malformed.
 */
jboolean nativePushSlice(JNIEnv* env, jobject /*thiz*/, jlong handle, jintArray header,
                         jintArray links, jintArray hops, jlongArray seen) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return JNI_FALSE;
    }
    const HandedArray<jintArray, jint> headerInts(env, header, &JNIEnv::GetIntArrayElements,
                                                  &JNIEnv::ReleaseIntArrayElements);
    const HandedArray<jintArray, jint> linkInts(env, links, &JNIEnv::GetIntArrayElements,
                                                &JNIEnv::ReleaseIntArrayElements);
    const HandedArray<jintArray, jint> hopInts(env, hops, &JNIEnv::GetIntArrayElements,
                                               &JNIEnv::ReleaseIntArrayElements);
    const HandedArray<jlongArray, jlong> seenLongs(env, seen, &JNIEnv::GetLongArrayElements,
                                                   &JNIEnv::ReleaseLongArrayElements);
    if (!headerInts.valid() || !linkInts.valid() || !hopInts.valid() || !seenLongs.valid()) {
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
        }
        engine->pushSlice(nullptr, 0, nullptr, 0, nullptr, 0, nullptr, 0);
        return JNI_FALSE;
    }
    const jint* const counts = headerInts.values();
    const bool counted = headerInts.length() == static_cast<size_t>(kSliceHeaderFields);
    const int64_t linksCounted = counted ? static_cast<int64_t>(counts[ContextSlice::kPairsField]) +
                                               counts[ContextSlice::kTriplesField]
                                         : -1;
    const int64_t hopsCounted = counted ? counts[ContextSlice::kHopsField] : -1;
    const bool held = engine->pushSlice(
        reinterpret_cast<const int32_t*>(counts), headerInts.length(),
        reinterpret_cast<const int32_t*>(linkInts.values()),
        sliceLength(linksCounted < 0 ? -1 : linksCounted * kSliceLinkFields, linkInts.length()),
        reinterpret_cast<const int32_t*>(hopInts.values()),
        sliceLength(hopsCounted < 0 ? -1 : hopsCounted * kSliceHopFields, hopInts.length()),
        reinterpret_cast<const int64_t*>(seenLongs.values()),
        sliceLength(linksCounted < 0 ? -1 : linksCounted + kSliceContextSeenFields,
                    seenLongs.length()));
    return held ? JNI_TRUE : JNI_FALSE;
}

/**
 * The slice the engine holds, as nativePushSlice takes it: its header into `outHeader`, its links,
 * hops and stamps into `outLinks`, `outHops` and `outSeen` up to their sizes. False, writing
 * nothing, when the engine is gone or `outHeader` holds fewer than kSliceHeaderFields.
 */
jboolean nativeReadSlice(JNIEnv* env, jobject /*thiz*/, jlong handle, jintArray outHeader,
                         jintArray outLinks, jintArray outHops, jlongArray outSeen) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || outHeader == nullptr || outLinks == nullptr || outHops == nullptr ||
        outSeen == nullptr || env->GetArrayLength(outHeader) < kSliceHeaderFields) {
        return JNI_FALSE;
    }
    const ContextSlice& slice = engine->slice();
    jint header[kSliceHeaderFields] = {};
    header[ContextSlice::kLanguageField] = slice.key().language;
    header[ContextSlice::kContext1Field] = slice.key().context1;
    header[ContextSlice::kContext2Field] = slice.key().context2;
    header[ContextSlice::kPairTotalField] = static_cast<jint>(slice.pairTotal());
    header[ContextSlice::kTripleTotalField] = static_cast<jint>(slice.tripleTotal());
    header[ContextSlice::kPairsField] = slice.pairCount();
    header[ContextSlice::kTriplesField] = slice.tripleCount();
    header[ContextSlice::kHopsField] = slice.hopCount();
    env->SetIntArrayRegion(outHeader, 0, kSliceHeaderFields, header);
    const int links = slice.pairCount() + slice.tripleCount();
    std::vector<jint> linkInts;
    std::vector<jlong> seen = {static_cast<jlong>(slice.pairSeen()),
                               static_cast<jlong>(slice.tripleSeen())};
    for (int i = 0; i < links; ++i) {
        const ContextSlice::Link& link =
            i < slice.pairCount() ? slice.pair(i) : slice.triple(i - slice.pairCount());
        linkInts.insert(linkInts.end(), {link.word, static_cast<jint>(link.count),
                                         static_cast<jint>(link.asserted)});
        seen.push_back(static_cast<jlong>(link.seen));
    }
    std::vector<jint> hopInts;
    for (int i = 0; i < slice.hopCount(); ++i) {
        const ContextSlice::Hop& hop = slice.hop(i);
        hopInts.insert(hopInts.end(), {hop.word, static_cast<jint>(hop.count),
                                       static_cast<jint>(hop.asserted),
                                       static_cast<jint>(hop.middleTotal)});
    }
    const auto upTo = [env](auto array, size_t length) {
        const auto room = static_cast<size_t>(env->GetArrayLength(array));
        return static_cast<jsize>(std::min(length, room));
    };
    env->SetIntArrayRegion(outLinks, 0, upTo(outLinks, linkInts.size()), linkInts.data());
    env->SetIntArrayRegion(outHops, 0, upTo(outHops, hopInts.size()), hopInts.data());
    env->SetLongArrayRegion(outSeen, 0, upTo(outSeen, seen.size()), seen.data());
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
    }
    return JNI_TRUE;
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
                         jintArray personalContext, jobjectArray outWords, jfloatArray outScores,
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

    const ContextSlice::Key personal = personalContextOf(env, personalContext);

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
        static_cast<size_t>(prev2Length), candidates, static_cast<int>(slots), personal);
    writeAnswerPack(env, *engine, outTexts);
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
     "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[I[F[F"
     "[Ljava/lang/String;[F[Z[Ljava/lang/String;[Ljava/lang/String;[Z[Z[Z[Z)I",
     reinterpret_cast<void*>(nativeAnswer)},
    {"nativeSetTouchModel", "(JZFI)V", reinterpret_cast<void*>(nativeSetTouchModel)},
    {"nativeSetTouchPatterns", "(J[I[F[F[F[F[F[F)V",
     reinterpret_cast<void*>(nativeSetTouchPatterns)},
    {"nativeLoadWordTable", "(J[Ljava/lang/String;[I[I[Z)I",
     reinterpret_cast<void*>(nativeLoadWordTable)},
    {"nativePutWords", "(J[I[Ljava/lang/String;[I[I[Z)I",
     reinterpret_cast<void*>(nativePutWords)},
    {"nativeWordEntry", "(JLjava/lang/String;[I[Ljava/lang/String;[Z)I",
     reinterpret_cast<void*>(nativeWordEntry)},
    {"nativePushSlice", "(J[I[I[I[J)Z", reinterpret_cast<void*>(nativePushSlice)},
    {"nativeReadSlice", "(J[I[I[I[J)Z", reinterpret_cast<void*>(nativeReadSlice)},
    {"nativeSetBlockedWords", "(J[Ljava/lang/String;)V",
     reinterpret_cast<void*>(nativeSetBlockedWords)},
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
    {"nativeExplainScore", "(JLjava/lang/String;Ljava/lang/String;[I[F)Z",
     reinterpret_cast<void*>(nativeExplainScore)},
    {"nativeLearnable", "(J[Ljava/lang/String;[Z)V", reinterpret_cast<void*>(nativeLearnable)},
    {"nativePacksSpelling", "(J[Ljava/lang/String;[I)V",
     reinterpret_cast<void*>(nativePacksSpelling)},
    {"nativeDecodeGesture",
     "(J[F[F[JILjava/lang/String;Ljava/lang/String;[I[Ljava/lang/String;[F[Z"
     "[Ljava/lang/String;)I",
     reinterpret_cast<void*>(nativeDecodeGesture)},
    {"nativeSetPersonalModelEnabled", "(JZ)V",
     reinterpret_cast<void*>(nativeSetPersonalModelEnabled)},
    {"nativeDominantLanguageTag", "(J)Ljava/lang/String;",
     reinterpret_cast<void*>(nativeDominantLanguageTag)},
    {"nativePackTag", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(nativePackTag)},
    {"nativeAnswerAs",
     "(JILjava/lang/String;[I[Ljava/lang/String;[F[Z[Ljava/lang/String;"
     "[Ljava/lang/String;[Z[Z[Z[Z)I",
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
