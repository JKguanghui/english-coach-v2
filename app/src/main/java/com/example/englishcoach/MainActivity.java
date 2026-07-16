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
import android.view.MotionEvent;
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
    private Button btnTalk;
    private ScrollView scrollAi;
    private Handler handler = new Handler(Looper.getMainLooper());

    // Audio
    private AudioRecord audioRecord;
    private AcousticEchoCanceler aec;
    private boolean isRecording = false;
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
        btnTalk = findViewById(R.id.btnTalk);
        scrollAi = findViewById(R.id.scrollAi);

        // Push-to-talk button
        btnTalk.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startRecording();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopRecording();
                    return true;
            }
            return false;
        });
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

        // Enable AEC if available
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
            if (aec != null) {
                aec.setEnabled(true);
                Log.i(TAG, "AEC enabled");
            }
        }

        isInitialized = true;
        tvStatus.setText("Ready - Hold to talk");
    }

    private void startRecording() {
        if (!isInitialized || audioRecord == null) {
            Toast.makeText(this, "Not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        isRecording = true;
        btnTalk.setText("Listening...");
        btnTalk.setBackgroundColor(0xFFE53935);
        tvUserSubtitle.setText("");

        audioRecord.startRecording();

        recordingThread = new Thread(() -> {
            short[] buffer = new short[1024];
            while (isRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    // TODO: Process audio with Sherpa-ONNX VAD + STT
                    // For now, just show that we're recording
                    handler.post(() -> tvUserSubtitle.setText("Recording... " + read + " samples"));
                }
            }
        });
        recordingThread.start();
    }

    private void stopRecording() {
        isRecording = false;
        btnTalk.setText("Hold to Talk");
        btnTalk.setBackgroundColor(0xFF4CAF50);

        if (audioRecord != null) {
            audioRecord.stop();
        }

        // TODO: Process recorded audio with STT
        handler.post(() -> {
            tvUserSubtitle.setText("Processing...");
            // Simulate AI response
            simulateAIResponse();
        });
    }

    private void simulateAIResponse() {
        // TODO: Replace with actual LLM call
        String response = "Hello! I'm your English tutor. What would you like to practice today?";
        tvAiSubtitle.append(response + "\n");
        scrollAi.post(() -> scrollAi.fullScroll(View.FOCUS_DOWN));
        tvStatus.setText("Ready - Hold to talk");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
