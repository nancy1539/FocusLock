package com.example.focuslock;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.firestore.FirebaseFirestore;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

public class TeacherLoginActivity extends AppCompatActivity {

    EditText etCode, etName, etPhone, etPassword;
    FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher_login);

        db = FirebaseFirestore.getInstance();

        etCode = findViewById(R.id.etSchoolCode);
        etName = findViewById(R.id.etName);
        etPhone = findViewById(R.id.etPhone);
        etPassword = findViewById(R.id.etPassword);

        findViewById(R.id.btnLogin).setOnClickListener(v -> login());
    }

    private void login() {
        String code = etCode.getText().toString().trim().toUpperCase();
        String name = etName.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        String pass = etPassword.getText().toString().trim();

        if (code.isEmpty() || name.isEmpty() || phone.length() != 10 || pass.isEmpty()) {
            toast("Fill all details correctly");
            return;
        }

        db.collection("schools").document(code).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) {
                        toast("Invalid school code");
                        return;
                    }
                    String saved = doc.getString("teacherPassHash");
                    if (saved == null) saved = doc.getString("passHash");   // older schools
                    if (saved == null || !saved.equals(sha256(pass))) {
                        toast("Wrong teacher password");
                        return;
                    }

                    Map<String, Object> teacher = new HashMap<>();
                    teacher.put("name", name);
                    teacher.put("phone", phone);

                    db.collection("schools").document(code)
                            .collection("teachers").document(phone).set(teacher)
                            .addOnSuccessListener(a -> {
                                SharedPreferences sp = getSharedPreferences("focus", MODE_PRIVATE);
                                sp.edit()
                                        .putString("schoolCode", code)
                                        .putString("teacherName", name)
                                        .putString("teacherPhone", phone)
                                        .apply();
                                startActivity(new Intent(this, TeacherDashboardActivity.class));
                                finish();
                            })
                            .addOnFailureListener(e -> toast("Save failed: " + e.getMessage()));
                })
                .addOnFailureListener(e -> toast("Error: " + e.getMessage()));
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return text;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}