package com.example.focuslock;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class StudentLoginActivity extends AppCompatActivity {

    EditText etCode, etName, etRoll, etPhone, etPin;
    FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student_login);

        db = FirebaseFirestore.getInstance();

        etCode = findViewById(R.id.etSchoolCode);
        etName = findViewById(R.id.etName);
        etRoll = findViewById(R.id.etRoll);
        etPhone = findViewById(R.id.etPhone);
        etPin = findViewById(R.id.etPin);

        findViewById(R.id.btnLogin).setOnClickListener(v -> login());
    }

    private void login() {
        String code = etCode.getText().toString().trim().toUpperCase();
        String name = etName.getText().toString().trim();
        String roll = etRoll.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        String pin = etPin.getText().toString().trim();

        if (code.isEmpty() || name.isEmpty() || roll.isEmpty()
                || phone.length() != 10 || pin.length() != 4) {
            toast("Fill all details correctly (PIN is 4 digits)");
            return;
        }

        db.collection("schools").document(code).get().addOnSuccessListener(school -> {
            if (!school.exists()) {
                toast("Invalid school code");
                return;
            }
            school.getReference().collection("classes").get().addOnSuccessListener(classes -> {
                if (classes.isEmpty()) {
                    toast("No classes found. Ask your teacher");
                    return;
                }

                // Look for this student in every class of the school
                List<DocumentSnapshot> matches = new ArrayList<>();
                int[] pending = {classes.size()};

                for (DocumentSnapshot c : classes.getDocuments()) {
                    c.getReference().collection("students")
                            .whereEqualTo("phone", phone).get()
                            .addOnSuccessListener(stus -> {
                                for (DocumentSnapshot s : stus.getDocuments()) {
                                    if (roll.equals(s.getString("rollNo"))
                                            && name.equalsIgnoreCase(s.getString("name"))) {
                                        matches.add(s);
                                    }
                                }
                                pending[0]--;
                                if (pending[0] == 0) finishLogin(code, matches, pin);
                            })
                            .addOnFailureListener(e -> {
                                pending[0]--;
                                if (pending[0] == 0) finishLogin(code, matches, pin);
                            });
                }
            }).addOnFailureListener(e -> toast("Error: " + e.getMessage()));
        }).addOnFailureListener(e -> toast("Error: " + e.getMessage()));
    }

    private void finishLogin(String code, List<DocumentSnapshot> matches, String pin) {
        if (matches.isEmpty()) {
            toast("Details do not match your teacher's list. Check name, roll no and phone");
            return;
        }
        DocumentSnapshot stu = matches.get(0);
        String classId = stu.getReference().getParent().getParent().getId();

        String savedPin = stu.getString("pinHash");
        if (savedPin == null) {
            // First login: this PIN becomes the student's PIN
            stu.getReference().update("pinHash", sha256(pin))
                    .addOnSuccessListener(a -> saveAndGo(code, classId, stu))
                    .addOnFailureListener(e -> toast("Save failed: " + e.getMessage()));
        } else if (savedPin.equals(sha256(pin))) {
            saveAndGo(code, classId, stu);
        } else {
            toast("Wrong PIN");
        }
    }

    private void saveAndGo(String code, String classId, DocumentSnapshot stu) {
        getSharedPreferences("focus", MODE_PRIVATE).edit()
                .putString("role", "student")
                .putString("schoolCode", code)
                .putString("classId", classId)
                .putString("studentId", stu.getId())
                .putString("studentName", stu.getString("name"))
                .apply();
        startActivity(new Intent(this, StudentHomeActivity.class));
        finish();
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