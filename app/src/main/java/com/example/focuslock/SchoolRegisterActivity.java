package com.example.focuslock;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.firestore.FirebaseFirestore;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

public class SchoolRegisterActivity extends AppCompatActivity {

    EditText etName, etCode, etPass;
    FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_school_register);

        db = FirebaseFirestore.getInstance();
        etName = findViewById(R.id.etSchoolName);
        etCode = findViewById(R.id.etSchoolCode);
        etPass = findViewById(R.id.etTeacherPassword);

        findViewById(R.id.btnRegisterSchool).setOnClickListener(v -> registerSchool());
    }

    private void registerSchool() {
        String name = etName.getText().toString().trim();
        String code = etCode.getText().toString().trim().toUpperCase();
        String pass = etPass.getText().toString().trim();

        if (name.isEmpty()) {
            toast("Enter school / college name");
            return;
        }
        if (!code.matches("[A-Z0-9]{4,10}")) {
            toast("Code must be 4-10 letters or numbers (no spaces)");
            return;
        }
        if (pass.length() < 6) {
            toast("Teacher password must be at least 6 characters");
            return;
        }

        db.collection("schools").document(code).get().addOnSuccessListener(doc -> {
            if (doc.exists()) {
                toast("This code is already taken. Choose another code");
                return;
            }
            Map<String, Object> school = new HashMap<>();
            school.put("name", name);
            school.put("code", code);
            school.put("teacherPassHash", sha256(pass));

            db.collection("schools").document(code).set(school)
                    .addOnSuccessListener(a -> showDone(code))
                    .addOnFailureListener(e -> toast("Save failed: " + e.getMessage()));
        }).addOnFailureListener(e -> toast("Error: " + e.getMessage()));
    }

    private void showDone(String code) {
        new AlertDialog.Builder(this)
                .setTitle("School registered!")
                .setMessage("School Code: " + code
                        + "\n\nGive this code to teachers and students."
                        + "\nGive the Teacher password only to teachers.")
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> finish())
                .show();
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