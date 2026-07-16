package com.example.englishcoach;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ModelDownloadManager {
    private static final String TAG = "ModelDownloadManager";
    private Context context;
    private OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
        .build();

    // Qwen model - Chinese mirror first, HuggingFace fallback
    private static final String QWEN_MODEL_URL_CN = "https://modelscope.cn/api/v1/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF/repo?Revision=master&FilePath=qwen2.5-1.5b-instruct-q4_k_m.gguf";
    private static final String QWEN_MODEL_URL_HF = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf";

    public interface DownloadCallback {
        void onProgress(int percent, long downloaded, long total);
        void onSuccess(File file);
        void onError(String error);
        void onStatusUpdate(String status);
    }

    public ModelDownloadManager(Context context) {
        this.context = context;
    }

    public static File getQwenModelFile(Context context) {
        return new File(context.getFilesDir(), "qwen2.5-1.5b-instruct-q4_k_m.gguf");
    }

    public static boolean isQwenReady(Context context) {
        File f = getQwenModelFile(context);
        // Qwen 1.5B Q4_K_M should be around 1.1GB, check at least 500MB
        return f.exists() && f.length() > 500_000_000;
    }

    public void downloadQwenModel(DownloadCallback callback) {
        if (isQwenReady(context)) {
            callback.onSuccess(getQwenModelFile(context));
            return;
        }

        callback.onStatusUpdate("Downloading AI model...");
        downloadFile(QWEN_MODEL_URL_CN, getQwenModelFile(context), 
            new DownloadCallback() {
                @Override
                public void onProgress(int percent, long downloaded, long total) {
                    callback.onProgress(percent, downloaded, total);
                }

                @Override
                public void onSuccess(File file) {
                    callback.onSuccess(file);
                }

                @Override
                public void onError(String error) {
                    callback.onStatusUpdate("Retrying from HuggingFace...");
                    downloadFile(QWEN_MODEL_URL_HF, getQwenModelFile(context),
                        new DownloadCallback() {
                            @Override
                            public void onProgress(int p, long d, long t) { callback.onProgress(p, d, t); }
                            @Override
                            public void onSuccess(File f) { callback.onSuccess(f); }
                            @Override
                            public void onError(String e) { callback.onError(e); }
                            @Override
                            public void onStatusUpdate(String s) { callback.onStatusUpdate(s); }
                        });
                }

                @Override
                public void onStatusUpdate(String status) {
                    callback.onStatusUpdate(status);
                }
            });
    }

    private void downloadFile(String url, File outputFile, DownloadCallback callback) {
        new Thread(() -> {
            try {
                Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build();
                Response response = client.newCall(request).execute();
                
                if (!response.isSuccessful()) {
                    callback.onError("Download failed: HTTP " + response.code());
                    return;
                }

                ResponseBody body = response.body();
                if (body == null) {
                    callback.onError("Empty response");
                    return;
                }

                long totalBytes = body.contentLength();
                InputStream inputStream = body.byteStream();
                FileOutputStream outputStream = new FileOutputStream(outputFile);

                byte[] buffer = new byte[8192];
                long downloadedBytes = 0;
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    downloadedBytes += bytesRead;

                    if (totalBytes > 0) {
                        int progress = (int) ((downloadedBytes * 100) / totalBytes);
                        callback.onProgress(progress, downloadedBytes, totalBytes);
                    }
                }

                outputStream.flush();
                outputStream.close();
                inputStream.close();

                callback.onSuccess(outputFile);
            } catch (IOException e) {
                Log.e(TAG, "Download error", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }
}
