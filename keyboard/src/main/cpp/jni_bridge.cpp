// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors


#include <cstddef>
#include <cstdint>
#include <cstring>
#include <jni.h>
#include <new>

#include "engine.hpp"

// The JNI bridge, the only file in this library that uses JNI. Methods are registered in
// JNI_OnLoad; errors cross as return values; nothing allocates per call but the result strings.
// Runs on the prediction thread.

namespace {

using borderkeys::Candidate;
using borderkeys::Engine;

// The longest word the engine takes, in UTF-16 units; modified UTF-8 takes at most three bytes
// each.
constexpr jsize kMaxStringUnits = 64;
constexpr jsize kStringBufferBytes = kMaxStringUnits * 3 + 1;

constexpr int kMaxUserWordsPerCall = 20000;

// The most gesture points taken; the same as GestureCapture's capacity.
constexpr jsize kMaxGesturePoints = 512;

// The slots of nativeAnswer's `outTexts`, the same as NativePredictor's TEXT_ constants.
constexpr jsize kTextKnownSpelling = 0;
constexpr jsize kTextPossessive = 1;
constexpr jsize kTextCorrection = 2;
constexpr jsize kTextSlots = 3;

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
 * Describes a `.bkd` without loading it: fills `out` with { status, formatVersion, wordCount }
 * and returns the language tag, or null when the pack was refused, `out[0]` then holding the
 * BkdStatus.
 */
jstring nativeInspectPack(JNIEnv* env, jobject /*thiz*/, jint fd, jlong offset, jlong length,
                          jintArray out) {
    if (out == nullptr || env->GetArrayLength(out) < 3) {
        return nullptr;
    }
    borderkeys::PackInfo info = {};
    const int32_t status = borderkeys::bkdInspectPack(
        static_cast<int>(fd), static_cast<int64_t>(offset), static_cast<int64_t>(length), &info);

    jint values[3] = {static_cast<jint>(status), 0, 0};
    if (status == borderkeys::kBkdOk) {
        values[1] = static_cast<jint>(info.formatVersion);
        values[2] = static_cast<jint>(info.wordCount);
    }
    env->SetIntArrayRegion(out, 0, 3, values);
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
                          jfloat keyHeight) {
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

    engine->setKeyGeometry(reinterpret_cast<const int32_t*>(codeBuffer), xBuffer, yBuffer,
                           static_cast<int>(limited), static_cast<float>(keyWidth),
                           static_cast<float>(keyHeight));
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
 * Fills `outTexts` for the typed [word]: how the dictionaries spell it, its possessive, and
 * autocorrect's answer to the request just served; a slot with none is left as it is.
 * `outCorrectionName[0]` is set to whether that answer is a name.
 */
void writeTexts(JNIEnv* env, const Engine& engine, const char* word, size_t length,
                jobjectArray outTexts, jbooleanArray outCorrectionName) {
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
    const Candidate* const best = engine.bestCorrection();
    if (best == nullptr) {
        return;
    }
    uint32_t bestLength = 0;
    const char* const source = engine.candidateText(*best, &bestLength);
    if (source == nullptr || bestLength == 0 || bestLength >= kStringBufferBytes) {
        return;
    }
    if (outCorrectionName != nullptr && env->GetArrayLength(outCorrectionName) > 0) {
        const jboolean isName = engine.candidateIsProperNoun(*best) ? JNI_TRUE : JNI_FALSE;
        env->SetBooleanArrayRegion(outCorrectionName, 0, 1, &isName);
    }
    std::memcpy(text, source, bestLength);
    text[bestLength] = '\0';
    setText(env, outTexts, kTextCorrection, text);
}

jint nativeAnswer(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring composing, jstring prev1,
                  jstring prev2, jobjectArray outWords, jfloatArray outScores,
                  jbooleanArray outProperNoun, jintArray outCorrectionIndex,
                  jobjectArray outTexts, jbooleanArray outCorrectionName) {
    Engine* const engine = engineFrom(handle);
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

    Candidate candidates[Engine::kMaxCandidates];
    const int found = engine->suggest(composingBuffer, static_cast<size_t>(composingLength),
                                      prev1Buffer, static_cast<size_t>(prev1Length),
                                      prev2Buffer, static_cast<size_t>(prev2Length), candidates,
                                      static_cast<int>(slots));
    // For a typed word: how the dictionaries spell it, its possessive, and autocorrect's answer.
    if (composingLength > 0) {
        writeTexts(env, *engine, composingBuffer, static_cast<size_t>(composingLength), outTexts,
                   outCorrectionName);
    }
    if (found <= 0) {
        return 0;
    }

    float scores[Engine::kMaxCandidates];
    jboolean properNoun[Engine::kMaxCandidates];
    const Candidate* const correction = engine->bestCorrection();
    jint correctionIndex = -1;
    int written = 0;
    char text[kStringBufferBytes];
    for (int i = 0; i < found; ++i) {
        uint32_t length = 0;
        const char* const source = engine->candidateText(candidates[i], &length);
        if (source == nullptr || length == 0 || length >= sizeof(text)) {
            continue;
        }
        std::memcpy(text, source, length);
        text[length] = '\0';

        // The one allocation on this path, into the caller's reused array.
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
        properNoun[written] = engine->candidateIsProperNoun(candidates[i]) ? JNI_TRUE : JNI_FALSE;
        // Which of these is the correction, by pack and word; -1 when none is.
        if (correction != nullptr && candidates[i].packIndex == correction->packIndex &&
            candidates[i].wordIndex == correction->wordIndex) {
            correctionIndex = written;
        }
        ++written;
    }

    if (outCorrectionIndex != nullptr && env->GetArrayLength(outCorrectionIndex) > 0) {
        env->SetIntArrayRegion(outCorrectionIndex, 0, 1, &correctionIndex);
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
        }
    }

    if (written > 0) {
        env->SetFloatArrayRegion(outScores, 0, written, scores);
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            return 0;
        }
        env->SetBooleanArrayRegion(outProperNoun, 0, written, properNoun);
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            return 0;
        }
    }
    return written;
}

void nativeLearn(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring word, jstring prev1,
                 jstring prev2, jboolean deliberateCapital, jboolean asserted) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    char wordBuffer[kStringBufferBytes];
    char prev1Buffer[kStringBufferBytes];
    char prev2Buffer[kStringBufferBytes];

    const jsize wordLength = copyString(env, word, wordBuffer, sizeof(wordBuffer));
    if (wordLength <= 0) {
        return;
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

    engine->learn(wordBuffer, static_cast<size_t>(wordLength), prev1Buffer,
                  static_cast<size_t>(prev1Length), prev2Buffer,
                  static_cast<size_t>(prev2Length), deliberateCapital == JNI_TRUE,
                  asserted == JNI_TRUE);
}

/**
 * Copies a Java string array into newly allocated C strings, for the bulk loaders. Returns false
 * and frees everything on any failure.
 */
bool copyStringArray(JNIEnv* env, jobjectArray array, jsize count, char** storage,
                     size_t* lengths) {
    for (jsize i = 0; i < count; ++i) {
        storage[i] = nullptr;
        lengths[i] = 0;
    }
    for (jsize i = 0; i < count; ++i) {
        jstring value = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        char buffer[kStringBufferBytes];
        const jsize length = copyString(env, value, buffer, sizeof(buffer));
        if (value != nullptr) {
            // One local reference at a time.
            env->DeleteLocalRef(value);
        }
        if (length <= 0) {
            continue;
        }
        char* const copy = new (std::nothrow) char[length];
        if (copy == nullptr) {
            continue;
        }
        std::memcpy(copy, buffer, static_cast<size_t>(length));
        storage[i] = copy;
        lengths[i] = static_cast<size_t>(length);
    }
    return true;
}

/** One Java string array's worth of copied C strings, freed on destruction. */
class StringColumn {
public:
    explicit StringColumn(jsize count)
        : count_(count),
          store_(count > 0 ? new (std::nothrow) char*[count] : nullptr),
          lengths_(count > 0 ? new (std::nothrow) size_t[count] : nullptr) {
        if (store_ != nullptr) {
            for (jsize i = 0; i < count_; ++i) {
                store_[i] = nullptr;
                lengths_[i] = 0;
            }
        }
    }

    ~StringColumn() {
        if (store_ != nullptr) {
            for (jsize i = 0; i < count_; ++i) {
                delete[] store_[i];
            }
        }
        delete[] store_;
        delete[] lengths_;
    }

    StringColumn(const StringColumn&) = delete;
    StringColumn& operator=(const StringColumn&) = delete;

    bool valid() const { return store_ != nullptr && lengths_ != nullptr; }
    void copyFrom(JNIEnv* env, jobjectArray array) {
        copyStringArray(env, array, count_, store_, lengths_);
    }
    char** data() const { return store_; }
    size_t* lengths() const { return lengths_; }

private:
    jsize count_;
    char** store_;
    size_t* lengths_;
};

/** A bulk loader's `int32_t` counts, read with `GetIntArrayRegion`, freed on destruction. */
class Int32Column {
public:
    explicit Int32Column(jsize count)
        : data_(count > 0 ? new (std::nothrow) int32_t[count] : nullptr) {}
    ~Int32Column() { delete[] data_; }

    Int32Column(const Int32Column&) = delete;
    Int32Column& operator=(const Int32Column&) = delete;

    bool valid() const { return data_ != nullptr; }
    int32_t* data() const { return data_; }

private:
    int32_t* data_;
};

/** Loads the remembered three-word sequences, as parallel string arrays, after the pairs. */
void nativeLoadUserTrigrams(JNIEnv* env, jobject /*thiz*/, jlong handle,
                            jobjectArray previous2, jobjectArray previous1, jobjectArray next,
                            jintArray counts) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || previous2 == nullptr || previous1 == nullptr || next == nullptr ||
        counts == nullptr) {
        return;
    }
    const jsize tripleCount = env->GetArrayLength(previous2);
    // An empty list clears the triples the engine holds.
    if (tripleCount == 0) {
        engine->loadUserTrigrams(nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, 0);
        return;
    }
    if (tripleCount < 0 || env->GetArrayLength(previous1) < tripleCount ||
        env->GetArrayLength(next) < tripleCount ||
        env->GetArrayLength(counts) < tripleCount || tripleCount > kMaxUserWordsPerCall) {
        return;
    }

    StringColumn col2(tripleCount);
    StringColumn col1(tripleCount);
    StringColumn colNext(tripleCount);
    Int32Column countValues(tripleCount);
    if (!col2.valid() || !col1.valid() || !colNext.valid() || !countValues.valid()) {
        return;
    }

    env->GetIntArrayRegion(counts, 0, tripleCount, reinterpret_cast<jint*>(countValues.data()));
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return;
    }

    col2.copyFrom(env, previous2);
    col1.copyFrom(env, previous1);
    colNext.copyFrom(env, next);

    engine->loadUserTrigrams(col2.data(), col2.lengths(), col1.data(), col1.lengths(),
                             colNext.data(), colNext.lengths(), countValues.data(),
                             static_cast<int>(tripleCount));
}

/** The score of `candidate` as an answer to `typed`, term by term, into `out` in
 *  ScoreExplanation.kt's slot order; false when the word is not offered. */
jboolean nativeExplainScore(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring typed,
                            jstring candidate, jfloatArray out) {
    constexpr jsize kSlots = 9;
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
    };
    env->SetFloatArrayRegion(out, 0, kSlots, values);
    return JNI_TRUE;
}

/** Which of `stems`, at most kMaxStemsQuery, the engine vouches for; see
 *  Engine::vouchesForStem. */
jint nativeKnownStems(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray words,
                      jbooleanArray outKnown) {
    constexpr jsize kMaxStemsQuery = 64;
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || words == nullptr || outKnown == nullptr) {
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
        flags[i] = (length > 0 && engine->vouchesForStem(buffer, static_cast<size_t>(length)))
            ? JNI_TRUE
            : JNI_FALSE;
        known += (flags[i] == JNI_TRUE) ? 1 : 0;
    }
    env->SetBooleanArrayRegion(outKnown, 0, count, flags);
    return known;
}

jstring nativeCandidateForPack(JNIEnv* env, jobject /*thiz*/, jlong handle, jint packIndex,
                               jstring word) {
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
    const int written = engine->candidateForPack(static_cast<int>(packIndex), buffer,
                                                 static_cast<size_t>(length), spelling,
                                                 sizeof(spelling) - 1);
    if (written <= 0) {
        return nullptr;
    }
    spelling[written] = '\0';
    return env->NewStringUTF(spelling);
}

jint nativeDominantPack(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Engine* const engine = engineFrom(handle);
    return engine == nullptr ? -1 : static_cast<jint>(engine->dominantPack());
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

jstring nativeDominantLanguageTag(JNIEnv* env, jobject /*thiz*/, jlong handle) {
    Engine* const engine = engineFrom(handle);
    const char* const tag = engine == nullptr ? nullptr : engine->dominantLanguageTag();
    return tag == nullptr ? nullptr : env->NewStringUTF(tag);
}

/** Loads tier B's weights from a byte array; false in a `core` build. */
jboolean nativeLoadSwipeWeights(JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray weights) {
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
    const bool loaded =
        engine->loadSwipeWeights(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(length));
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

/** Runs one discarded decode through tier B; false when there is nothing to warm. */
jboolean nativeWarmSwipeModel(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return JNI_FALSE;
    }
    return engine->warmSwipeModel() ? JNI_TRUE : JNI_FALSE;
}

void nativeSetLearningSpeed(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jfloat speed) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return;
    }
    engine->setLearningSpeed(static_cast<float>(speed));
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

void nativeLoadUserBigrams(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray previous,
                           jobjectArray next, jintArray counts) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || previous == nullptr || next == nullptr || counts == nullptr) {
        return;
    }
    const jsize pairCount = env->GetArrayLength(previous);
    // An empty list clears the pairs the engine holds.
    if (pairCount == 0) {
        engine->loadUserBigrams(nullptr, nullptr, nullptr, nullptr, nullptr, 0);
        return;
    }
    if (pairCount < 0 || env->GetArrayLength(next) < pairCount ||
        env->GetArrayLength(counts) < pairCount || pairCount > kMaxUserWordsPerCall) {
        return;
    }

    StringColumn previousColumn(pairCount);
    StringColumn nextColumn(pairCount);
    Int32Column countValues(pairCount);
    if (!previousColumn.valid() || !nextColumn.valid() || !countValues.valid()) {
        return;
    }

    env->GetIntArrayRegion(counts, 0, pairCount, reinterpret_cast<jint*>(countValues.data()));
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return;
    }

    previousColumn.copyFrom(env, previous);
    nextColumn.copyFrom(env, next);

    engine->loadUserBigrams(previousColumn.data(), previousColumn.lengths(), nextColumn.data(),
                            nextColumn.lengths(), countValues.data(),
                            static_cast<int>(pairCount));
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
    Int32Column countValues(wordCount);
    Int32Column capsValues(wordCount);
    Int32Column assertedValues(wordCount);
    if (!column.valid() || !countValues.valid() || !capsValues.valid() ||
        !assertedValues.valid()) {
        return;
    }

    env->GetIntArrayRegion(counts, 0, wordCount, reinterpret_cast<jint*>(countValues.data()));
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return;
    }
    env->GetIntArrayRegion(deliberateCapitals, 0, wordCount,
                           reinterpret_cast<jint*>(capsValues.data()));
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return;
    }
    env->GetIntArrayRegion(asserted, 0, wordCount,
                           reinterpret_cast<jint*>(assertedValues.data()));
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
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
        jstring value = static_cast<jstring>(env->GetObjectArrayElement(words, i));
        char buffer[kStringBufferBytes];
        const jsize length = copyString(env, value, buffer, sizeof(buffer));
        if (value != nullptr) {
            // One local reference at a time.
            env->DeleteLocalRef(value);
        }
        if (length <= 0) {
            continue;
        }
        char* const copy = new (std::nothrow) char[length];
        if (copy == nullptr) {
            continue;
        }
        std::memcpy(copy, buffer, static_cast<size_t>(length));
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
                         jbooleanArray outProperNoun) {
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
    if (found <= 0) {
        return 0;
    }

    float scores[Engine::kMaxCandidates];
    jboolean properNoun[Engine::kMaxCandidates];
    int written = 0;
    char text[kStringBufferBytes];
    for (int i = 0; i < found; ++i) {
        uint32_t length = 0;
        const char* const source = engine->candidateText(candidates[i], &length);
        if (source == nullptr || length == 0 || length >= sizeof(text)) {
            continue;
        }
        std::memcpy(text, source, length);
        text[length] = '\0';
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
        // Already in [0, 1000].
        scores[written] = candidates[i].score;
        // The same name flag nativeAnswer reports.
        properNoun[written] = engine->candidateIsProperNoun(candidates[i]) ? JNI_TRUE : JNI_FALSE;
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

const JNINativeMethod kMethods[] = {
    {"nativeCreate", "()J", reinterpret_cast<void*>(nativeCreate)},
    {"nativeDestroy", "(J)V", reinterpret_cast<void*>(nativeDestroy)},
    {"nativeLoadLanguage", "(JLjava/lang/String;IJJF)I",
     reinterpret_cast<void*>(nativeLoadLanguage)},
    {"nativeInspectPack", "(IJJ[I)Ljava/lang/String;",
     reinterpret_cast<void*>(nativeInspectPack)},
    {"nativeSetActiveLanguages", "(J[Ljava/lang/String;[F)V",
     reinterpret_cast<void*>(nativeSetActiveLanguages)},
    {"nativeSetKeyGeometry", "(J[I[F[FFF)V", reinterpret_cast<void*>(nativeSetKeyGeometry)},
    {"nativeAnswer",
     "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[F[Z[I"
     "[Ljava/lang/String;[Z)I",
     reinterpret_cast<void*>(nativeAnswer)},
    {"nativeLearn", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZ)V",
     reinterpret_cast<void*>(nativeLearn)},
    {"nativeLoadUserWords", "(J[Ljava/lang/String;[I[I[I)V",
     reinterpret_cast<void*>(nativeLoadUserWords)},
    {"nativeSetBlockedWords", "(J[Ljava/lang/String;)V",
     reinterpret_cast<void*>(nativeSetBlockedWords)},
    {"nativeLoadUserBigrams", "(J[Ljava/lang/String;[Ljava/lang/String;[I)V",
     reinterpret_cast<void*>(nativeLoadUserBigrams)},
    {"nativeSetLearningSpeed", "(JF)V",
     reinterpret_cast<void*>(nativeSetLearningSpeed)},
    {"nativeSetCorrectionStrictness", "(JF)V",
     reinterpret_cast<void*>(nativeSetCorrectionStrictness)},
    {"nativeSetLanguageLock", "(JFZ)V",
     reinterpret_cast<void*>(nativeSetLanguageLock)},
    {"nativeSetPreferredLanguage", "(JLjava/lang/String;)V",
     reinterpret_cast<void*>(nativeSetPreferredLanguage)},
    {"nativeResetLanguageEvidence", "(J)V",
     reinterpret_cast<void*>(nativeResetLanguageEvidence)},
    {"nativeSetPhraseSuggestions", "(JZ)V",
     reinterpret_cast<void*>(nativeSetPhraseSuggestions)},
    {"nativeLoadSwipeWeights", "(J[B)Z", reinterpret_cast<void*>(nativeLoadSwipeWeights)},
    {"nativeSetSwipeModelEnabled", "(JZ)V",
     reinterpret_cast<void*>(nativeSetSwipeModelEnabled)},
    {"nativeLastDecodeUsedNeural", "(J)Z",
     reinterpret_cast<void*>(nativeLastDecodeUsedNeural)},
    {"nativeWarmSwipeModel", "(J)Z", reinterpret_cast<void*>(nativeWarmSwipeModel)},
    {"nativeKnownStems", "(J[Ljava/lang/String;[Z)I",
     reinterpret_cast<void*>(nativeKnownStems)},
    {"nativeExplainScore", "(JLjava/lang/String;Ljava/lang/String;[F)Z",
     reinterpret_cast<void*>(nativeExplainScore)},
    {"nativeLoadUserTrigrams",
     "(J[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;[I)V",
     reinterpret_cast<void*>(nativeLoadUserTrigrams)},
    {"nativeDecodeGesture",
     "(J[F[F[JILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;[F[Z)I",
     reinterpret_cast<void*>(nativeDecodeGesture)},
    {"nativeSetPersonalModelEnabled", "(JZ)V",
     reinterpret_cast<void*>(nativeSetPersonalModelEnabled)},
    {"nativeDominantLanguageTag", "(J)Ljava/lang/String;",
     reinterpret_cast<void*>(nativeDominantLanguageTag)},
    {"nativeCandidateForPack", "(JILjava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(nativeCandidateForPack)},
    {"nativeDominantPack", "(J)I", reinterpret_cast<void*>(nativeDominantPack)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass predictor = env->FindClass("com/borderkeys/predict/NativePredictor");
    if (predictor == nullptr) {
        return JNI_ERR;
    }
    // A signature that does not match its Kotlin declaration fails the load.
    const jint registered =
        env->RegisterNatives(predictor, kMethods, sizeof(kMethods) / sizeof(kMethods[0]));
    env->DeleteLocalRef(predictor);
    if (registered != JNI_OK) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
