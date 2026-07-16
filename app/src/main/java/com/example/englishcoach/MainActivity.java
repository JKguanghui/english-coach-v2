package com.example.englishcoach;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "EnglishCoach";
    private static final int REQ_AUDIO = 100;
    private static final int SAMPLE_RATE = 16000;
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
    private Button btnCall;
    private ScrollView scrollAi;
    private Handler handler = new Handler(Looper.getMainLooper());

    // Audio
    private AudioRecord audioRecord;
    private AcousticEchoCanceler aec;
    private boolean isCallActive = false;
    private Thread recordingThread;

    // State
    private boolean isInitialized = false;

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
        scrollAi = findViewById(R.id.scrollAi);

        // Call button
        btnCall.setOnClickListener(v -> toggleCall());
    }

    private void checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        } else {
            initAudio();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            initAudio();
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show();
        }
    }

    private void initAudio() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, 
            AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);

        // Enable AEC
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
            if (aec != null) {
                aec.setEnabled(true);
                Log.i(TAG, "AEC enabled");
            }
        }

        isInitialized = true;
        tvStatus.setText("Ready");
    }

    private void toggleCall() {
        if (!isInitialized) {
            Toast.makeText(this, "Not ready", Toast.LENGTH_SHORT).show();
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

        // Start recording
        audioRecord.startRecording();

        recordingThread = new Thread(() -> {
            short[] buffer = new short[1024];
            while (isCallActive) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    // TODO: Process with VAD + STT
                    // For now, just show we're listening
                    handler.post(() -> tvUserSubtitle.setText("Listening..."));
                }
            }
        });
        recordingThread.start();

        // Welcome message
        appendAiMessage("Hi! I'm your English tutor. What would you like to practice today?");
        ttsSpeak("Hi! I'm your English tutor. What would you like to practice today?");
    }

    private void endCall() {
        isCallActive = false;
        btnCall.setText("Start");
        btnCall.setBackgroundColor(0xFF4CAF50);
        tvStatus.setText("Ready");

        if (audioRecord != null) {
            audioRecord.stop();
        }
    }

    private void appendAiMessage(String text) {
        tvAiSubtitle.append(text + "\n");
        scrollAi.post(() -> scrollAi.fullScroll(View.FOCUS_DOWN));
    }

    private void ttsSpeak(String text) {
        // TODO: Integrate TTS
        Log.i(TAG, "TTS: " + text);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isCallActive = false;
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
        if (aec != null) {
            aec.release();
            aec = null;
        }
    }
}
