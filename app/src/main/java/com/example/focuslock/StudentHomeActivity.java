package com.example.focuslock;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class StudentHomeActivity extends AppCompatActivity {

    TextView tvStatus;
    Button btnEnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student_home);

        String name = getSharedPreferences("focus", MODE_PRIVATE)
                .getString("studentName", "");
        ((TextView) findViewById(R.id.tvWelcome)).setText("Welcome, " + name);

        tvStatus = findViewById(R.id.tvStatus);
        btnEnable = findViewById(R.id.btnEnable);
        btnEnable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isServiceEnabled()) {
            tvStatus.setText("✅ Blocking is ON.\nYour teacher's lock will work on this phone.");
            btnEnable.setVisibility(View.GONE);
        } else {
            tvStatus.setText("⚠️ Blocking is OFF.\nTap below, find FocusLock and turn it ON.");
            btnEnable.setVisibility(View.VISIBLE);
        }
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName())
                && enabled.contains("BlockerService");
    }
}