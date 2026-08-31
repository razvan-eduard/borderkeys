// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors


#include <cstddef>
#include <cstdint>
#include <cstring>
#include <jni.h>
#include <new>

#include "engine.hpp"

// The only file in this library that knows JNI exists. Everything below it is plain C++ that
// the host test binary compiles and runs without an Android in sight.
//
// Rules this file exists to enforce:
//
//   * Registration goes through JNI_OnLoad and RegisterNatives, not through exported
//     Java_com_borderkeys_... symbols. Two reasons. The symbols stay hidden, so the library
//     exports exactly one thing and the linker can garbage-collect the rest. And the binding is
//     checked when the library loads: a signature that drifted from the Kotlin declaration is a
//     failure at System.loadLibrary, in the open, rather than an UnsatisfiedLinkError on the
//     first keystroke with the keyboard already on screen.
//
//   * C++ exceptions are compiled out (-fno-exceptions). Errors cross this boundary as return
//     values, never as throws, and nothing here throws a Java exception on the suggestion path
//     either: a checked exception per keystroke would be an allocation and a stack walk inside
//     the 8 ms budget.
//
//   * Nothing here allocates per call except the result strings, which cannot be avoided --
//     Kotlin needs java.lang.String objects and only the VM can make them. The arrays holding
//     them are supplied by the caller and reused, the input strings are read into stack
//     buffers, and the whole path runs on the prediction thread, never the UI thread.

namespace {

using borderkeys::Candidate;
using borderkeys::Engine;

// The longest word this engine will look at, in UTF-16 units as GetStringUTFRegion counts
// them. Modified UTF-8 is at most three bytes per unit, hence the buffer size.
constexpr jsize kMaxStringUnits = 64;
constexpr jsize kStringBufferBytes = kMaxStringUnits * 3 + 1;

constexpr int kMaxUserWordsPerCall = 20000;

// Matches the capture buffer in KeyboardCanvasView. A gesture longer than this has already been
// decimated on the Kotlin side, so the cap here is a bound on a hostile caller rather than on a
// real swipe.
constexpr jsize kMaxGesturePoints = 512;

// No jclass, jmethodID or jfieldID is cached here, because none is needed: the bridge fills
// arrays the caller allocated and calls nothing back into Kotlin. If that ever changes, the id
// is resolved in JNI_OnLoad and held in a global reference -- never looked up per call, which
// on the suggestion path would be a hash lookup in the VM's class table on every keystroke.

Engine* engineFrom(jlong handle) {
    return reinterpret_cast<Engine*>(static_cast<intptr_t>(handle));
}

// Copies a Java string into a stack buffer. Returns the byte length, or -1 when the string is
// null or longer than the buffer.
//
// GetStringUTFRegion rather than GetStringUTFChars: the latter may allocate and hand back a
// pointer that has to be released on every path out of the function, including the error ones,
// which is exactly the shape of leak that only shows up under a failing input. This copies into
// memory we already own and cannot forget to free.
jsize copyString(JNIEnv* env, jstring value, char* buffer, jsize bufferBytes) {
    if (value == nullptr) {
        return -1;
    }
    const jsize units = env->GetStringLength(value);
    // GetStringUTFRegion writes modified UTF-8 and reports nothing about how much it wrote, and
    // it does not terminate. GetStringUTFLength is the only source for the byte count; deriving
    // it with strlen afterwards would read past whatever was written.
    const jsize bytes = env->GetStringUTFLength(value);
    if (units <= 0 || units > kMaxStringUnits || bytes <= 0 || bytes + 1 > bufferBytes) {
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
 * Describes a `.bkd` without loading it into an engine.
 *
 * Fills `out` with { status, formatVersion, wordCount } and returns the language tag, or null
 * when the pack was refused -- in which case `out[0]` carries the BkdStatus that says why.
 *
 * This exists so that Settings can name and record a pack the user has just chosen without a
 * second implementation of the header layout in Kotlin. It allocates one String per import,
 * which is not a hot path: the alternative is two parsers that agree until they do not.
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
    const jsize count = env->GetArrayLength(tags);
    if (count <= 0 || count > Engine::kMaxPacks) {
        // More tags than the engine has slots is a caller bug, not something to half-apply.
        engine->setActiveLanguages(nullptr, nullptr, 0);
        return;
    }

    char storage[Engine::kMaxPacks][kStringBufferBytes];
    const char* pointers[Engine::kMaxPacks];
    float weightValues[Engine::kMaxPacks];

    for (jsize i = 0; i < count; ++i) {
        jstring tag = static_cast<jstring>(env->GetObjectArrayElement(tags, i));
        const jsize length = copyString(env, tag, storage[i], kStringBufferBytes);
        // Local references are finite and this loop is bounded by kMaxPacks, but deleting them
        // as we go keeps the pattern the same as the suggestion path, where it matters.
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

jint nativeSuggest(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring composing, jstring prev1,
                   jstring prev2, jobjectArray outWords, jfloatArray outScores) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || outWords == nullptr || outScores == nullptr) {
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
    jsize slots = (wordSlots < scoreSlots) ? wordSlots : scoreSlots;
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
    if (found <= 0) {
        return 0;
    }

    float scores[Engine::kMaxCandidates];
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

        // The one unavoidable allocation on this path: only the VM can produce a
        // java.lang.String. The array it goes into was allocated once by the caller and is
        // reused for every request, and none of this runs on the UI thread.
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
        ++written;
    }

    if (written > 0) {
        env->SetFloatArrayRegion(outScores, 0, written, scores);
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            return 0;
        }
    }
    return written;
}

void nativeLearn(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring word, jstring prev1,
                 jstring prev2) {
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
                  static_cast<size_t>(prev2Length));
}

void nativeLoadUserWords(JNIEnv* env, jobject /*thiz*/, jlong handle, jobjectArray words,
                         jintArray counts) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr || words == nullptr || counts == nullptr) {
        return;
    }
    const jsize wordCount = env->GetArrayLength(words);
    const jsize countLength = env->GetArrayLength(counts);
    if (wordCount <= 0 || countLength < wordCount || wordCount > kMaxUserWordsPerCall) {
        return;
    }

    // This runs once, at service start, off the UI thread, over the whole personal dictionary,
    // so it is the one place in this file that is allowed to allocate proportionally to its
    // input rather than into a fixed buffer.
    char** const storage = new (std::nothrow) char*[wordCount];
    size_t* const lengths = new (std::nothrow) size_t[wordCount];
    int32_t* const countValues = new (std::nothrow) int32_t[wordCount];
    if (storage == nullptr || lengths == nullptr || countValues == nullptr) {
        delete[] storage;
        delete[] lengths;
        delete[] countValues;
        return;
    }
    for (jsize i = 0; i < wordCount; ++i) {
        storage[i] = nullptr;
        lengths[i] = 0;
    }

    env->GetIntArrayRegion(counts, 0, wordCount, reinterpret_cast<jint*>(countValues));
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        delete[] storage;
        delete[] lengths;
        delete[] countValues;
        return;
    }

    jsize kept = 0;
    for (jsize i = 0; i < wordCount; ++i) {
        jstring value = static_cast<jstring>(env->GetObjectArrayElement(words, i));
        char buffer[kStringBufferBytes];
        const jsize length = copyString(env, value, buffer, sizeof(buffer));
        if (value != nullptr) {
            // Without this the loop accumulates one local reference per word and overflows the
            // local reference table long before a real personal dictionary is exhausted.
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
        countValues[kept] = countValues[i];
        ++kept;
    }

    engine->loadUserWords(storage, lengths, countValues, static_cast<int>(kept));

    for (jsize i = 0; i < kept; ++i) {
        delete[] storage[i];
    }
    delete[] storage;
    delete[] lengths;
    delete[] countValues;
}

jint nativeDecodeGesture(JNIEnv* env, jobject /*thiz*/, jlong handle, jfloatArray xs,
                         jfloatArray ys, jlongArray ts, jint count, jstring prev1, jstring prev2,
                         jobjectArray outWords, jfloatArray outScores) {
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

    // Copied into stack buffers rather than pinned with GetPrimitiveArrayCritical. Critical
    // sections forbid every other JNI call while they are held, and the decoder below is not a
    // few instructions -- it is a trie walk with a thirty-millisecond budget. Eight kilobytes of
    // stack is the cheaper trade.
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
        scores[written] = candidates[i].score;
        ++written;
    }
    if (written > 0) {
        env->SetFloatArrayRegion(outScores, 0, written, scores);
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            return 0;
        }
    }
    return written;
}

jint nativeSnapshotUserModel(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring path) {
    Engine* const engine = engineFrom(handle);
    if (engine == nullptr) {
        return borderkeys::kBkdErrArgument;
    }
    // A filesystem path can be longer than a word, so it gets its own bound rather than the
    // word-sized one.
    constexpr jsize kMaxPathUnits = 512;
    char buffer[kMaxPathUnits * 3 + 1];
    if (path == nullptr) {
        return borderkeys::kBkdErrArgument;
    }
    const jsize units = env->GetStringLength(path);
    const jsize bytes = env->GetStringUTFLength(path);
    if (units <= 0 || units > kMaxPathUnits || bytes <= 0 ||
        bytes + 1 > static_cast<jsize>(sizeof(buffer))) {
        return borderkeys::kBkdErrArgument;
    }
    env->GetStringUTFRegion(path, 0, units, buffer);
    if (env->ExceptionCheck() == JNI_TRUE) {
        env->ExceptionClear();
        return borderkeys::kBkdErrArgument;
    }
    buffer[bytes] = '\0';
    return engine->snapshotUserModel(buffer) ? borderkeys::kBkdOk : -1;
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
    {"nativeSuggest",
     "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[F)I",
     reinterpret_cast<void*>(nativeSuggest)},
    {"nativeLearn", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
     reinterpret_cast<void*>(nativeLearn)},
    {"nativeLoadUserWords", "(J[Ljava/lang/String;[I)V",
     reinterpret_cast<void*>(nativeLoadUserWords)},
    {"nativeDecodeGesture",
     "(J[F[F[JILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;[F)I",
     reinterpret_cast<void*>(nativeDecodeGesture)},
    {"nativeSnapshotUserModel", "(JLjava/lang/String;)I",
     reinterpret_cast<void*>(nativeSnapshotUserModel)},
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
    // Registering here is what turns a signature mismatch into a load-time failure. If any
    // entry in kMethods does not match its Kotlin declaration exactly, this returns non-zero
    // and System.loadLibrary throws, before a keyboard has been shown.
    const jint registered =
        env->RegisterNatives(predictor, kMethods, sizeof(kMethods) / sizeof(kMethods[0]));
    env->DeleteLocalRef(predictor);
    if (registered != JNI_OK) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
