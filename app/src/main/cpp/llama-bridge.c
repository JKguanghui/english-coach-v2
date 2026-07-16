#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include "llama.h"

typedef struct {
    struct llama_model *model;
    struct llama_context *ctx;
} LlamaState;

JNIEXPORT jlong JNICALL
Java_com_example_englishcoach_LLMEngine_nativeCreate(JNIEnv *env, jobject thiz, jstring modelPath, jint contextSize) {
    const char *path = (*env)->GetStringUTFChars(env, modelPath, NULL);

    struct llama_model_params model_params = llama_model_default_params();
    struct llama_model *model = llama_load_model_from_file(path, model_params);

    (*env)->ReleaseStringUTFChars(env, modelPath, path);

    if (model == NULL) {
        return 0;
    }

    struct llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = contextSize;
    ctx_params.n_batch = 512;

    struct llama_context *ctx = llama_new_context_with_model(model, ctx_params);

    if (ctx == NULL) {
        llama_free_model(model);
        return 0;
    }

    LlamaState *state = malloc(sizeof(LlamaState));
    state->model = model;
    state->ctx = ctx;

    return (jlong)(intptr_t)state;
}

JNIEXPORT jstring JNICALL
Java_com_example_englishcoach_LLMEngine_nativeGenerate(JNIEnv *env, jobject thiz, jlong modelPtr, jstring prompt, jint maxTokens) {
    LlamaState *state = (LlamaState *)(intptr_t)modelPtr;

    if (state == NULL || state->ctx == NULL) {
        return (*env)->NewStringUTF(env, "Error: model not loaded");
    }

    const char *promptStr = (*env)->GetStringUTFChars(env, prompt, NULL);

    llama_token tokens[4096];
    int n_tokens = llama_tokenize(state->model, promptStr, strlen(promptStr), tokens, 4096, true, true);

    (*env)->ReleaseStringUTFChars(env, prompt, promptStr);

    if (n_tokens < 0) {
        return (*env)->NewStringUTF(env, "Error: tokenization failed");
    }

    llama_kv_cache_clear(state->ctx);

    if (llama_decode(state->ctx, llama_batch_get_one(tokens, n_tokens, 0, 0))) {
        return (*env)->NewStringUTF(env, "Error: prompt evaluation failed");
    }

    char response[4096] = {0};
    int resp_len = 0;
    llama_token eos = llama_token_eos(state->model);

    for (int i = 0; i < maxTokens && resp_len < 4095; i++) {
        float *logits = llama_get_logits(state->ctx);
        if (!logits) break;

        int n_vocab = llama_n_vocab(state->model);
        llama_token new_token = 0;
        float max_logit = logits[0];
        for (int v = 1; v < n_vocab; v++) {
            if (logits[v] > max_logit) {
                max_logit = logits[v];
                new_token = v;
            }
        }

        if (new_token == eos) break;

        char buf[256];
        int n = llama_token_to_piece(state->model, new_token, buf, sizeof(buf) - 1);
        if (n > 0) {
            buf[n] = 0;
            int copy_len = n;
            if (resp_len + copy_len > 4095) copy_len = 4095 - resp_len;
            memcpy(response + resp_len, buf, copy_len);
            resp_len += copy_len;
        }

        llama_batch batch = llama_batch_get_one(&new_token, 1, n_tokens + i, 0);
        if (llama_decode(state->ctx, batch)) break;
    }

    response[resp_len] = 0;
    return (*env)->NewStringUTF(env, response);
}

JNIEXPORT void JNICALL
Java_com_example_englishcoach_LLMEngine_nativeDestroy(JNIEnv *env, jobject thiz, jlong modelPtr) {
    LlamaState *state = (LlamaState *)(intptr_t)modelPtr;

    if (state != NULL) {
        if (state->ctx) llama_free(state->ctx);
        if (state->model) llama_free_model(state->model);
        free(state);
    }
}
