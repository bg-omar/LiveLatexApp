#include <jni.h>
#include <cstring>
#include <string>

// Negative codes returned to Kotlin (see LatexEngineNative.kt)
static const jint ERR_NOT_BUILT = -1;
static const jint ERR_TEXLIVE_NOT_IMPLEMENTED = -2;
static const jint ERR_INVALID_ARGS = -3;

static std::string jstring_to_utf8(JNIEnv *env, jstring js) {
    if (!js) return {};
    const char *c = env->GetStringUTFChars(js, nullptr);
    if (!c) return {};
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omariskandarani_livelatexapp_latex_engine_LatexEngineNative_nativeCompileTexToPdf(
        JNIEnv *env,
        jclass /* clazz */,
        jstring jTexPath,
        jstring jWorkDir,
        jint engineKind) {
    (void)env;
    (void)jTexPath;
    (void)jWorkDir;
    // 0 = Tectonic, 1 = TeX Live (stub)
    if (engineKind == 1) {
        return ERR_TEXLIVE_NOT_IMPLEMENTED;
    }
    // Replace with Tectonic/Rust linkage when the full engine is bundled.
    return ERR_NOT_BUILT;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omariskandarani_livelatexapp_latex_engine_LatexEngineNative_nativePdfToSvg(
        JNIEnv *env,
        jclass /* clazz */,
        jstring jPdfPath,
        jstring jSvgPath) {
    (void)env;
    (void)jPdfPath;
    (void)jSvgPath;
    // Replace with mutool/dvisvgm when linked.
    return ERR_NOT_BUILT;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_omariskandarani_livelatexapp_latex_engine_LatexEngineNative_nativeGetBuildInfoImpl(
        JNIEnv *env,
        jclass /* clazz */) {
    const char *msg = "JNI stub — link Tectonic/TeX Live native libs for offline compile";
    return env->NewStringUTF(msg);
}
