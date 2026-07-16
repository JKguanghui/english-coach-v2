package com.example.englishcoach;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "EnglishCoach";
    private static final int REQ_AUDIO = 100;
    private static final String PREF_NAME = "english_coach_prefs";
    private static final String KEY_AGREED = "is_agreed";

    // System Prompt
    private static final String SYSTEM_PROMPT =
        "You are a friendly, patient English tutor. This is your ONLY identity. "
        + "When the student tells you what scenario to practice, immediately adopt that role. "
        + "Lead the conversation in English. "
        + "If the student makes a grammar mistake, gently correct it first, then continue. "
        + "If the student speaks in Chinese, respond in Chinese to explain, then guide back to English. "
        + "Keep responses concise and spoken-language friendly. "
        + "Never use Markdown, code blocks, or bullet points. "
        + "Always reply in plain, natural English paragraphs.";

    // UI
    private TextView tvStatus, tvUserSubtitle, tvAiSubtitle;
    private Button btnCall, btnDownload;
    private ProgressBar progressBar;
    private ScrollView scrollAi;
    private Handler handler = new Handler(Looper.getMainLooper());

    // Speech
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private boolean isCallActive = false;
    private boolean isTtsSpeaking = false;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    // LLM
    private LLMEngine llmEngine;
    private ModelDownloadManager downloadManager;
    private boolean llmReady = false;

    // State
    private boolean isInitialized = false;
    private boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Check agreement
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_AGREED, false)) {
            showAgreementDialog(prefs);
        }

        initViews();
        checkPermission();
    }

    private void showAgreementDialog(SharedPreferences prefs) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("User Agreement")
            .setMessage("This app uses AI for English practice. All data stays on device.")
            .setCancelable(false)
            .setPositiveButton("Agree", (d, w) -> prefs.edit().putBoolean(KEY_AGREED, true).apply())
            .setNegativeButton("Exit", (d, w) -> finish())
            .show();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvUserSubtitle = findViewById(R.id.tvUserSubtitle);
        tvAiSubtitle = findViewById(R.id.tvAiSubtitle);
        btnCall = findViewById(R.id.btnCall);
        btnDownload = findViewById(R.id.btnDownload);
        progressBar = findViewById(R.id.progressBar);
        scrollAi = findViewById(R.id.scrollAi);

        // Call button
        btnCall.setOnClickListener(v -> toggleCall());
        
        // Download button
        btnDownload.setOnClickListener(v -> startDownload());
    }

    private void checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        } else {
            initApp();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            initApp();
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show();
        }
    }

    private void initApp() {
        // Init download manager
        downloadManager = new ModelDownloadManager(this);
        
        // Check if model exists
        if (ModelDownloadManager.isQwenReady(this)) {
            // Model exists, load it
            loadModel();
        } else {
            // Model not found, show download button
            tvStatus.setText("Download AI model first");
            btnDownload.setVisibility(View.VISIBLE);
            btnCall.setVisibility(View.GONE);
        }

        // Init SpeechRecognizer
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.i(TAG, "Ready for speech");
            }

            @Override
            public void onBeginningOfSpeech() {
                Log.i(TAG, "Speech started");
            }

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                Log.i(TAG, "Speech ended");
            }

            @Override
            public void onError(int error) {
                Log.e(TAG, "Speech error: " + error);
                if (isCallActive && !isTtsSpeaking) {
                    handler.postDelayed(() -> startListening(), 500);
                }
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0);
                    Log.i(TAG, "Recognized: " + text);
                    handler.post(() -> tvUserSubtitle.setText(text));
                    
                    // Process with LLM
                    if (!isProcessing.get()) {
                        isProcessing.set(true);
                        processWithLLM(text);
                    }
                }
                
                if (isCallActive && !isTtsSpeaking) {
                    handler.postDelayed(() -> startListening(), 500);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial != null && !partial.isEmpty()) {
                    handler.post(() -> tvUserSubtitle.setText(partial.get(0) + "..."));
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });

        // Init TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        isTtsSpeaking = true;
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        isTtsSpeaking = false;
                        if (isCallActive) {
                            handler.postDelayed(() -> startListening(), 300);
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        isTtsSpeaking = false;
                        if (isCallActive) {
                            handler.postDelayed(() -> startListening(), 300);
                        }
                    }
                });
                ttsReady = true;
                Log.i(TAG, "TTS initialized");
            } else {
                Log.e(TAG, "TTS initialization failed");
            }
        });

        isInitialized = true;
    }

    private void loadModel() {
        tvStatus.setText("Loading model...");
        btnDownload.setVisibility(View.GONE);
        btnCall.setVisibility(View.VISIBLE);
        
        llmEngine = new LLMEngine(this);
        new Thread(() -> {
            boolean ok = llmEngine.loadModel();
            llmReady = ok;
            handler.post(() -> {
                if (ok) {
                    tvStatus.setText("Ready");
                } else {
                    tvStatus.setText("Model load failed");
                }
            });
        }).start();
    }

    private void startDownload() {
        btnDownload.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        
        downloadManager.downloadQwenModel(new ModelDownloadManager.DownloadCallback() {
            @Override
            public void onProgress(int percent, long downloaded, long total) {
                handler.post(() -> progressBar.setProgress(percent));
            }

            @Override
            public void onSuccess(File file) {
                handler.post(() -> {
                    progressBar.setVisibility(View.GONE);
                    loadModel();
                });
            }

            @Override
            public void onError(String error) {
                handler.post(() -> {
                    tvStatus.setText("Download failed: " + error);
                    btnDownload.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                });
            }

            @Override
            public void onStatusUpdate(String status) {
                handler.post(() -> tvStatus.setText(status));
            }
        });
    }

    private void toggleCall() {
        if (!isInitialized) {
            Toast.makeText(this, "Not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!ttsReady) {
            Toast.makeText(this, "TTS still loading", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!llmReady) {
            Toast.makeText(this, "LLM still loading", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isCallActive) {
            endCall();
        } else {
            startCall();
        }
    }

    private void startCall() {
        isCallActive = true;
        btnCall.setText("End");
        btnCall.setBackgroundColor(0xFFE53935);
        tvUserSubtitle.setText("");
        tvAiSubtitle.setText("");
        tvStatus.setText("Listening...");

        // Welcome message
        String welcome = "Hi! I'm your English tutor. What would you like to practice today?";
        tvAiSubtitle.append(welcome + "\n");
        scrollAi.post(() -> scrollAi.fullScroll(View.FOCUS_DOWN));
        speak(welcome);
    }

    private void endCall() {
        isCallActive = false;
        isTtsSpeaking = false;
        isProcessing.set(false);
        btnCall.setText("Start");
        btnCall.setBackgroundColor(0xFF4CAF50);
        tvStatus.setText("Ready");

        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        if (tts != null) {
            tts.stop();
        }
    }

    private void startListening() {
        if (!isCallActive || isTtsSpeaking || isProcessing.get()) {
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        
        try {
            speechRecognizer.startListening(intent);
            tvStatus.setText("Listening...");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start listening", e);
        }
    }

    private void processWithLLM(String userText) {
        tvStatus.setText("Thinking...");
        
        new Thread(() -> {
            try {
                String response = llmEngine.generate(SYSTEM_PROMPT, userText);
                
                handler.post(() -> {
                    tvAiSubtitle.append(response + "\n");
                    scrollAi.post(() -> scrollAi.fullScroll(View.FOCUS_DOWN));
                    tvStatus.setText("Speaking...");
                    isProcessing.set(false);
                    speak(response);
                });
            } catch (Exception e) {
                Log.e(TAG, "LLM processing failed", e);
                handler.post(() -> {
                    isProcessing.set(false);
                    tvStatus.setText("Error: " + e.getMessage());
                });
            }
        }).start();
    }

    private void speak(String text) {
        if (tts != null && ttsReady) {
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "tts_" + System.currentTimeMillis());
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "tts_" + System.currentTimeMillis());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isCallActive = false;
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (llmEngine != null) {
            llmEngine.destroy();
        }
    }
}
