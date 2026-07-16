package com.example.englishcoach;

import android.content.Context;
import android.util.Log;
import java.io.File;

public class LLMEngine {
    private static final String TAG = "LLMEngine";
    private boolean loaded = false;
    private Context ctx;

    static {
        System.loadLibrary("llama-android");
    }

    // Native methods
    private native long nativeCreate(String modelPath, int contextSize);
    private native String nativeGenerate(long modelPtr, String prompt, int maxTokens);
    private native void nativeDestroy(long modelPtr);

    private long modelPtr = 0;

    public LLMEngine(Context c) { this.ctx = c; }

    public boolean loadModel() {
        File f = new File(ctx.getFilesDir(), "qwen2.5-1.5b-instruct-q4_k_m.gguf");
        if (!f.exists()) {
            Log.e(TAG, "Model file not found: " + f.getAbsolutePath());
            return false;
        }
        try {
            modelPtr = nativeCreate(f.getAbsolutePath(), 2048);
            loaded = (modelPtr != 0);
            Log.i(TAG, "Model loaded: " + loaded);
            return loaded;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load model", e);
            return false;
        }
    }

    public String generate(String systemPrompt, String userInput) {
        if (!loaded || modelPtr == 0) return "Model not loaded.";
        try {
            String fullPrompt = systemPrompt + "\n\nUser: " + userInput + "\nAssistant:";
            return nativeGenerate(modelPtr, fullPrompt, 512);
        } catch (Exception e) {
            Log.e(TAG, "Generate failed", e);
            return "Error generating response.";
        }
    }

    public void destroy() {
        if (modelPtr != 0) {
            nativeDestroy(modelPtr);
            modelPtr = 0;
        }
        loaded = false;
    }
}
