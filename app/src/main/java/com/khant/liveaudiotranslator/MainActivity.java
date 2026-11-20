package com.khant.liveaudiotranslator;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_MEDIA_PROJECTION = 1001;
    private static final int REQ_AUDIO_PERM = 1002;

    private EditText apiKeyInput;
    private RadioGroup langGroup;
    private RadioGroup styleGroup;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        SharedPreferences prefs = getSharedPreferences("LiveAudioPrefs", MODE_PRIVATE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        TextView title = new TextView(this);
        title.setText("Live Audio Translator v2");
        title.setTextSize(22);
        layout.addView(title);

        TextView keyLabel = new TextView(this);
        keyLabel.setText("\nGemini API Key ထည့်ပါ:");
        layout.addView(keyLabel);

        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("AIzaSy...");
        apiKeyInput.setText(prefs.getString("api_key", ""));
        layout.addView(apiKeyInput);

        TextView langLabel = new TextView(this);
        langLabel.setText("\nဘာသာပြန်မည့် ဘာသာစကား:");
        layout.addView(langLabel);

        langGroup = new RadioGroup(this);
        RadioButton rbMy = new RadioButton(this);
        rbMy.setText("မြန်မာ (Burmese)");
        rbMy.setId(1);
        rbMy.setChecked(true);
        RadioButton rbEn = new RadioButton(this);
        rbEn.setText("အင်္ဂလိပ် (English)");
        rbEn.setId(2);
        langGroup.addView(rbMy);
        langGroup.addView(rbEn);
        layout.addView(langGroup);

        TextView styleLabel = new TextView(this);
        styleLabel.setText("\nဘာသာပြန်ပုံစံ:");
        layout.addView(styleLabel);

        styleGroup = new RadioGroup(this);
        RadioButton rbNormal = new RadioButton(this);
        rbNormal.setText("ယဉ်ကျေး/ပုံမှန် (Normal)");
        rbNormal.setId(1);
        RadioButton rbSlang = new RadioButton(this);
        rbSlang.setText("ခပ်ကြမ်းကြမ်း/ရိုင်း (Slang/Uncensored)");
        rbSlang.setId(2);
        rbSlang.setChecked(true);
        styleGroup.addView(rbNormal);
        styleGroup.addView(rbSlang);
        layout.addView(styleGroup);

        Button startBtn = new Button(this);
        startBtn.setText("System Audio ဘာသာပြန် စတင်မည်");
        startBtn.setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, "Gemini API Key အရင်ထည့်ပါ", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putString("api_key", key).apply();

            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                Toast.makeText(this, "Display over other apps ကို ဖွင့်ပေးပါ", Toast.LENGTH_LONG).show();
                return;
            }

            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERM);
                return;
            }

            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
        });
        layout.addView(startBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("ပိတ်မည် (Stop)");
        stopBtn.setOnClickListener(v -> {
            stopService(new Intent(this, FloatingService.class));
            Toast.makeText(this, "ပိတ်လိုက်ပါပြီ", Toast.LENGTH_SHORT).show();
        });
        layout.addView(stopBtn);

        setContentView(layout);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            Intent serviceIntent = new Intent(this, FloatingService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("data", data);
            serviceIntent.putExtra("apiKey", apiKeyInput.getText().toString().trim());
            serviceIntent.putExtra("lang", langGroup.getCheckedRadioButtonId() == 1 ? "Burmese" : "English");
            serviceIntent.putExtra("slang", styleGroup.getCheckedRadioButtonId() == 2);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Toast.makeText(this, "Live Audio Translator စတင်ပါပြီ", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
