#include <jni.h>
#include <llama.h>
#include <mtmd.h>
#include <ggml-backend.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <new>
#include <android/bitmap.h>
#include <android/log.h>
#include <mtmd-helper.h>
#include <chrono>
#include <string>
#include <vector>

namespace {
constexpr int OK = 0, MODEL_FAILED = 1, PROJECTOR_FAILED = 2, CONTEXT_FAILED = 3,
              OUT_OF_MEMORY = 4, CANCELLED = 5, NATIVE_FAILED = 6, ALREADY_LOADED = 7;
std::mutex lifecycle;
std::atomic<bool> cancelled{false};
std::atomic<bool> loaded{false};
bool backend_initialized = false;
std::unique_ptr<llama_model, decltype(&llama_model_free)> model(nullptr, llama_model_free);
std::unique_ptr<llama_context, decltype(&llama_free)> context(nullptr, llama_free);
std::unique_ptr<mtmd_context, decltype(&mtmd_free)> projector(nullptr, mtmd_free);
using BitmapPtr = std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)>;
std::vector<BitmapPtr> images;
bool abort_decode(void *) { return cancelled.load(); }

bool continue_loading(float, void *) { return !cancelled.load(); }
void cleanup() {
    loaded.store(false);
    images.clear();
    projector.reset();
    context.reset();
    model.reset();
    if (backend_initialized) { llama_backend_free(); backend_initialized = false; }
}
struct UtfChars {
    JNIEnv * env;
    jstring value;
    const char * chars;
    UtfChars(JNIEnv * env, jstring value) : env(env), value(value), chars(env->GetStringUTFChars(value, nullptr)) {}
    ~UtfChars() { if (chars) env->ReleaseStringUTFChars(value, chars); }
};
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rchia_ecocapture_phase0_vlm_native_NativeQwenBindings_nativeLoadModel(
    JNIEnv * env, jobject, jstring language_path, jstring projector_path) {
    std::lock_guard<std::mutex> lock(lifecycle);
    if (loaded.load()) return ALREADY_LOADED;
    cancelled.store(false);
    try {
        UtfChars language(env, language_path), vision(env, projector_path);
        if (!language.chars || !vision.chars) return OUT_OF_MEMORY;
        ggml_backend_load_all();
        llama_backend_init();
        backend_initialized = true;
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
        model_params.progress_callback = continue_loading;
        model.reset(llama_model_load_from_file(language.chars, model_params));
        if (!model || cancelled.load()) {
            const int result = cancelled.load() ? CANCELLED : MODEL_FAILED;
            cleanup(); return result;
        }
        auto context_params = llama_context_default_params();
        context_params.n_ctx = 8192;
        context_params.n_batch = 512;
        context_params.n_ubatch = 512;
        context_params.n_threads = 4;
        context_params.n_threads_batch = 4;
        context_params.offload_kqv = false;
        context_params.op_offload = false;
        context.reset(llama_init_from_model(model.get(), context_params));
        if (!context || cancelled.load()) {
            const int result = cancelled.load() ? CANCELLED : CONTEXT_FAILED;
            cleanup(); return result;
        }
        llama_set_abort_callback(context.get(), abort_decode, nullptr);
        auto vision_params = mtmd_context_params_default();
        vision_params.use_gpu = false;
        vision_params.n_threads = 4;
        vision_params.warmup = false;
        vision_params.print_timings = false;
        vision_params.progress_callback = continue_loading;
        projector.reset(mtmd_init_from_file(vision.chars, model.get(), vision_params));
        if (!projector || cancelled.load()) {
            const int result = cancelled.load() ? CANCELLED : PROJECTOR_FAILED;
            cleanup(); return result;
        }
        loaded.store(true);
        return OK;
    } catch (const std::bad_alloc &) { cleanup(); return OUT_OF_MEMORY; }
      catch (...) { cleanup(); return NATIVE_FAILED; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rchia_ecocapture_phase0_vlm_native_NativeQwenBindings_nativeUnload(
    JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(lifecycle);
    try { cleanup(); return OK; } catch (...) { return NATIVE_FAILED; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rchia_ecocapture_phase0_vlm_native_NativeQwenBindings_nativeCancel(
    JNIEnv *, jobject) { cancelled.store(true); }

extern "C" JNIEXPORT jstring JNICALL
Java_com_rchia_ecocapture_phase0_vlm_native_NativeQwenBindings_nativeGetRuntimeInfo(
    JNIEnv * env, jobject) {
    // No paths, model output or participant data are exposed here.
    return env->NewStringUTF("llama.cpp/libmtmd|" ECOCAPTURE_RUNTIME_COMMIT "|arm64-v8a|CPU|8192|4");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rchia_ecocapture_phase0_vlm_native_NativeQwenBindings_nativeBeginGeneration(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(lifecycle);
    if (!loaded.load()) return 8;
    cancelled.store(false);
    images.clear();
    llama_memory_clear(llama_get_memory(context.get()), true);
    return OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rchia_ecocapture_phase0_vlm_native_NativeQwenBindings_nativeAddFrame(JNIEnv * env, jobject, jobject bitmap) {
    std::lock_guard<std::mutex> lock(lifecycle);
    if (!loaded.load()) return 8;
    if (cancelled.load()) return CANCELLED;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0 ||
        info.width > 2048 || info.height > 2048 || images.size() >= 16) return 9;
    void * pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return 9;
    struct Unlock { JNIEnv * env; jobject bitmap; ~Unlock() { AndroidBitmap_unlockPixels(env, bitmap); } } unlock{env, bitmap};
    try {
        std::vector<unsigned char> rgb(size_t(info.width) * info.height * 3);
        for (uint32_t y = 0; y < info.height; ++y) {
            auto row = static_cast<const unsigned char *>(pixels) + size_t(y) * info.stride;
            for (uint32_t x = 0; x < info.width; ++x) {
                const size_t offset = (size_t(y) * info.width + x) * 3;
                rgb[offset] = row[x * 4]; rgb[offset + 1] = row[x * 4 + 1]; rgb[offset + 2] = row[x * 4 + 2];
            }
        }
        BitmapPtr image(mtmd_bitmap_init(info.width, info.height, rgb.data()), mtmd_bitmap_free);
        if (!image) return OUT_OF_MEMORY;
        // Separate chronological images: no temporal merge or fabricated video timestamps.
        mtmd_bitmap_set_mergeable(image.get(), false);
        images.push_back(std::move(image));
        return OK;
    } catch (const std::bad_alloc &) { return OUT_OF_MEMORY; }
      catch (...) { return NATIVE_FAILED; }
}

namespace {
using Clock = std::chrono::steady_clock;
long long elapsed_ms(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}
std::string read_bytes(JNIEnv * env, jbyteArray input) {
    const auto count = env->GetArrayLength(input);
    std::string value(count, '\0');
    env->GetByteArrayRegion(input, 0, count, reinterpret_cast<jbyte *>(value.data()));
    return value;
}
// UTF-8 bytes preserve arbitrary model text; JNI modified UTF-8 strings do not.
jobjectArray result(JNIEnv * env, int code, const std::string & output = "", const std::string & metrics = "{}",
                    const std::string & prompt = "") {
    auto cls = env->FindClass("[B");
    auto array = env->NewObjectArray(4, cls, nullptr);
    env->DeleteLocalRef(cls);
    if (!array) return nullptr;
    const std::string values[] = {std::to_string(code), output, metrics, prompt};
    for (int i = 0; i < 4; ++i) {
        auto bytes = env->NewByteArray(values[i].size());
        if (!bytes) return nullptr;
        env->SetByteArrayRegion(bytes, 0, values[i].size(), reinterpret_cast<const jbyte *>(values[i].data()));
        env->SetObjectArrayElement(array, i, bytes);
        env->DeleteLocalRef(bytes);
    }
    return array;
}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rchia_ecocapture_phase0_vlm_native_NativeQwenBindings_nativeGenerate(
    JNIEnv * env, jobject, jbyteArray system_bytes, jbyteArray user_bytes, jint max_tokens) {
    std::lock_guard<std::mutex> lock(lifecycle);
    if (!loaded.load()) return result(env, 8);
    if (cancelled.load()) return result(env, CANCELLED);
    if (images.empty() || max_tokens < 1 || max_tokens > 1024) return result(env, 9);
    try {
        auto system = read_bytes(env, system_bytes);
        auto user = read_bytes(env, user_bytes);
        if (env->ExceptionCheck()) return nullptr;
        if (system.find('\0') != std::string::npos || user.find('\0') != std::string::npos) return result(env, 9);
        std::string content;
        for (size_t i = 0; i < images.size(); ++i) content += std::string(mtmd_default_marker()) + "\n";
        content += user;
        const char * model_template = llama_model_chat_template(model.get(), nullptr);
        if (!model_template || std::string(model_template).find("<|im_start|>") == std::string::npos) return result(env, 9);
        const llama_chat_message messages[] = {{"system", system.c_str()}, {"user", content.c_str()}};
        int length = llama_chat_apply_template("chatml", messages, 2, true, nullptr, 0);
        if (length <= 0) return result(env, 11);
        std::string prompt(length, '\0');
        if (llama_chat_apply_template("chatml", messages, 2, true, prompt.data(), length) != length) return result(env, 11);
        std::vector<const mtmd_bitmap *> pointers;
        for (const auto & image : images) pointers.push_back(image.get());
        std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunks(mtmd_input_chunks_init(), mtmd_input_chunks_free);
        if (!chunks) return result(env, OUT_OF_MEMORY);
        const mtmd_input_text input{prompt.data(), prompt.size(), true, true};
        if (mtmd_tokenize(projector.get(), chunks.get(), &input, pointers.data(), pointers.size()) != 0) return result(env, 11);
        images.clear(); // Tokenization owns preprocessed image data now.
        const size_t prompt_tokens = mtmd_helper_get_n_tokens(chunks.get());
        __android_log_print(ANDROID_LOG_INFO, "EcoQwen", "Tokenized %zu prompt tokens", prompt_tokens);
        if (prompt_tokens + max_tokens > llama_n_ctx(context.get())) return result(env, 10);
        llama_pos position = 0;
        long long vision_ms = 0, prefill_ms = 0;
        const auto chunk_count = mtmd_input_chunks_size(chunks.get());
        for (size_t i = 0; i < chunk_count; ++i) {
            if (cancelled.load()) return result(env, CANCELLED);
            const auto chunk = mtmd_input_chunks_get(chunks.get(), i);
            int code;
            auto started = Clock::now();
            if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_IMAGE) {
                __android_log_print(ANDROID_LOG_INFO, "EcoQwen", "Encoding image chunk %zu", i);
                code = mtmd_encode_chunk(projector.get(), chunk);
                vision_ms += elapsed_ms(started);
                if (cancelled.load()) return result(env, CANCELLED);
                if (code != 0) return result(env, 12);
                started = Clock::now();
                code = mtmd_helper_decode_image_chunk(projector.get(), context.get(), chunk,
                    mtmd_get_output_embd(projector.get()), position, 0, 512, &position, nullptr, nullptr);
            } else {
                code = mtmd_helper_eval_chunk_single(projector.get(), context.get(), chunk, position,
                    0, 512, i == chunk_count - 1, &position);
            }
            prefill_ms += elapsed_ms(started);
            if (cancelled.load()) return result(env, CANCELLED);
            if (code != 0) return result(env, 13);
        }
        chunks.reset();
        __android_log_print(ANDROID_LOG_INFO, "EcoQwen", "Prefill complete; generating up to %d tokens", max_tokens);
        const auto vocab = llama_model_get_vocab(model.get());
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(context.get()), llama_batch_ext_free);
        if (!sampler || !batch) return result(env, OUT_OF_MEMORY);
        const auto generation_start = Clock::now();
        std::string output;
        int generated = 0;
        const char * stop = "token_limit";
        for (int i = 0; i < max_tokens; ++i) {
            if (cancelled.load()) return result(env, CANCELLED);
            const auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) { stop = "eog"; break; }
            char small[256];
            int count = llama_token_to_piece(vocab, token, small, sizeof(small), 0, true);
            if (count < 0) {
                std::string piece(-count, '\0');
                count = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
                if (count < 0) return result(env, 13);
                output.append(piece.data(), count);
            } else output.append(small, count);
            ++generated;
            if (generated % 32 == 0) __android_log_print(ANDROID_LOG_INFO, "EcoQwen", "Generated %d tokens", generated);
            if (i == max_tokens - 1) break;
            llama_batch_ext_clear(batch.get());
            const auto index = llama_batch_ext_add_token(batch.get(), 0, token);
            if (index < 0 || !llama_batch_ext_set_pos(batch.get(), index, &position) ||
                !llama_batch_ext_set_output_logits(batch.get(), index, true)) return result(env, 13);
            ++position;
            const auto code = llama_process(context.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get());
            if (cancelled.load()) return result(env, CANCELLED);
            if (code != 0) return result(env, 13);
        }
        if (output.empty()) return result(env, 14);
        const std::string metrics = "{\"nativeBuildType\":\"" ECOCAPTURE_NATIVE_BUILD_TYPE "\",\"visionEncodingMs\":" + std::to_string(vision_ms) +
            ",\"prefillMs\":" + std::to_string(prefill_ms) + ",\"generationMs\":" + std::to_string(elapsed_ms(generation_start)) +
            ",\"promptTokens\":" + std::to_string(prompt_tokens) + ",\"generatedTokens\":" + std::to_string(generated) +
            ",\"stopReason\":\"" + stop + "\"}";
        return result(env, OK, output, metrics, prompt);
    } catch (const std::bad_alloc &) { return result(env, OUT_OF_MEMORY); }
      catch (...) { return result(env, NATIVE_FAILED); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rchia_ecocapture_phase0_vlm_native_NativeQwenBindings_nativeEndGeneration(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(lifecycle);
    images.clear();
    if (context) llama_memory_clear(llama_get_memory(context.get()), true);
}
