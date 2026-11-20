package com.khant.liveaudiotranslator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class FloatingService extends Service {
    private static final String CHANNEL_ID = "LiveAudioTranslatorChannel";
    private static final int SAMPLE_RATE = 16000;

    private WindowManager windowManager;
    private LinearLayout floatingView;
    private TextView subtitleView;
    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private volatile boolean isRunning = false;

    private String apiKey = "";
    private String targetLang = "Burmese";
    private boolean isSlang = true;

    @Override
    public void onCreate() {
        super.onCreate();
        setupOverlay();
    }

    private void setupOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        floatingView = new LinearLayout(this);
        floatingView.setOrientation(LinearLayout.HORIZONTAL);
        floatingView.setBackgroundColor(Color.argb(180, 0, 0, 0));
        floatingView.setPadding(24, 16, 24, 16);
        floatingView.setGravity(Gravity.CENTER_VERTICAL);

        subtitleView = new TextView(this);
        subtitleView.setText("အသံနားထောင်နေသည်...");
        subtitleView.setTextColor(Color.WHITE);
        subtitleView.setTextSize(16);
        subtitleView.setMaxWidth(750);
        floatingView.addView(subtitleView);

        Button closeBtn = new Button(this);
        closeBtn.setText("X");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setBackgroundColor(Color.TRANSPARENT);
        closeBtn.setOnClickListener(v -> stopSelf());
        floatingView.addView(closeBtn);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = 80;

        windowManager.addView(floatingView, params);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();

        if (intent != null && !isRunning) {
            int resultCode = intent.getIntExtra("resultCode", -1);
            Intent data = intent.getParcelableExtra("data");
            apiKey = intent.getStringExtra("apiKey");
            targetLang = intent.getStringExtra("lang");
            isSlang = intent.getBooleanExtra("slang", true);

            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm != null && data != null) {
                mediaProjection = mpm.getMediaProjection(resultCode, data);
                startAudioCapture();
            } else {
                updateSubtitle("Error: MediaProjection မရပါ");
            }
        }
        return START_NOT_STICKY;
    }

    private void startForegroundNotification() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Live Audio Translator", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Live Audio Translator v2")
                .setContentText("System audio ကို ဘာသာပြန်နေသည်...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }
    }

    private void startAudioCapture() {
        isRunning = true;
        new Thread(() -> {
            try {
                AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build();

                int chunkBytes = SAMPLE_RATE * 2 * 4;
                int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

                audioRecord = new AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(Math.max(minBuf * 4, chunkBytes))
                        .setAudioPlaybackCaptureConfig(config)
                        .build();

                audioRecord.startRecording();
                byte[] buffer = new byte[4096];

                while (isRunning) {
                    ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
                    long maxAmp = 0;

                    while (isRunning && pcmOut.size() < chunkBytes) {
                        int read = audioRecord.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            pcmOut.write(buffer, 0, read);
                            for (int i = 0; i < read - 1; i += 2) {
                                int sample = Math.abs((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                                if (sample > maxAmp) maxAmp = sample;
                            }
                        }
                    }

                    if (!isRunning) break;
                    if (maxAmp > 50) {
                        byte[] wavBytes = addWavHeader(pcmOut.toByteArray());
                        sendToGemini(wavBytes);
                    } else {
                        updateSubtitle("အသံမမိပါ (Volume ချဲ့ပါ သို့မဟုတ် App ပြောင်းဖွင့်ပါ)");
                    }
                }
            } catch (Exception e) {
                updateSubtitle("Audio Error: " + e.getMessage());
            }
        }).start();
    }

    private void sendToGemini(byte[] wavData) {
        new Thread(() -> {
            try {
                String base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP);
                String stylePrompt = isSlang
                        ? "Translate into natural, raw, uncensored slang/colloquial " + targetLang + " (keep vulgar/profane tone if present)."
                        : "Translate into natural, polite " + targetLang + ".";

                String prompt = "Listen to this audio clip. " + stylePrompt +
                        " Output ONLY the translated subtitle text. If there is no speech, output nothing.";

                JSONObject inlineData = new JSONObject();
                inlineData.put("mimeType", "audio/wav");
                inlineData.put("data", base64Audio);

                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("text", prompt));
                parts.put(new JSONObject().put("inlineData", inlineData));

                JSONObject content = new JSONObject();
                content.put("parts", parts);

                JSONArray safetySettings = new JSONArray();
                String[] categories = {
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT"
                };
                for (String cat : categories) {
                    JSONObject s = new JSONObject();
                    s.put("category", cat);
                    s.put("threshold", "BLOCK_NONE");
                    safetySettings.put(s);
                }

                JSONObject payload = new JSONObject();
                payload.put("contents", new JSONArray().put(content));
                payload.put("safetySettings", safetySettings);

                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + apiKey);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes("UTF-8"));
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    JSONObject resp = new JSONObject(sb.toString());
                    if (resp.has("candidates")) {
                        JSONObject cand = resp.getJSONArray("candidates").getJSONObject(0);
                        if (cand.has("content")) {
                            String text = cand.getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .getString("text")
                                    .trim();
                            if (!text.isEmpty()) {
                                updateSubtitle(text);
                            }
                        }
                    }
                } else {
                    updateSubtitle("API Error: " + code);
                }
            } catch (Exception e) {
                updateSubtitle("Net Error: " + e.getMessage());
            }
        }).start();
    }

    private byte[] addWavHeader(byte[] pcm) {
        int totalDataLen = pcm.length + 36;
        int byteRate = SAMPLE_RATE * 2;
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = 1; header[23] = 0;
        header[24] = (byte) (SAMPLE_RATE & 0xff);
        header[25] = (byte) ((SAMPLE_RATE >> 8) & 0xff);
        header[26] = (byte) ((SAMPLE_RATE >> 16) & 0xff);
        header[27] = (byte) ((SAMPLE_RATE >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = 2; header[33] = 0;
        header[34] = 16; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcm.length & 0xff);
        header[41] = (byte) ((pcm.length >> 8) & 0xff);
        header[42] = (byte) ((pcm.length >> 16) & 0xff);
        header[43] = (byte) ((pcm.length >> 24) & 0xff);

        byte[] wav = new byte[44 + pcm.length];
        System.arraycopy(header, 0, wav, 0, 44);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    private void updateSubtitle(String text) {
        new Handler(Looper.getMainLooper()).post(() -> subtitleView.setText(text));
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
        }
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
        }
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
