// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors


#include <cstdint>
#include <jni.h>
#include <new>
#include <string>

#include "text_assist.hpp"

// The JNI surface of the assistant process. Registered through JNI_OnLoad like the keyboard's
// bridge, for the same reason: a signature that drifted from its Kotlin declaration fails when
// the library loads rather than when the user first asks for a summary.
//
// Unlike the keyboard's bridge, this one is allowed to allocate. It runs in :assist, on a worker
// thread, once per user action -- there is no frame budget here and the strings involved are
// kilobytes. Pretending otherwise would mean copying the user's selected text through a stack
// buffer for no reason.

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
    const jint status = assist->load(utf, contextTokens, threads);
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
 * Runs one instruction and returns the answer, or null with a status in `outStatus[0]`.
 *
 * `outTruncated[0]`, meaningful only when a non-null result comes back, is set to whether the
 * answer was cut short of where the model itself would have stopped -- see `TextAssist::run`'s
 * `outTruncated` doc. A second caller-supplied array of its own kind rather than a second slot
 * in `outStatus`: the two say different kinds of thing, and a status code that happens to share
 * an array with an unrelated flag is a status code future output stops meaning what its name
 * says. Both travel back through arrays rather than a second call, so neither can be separated
 * from the request that produced it. See `TextAssist::run`'s own doc for how `outputRatio`,
 * `minOutputTokens`, `maxOutputTokensCeiling` and `useRemainingContext` together decide the
 * token budget, and for `reuseSharedPrefix`.
 */
jstring nativeRun(JNIEnv* env, jobject /*thiz*/, jlong handle, jstring instruction, jstring text,
                  jfloat outputRatio, jint minOutputTokens, jint maxOutputTokensCeiling,
                  jboolean useRemainingContext, jboolean reuseSharedPrefix,
                  jboolean cleanFormatting, jintArray outStatus, jbooleanArray outTruncated) {
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

    const char* const instructionUtf = env->GetStringUTFChars(instruction, nullptr);
    if (instructionUtf == nullptr) {
        report();
        return nullptr;
    }
    const char* const textUtf = env->GetStringUTFChars(text, nullptr);
    if (textUtf == nullptr) {
        env->ReleaseStringUTFChars(instruction, instructionUtf);
        report();
        return nullptr;
    }

    std::string answer;
    status = assist->run(instructionUtf, textUtf, static_cast<float>(outputRatio), minOutputTokens,
                         maxOutputTokensCeiling, useRemainingContext == JNI_TRUE,
                         reuseSharedPrefix == JNI_TRUE, cleanFormatting == JNI_TRUE, &answer,
                         &truncated);

    env->ReleaseStringUTFChars(text, textUtf);
    env->ReleaseStringUTFChars(instruction, instructionUtf);
    report();

    if (status != TextAssist::kOk) {
        return nullptr;
    }
    return env->NewStringUTF(answer.c_str());
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
    {"nativeRun", "(JLjava/lang/String;Ljava/lang/String;FIIZZZ[I[Z)Ljava/lang/String;",
     reinterpret_cast<void*>(nativeRun)},
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
