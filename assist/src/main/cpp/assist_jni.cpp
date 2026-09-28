// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors


#include <cstdint>
#include <jni.h>
#include <new>
#include <string>

#include "text_assist.hpp"

// The JNI surface of the assistant process, registered through JNI_OnLoad: a signature that
// drifted from its Kotlin declaration fails when the library loads. It may allocate.

namespace {

using borderkeys::TextAssist;

constexpr jsize kMaxPathUnits = 1024;

TextAssist* assistFrom(jlong handle) {
    return reinterpret_cast<TextAssist*>(static_cast<intptr_t>(handle));
}

jlong nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    TextAssist* const assist = new (std::nothrow) TextAssist();
    return static_cast<jlong>(reinterpret_cast<intptr_t>(assist));
}

void nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    delete assistFrom(handle);
}

jint nativeLoad(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring path, jint contextTokens,
                jint threads) {
    TextAssist* const assist = assistFrom(handle);
    if (assist == nullptr || path == nullptr) {
        return TextAssist::kErrArgument;
    }
    if (env->GetStringLength(path) > kMaxPathUnits) {
        return TextAssist::kErrArgument;
    }
    const char* const utf = env->GetStringUTFChars(path, nullptr);
    if (utf == nullptr) {
        return TextAssist::kErrArgument;
    }
    // No C++ exception may cross back into the JVM's JNI call frame.
    const jint status = [&]() -> jint {
        try {
            return assist->load(utf, contextTokens, threads);
        } catch (const std::exception&) {
            return TextAssist::kErrException;
        } catch (...) {
            return TextAssist::kErrException;
        }
    }();
    env->ReleaseStringUTFChars(path, utf);
    return status;
}

void nativeSetSamplingParams(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jfloat temperature,
                             jfloat topP) {
    TextAssist* const assist = assistFrom(handle);
    if (assist == nullptr) {
        return;
    }
    assist->setSamplingParams(static_cast<float>(temperature), static_cast<float>(topP));
}

void nativeUnload(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    TextAssist* const assist = assistFrom(handle);
    if (assist != nullptr) {
        assist->unload();
    }
}

jboolean nativeIsLoaded(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    TextAssist* const assist = assistFrom(handle);
    return (assist != nullptr && assist->isLoaded()) ? JNI_TRUE : JNI_FALSE;
}

jint nativeContextTokens(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    TextAssist* const assist = assistFrom(handle);
    return assist != nullptr ? assist->contextTokens() : 0;
}

jfloat nativeCharsPerToken(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    TextAssist* const assist = assistFrom(handle);
    return assist != nullptr ? assist->charsPerToken() : 0.0f;
}

/**
 * Copies a Java byte array of UTF-8 into a NUL-terminated std::string. False when it is absent or
 * the copy raised.
 */
bool copyBytes(JNIEnv* env, jbyteArray array, std::string* out) {
    if (array == nullptr) {
        return false;
    }
    const jsize length = env->GetArrayLength(array);
    out->assign(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(&(*out)[0]));
        if (env->ExceptionCheck() == JNI_TRUE) {
            env->ExceptionClear();
            return false;
        }
    }
    return true;
}

/**
 * Runs one instruction and returns the answer as UTF-8 bytes, or null with a status in
 * `outStatus[0]`. `outTruncated[0]`, meaningful only with a non-null result, is set to whether the
 * answer was cut short; `TextAssist::run` describes the other parameters.
 */
jbyteArray nativeRun(JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray instruction,
                     jbyteArray text, jfloat outputRatio, jint minOutputTokens,
                     jint maxOutputTokensCeiling, jboolean useRemainingContext,
                     jboolean reuseSharedPrefix, jboolean cleanFormatting, jintArray outStatus,
                     jbooleanArray outTruncated) {
    TextAssist* const assist = assistFrom(handle);
    jint status = TextAssist::kErrArgument;
    bool truncated = false;
    auto report = [&]() {
        if (outStatus != nullptr && env->GetArrayLength(outStatus) > 0) {
            env->SetIntArrayRegion(outStatus, 0, 1, &status);
        }
        if (outTruncated != nullptr && env->GetArrayLength(outTruncated) > 0) {
            const jboolean truncatedValue = truncated ? JNI_TRUE : JNI_FALSE;
            env->SetBooleanArrayRegion(outTruncated, 0, 1, &truncatedValue);
        }
    };
    if (assist == nullptr || instruction == nullptr || text == nullptr) {
        report();
        return nullptr;
    }

    std::string instructionUtf8;
    std::string textUtf8;
    if (!copyBytes(env, instruction, &instructionUtf8) || !copyBytes(env, text, &textUtf8)) {
        report();
        return nullptr;
    }

    std::string answer;
    // Caught here, as in nativeLoad.
    status = [&]() -> jint {
        try {
            return assist->run(instructionUtf8.c_str(), textUtf8.c_str(),
                                static_cast<float>(outputRatio), minOutputTokens,
                                maxOutputTokensCeiling, useRemainingContext == JNI_TRUE,
                                reuseSharedPrefix == JNI_TRUE, cleanFormatting == JNI_TRUE,
                                &answer, &truncated);
        } catch (const std::exception&) {
            return TextAssist::kErrException;
        } catch (...) {
            return TextAssist::kErrException;
        }
    }();

    report();

    if (status != TextAssist::kOk) {
        return nullptr;
    }
    // UTF-8 bytes back as well, not NewStringUTF's modified UTF-8.
    const jsize size = static_cast<jsize>(answer.size());
    jbyteArray out = env->NewByteArray(size);
    if (out == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    if (size > 0) {
        env->SetByteArrayRegion(out, 0, size, reinterpret_cast<const jbyte*>(answer.data()));
    }
    return out;
}

void nativeCancel(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    TextAssist* const assist = assistFrom(handle);
    if (assist != nullptr) {
        assist->requestCancel();
    }
}

const JNINativeMethod kMethods[] = {
    {"nativeCreate", "()J", reinterpret_cast<void*>(nativeCreate)},
    {"nativeDestroy", "(J)V", reinterpret_cast<void*>(nativeDestroy)},
    {"nativeLoad", "(JLjava/lang/String;II)I", reinterpret_cast<void*>(nativeLoad)},
    {"nativeSetSamplingParams", "(JFF)V", reinterpret_cast<void*>(nativeSetSamplingParams)},
    {"nativeUnload", "(J)V", reinterpret_cast<void*>(nativeUnload)},
    {"nativeIsLoaded", "(J)Z", reinterpret_cast<void*>(nativeIsLoaded)},
    {"nativeContextTokens", "(J)I", reinterpret_cast<void*>(nativeContextTokens)},
    {"nativeCharsPerToken", "(J)F", reinterpret_cast<void*>(nativeCharsPerToken)},
    {"nativeRun", "(J[B[BFIIZZZ[I[Z)[B", reinterpret_cast<void*>(nativeRun)},
    {"nativeCancel", "(J)V", reinterpret_cast<void*>(nativeCancel)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass bridge = env->FindClass("com/borderkeys/assist/AssistNative");
    if (bridge == nullptr) {
        return JNI_ERR;
    }
    const jint registered =
        env->RegisterNatives(bridge, kMethods, sizeof(kMethods) / sizeof(kMethods[0]));
    env->DeleteLocalRef(bridge);
    return (registered == JNI_OK) ? JNI_VERSION_1_6 : JNI_ERR;
}
