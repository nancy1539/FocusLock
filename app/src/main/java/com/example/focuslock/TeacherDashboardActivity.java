package com.example.focuslock;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class TeacherDashboardActivity extends AppCompatActivity {

    String schoolCode;
    CollectionReference classesRef;
    ListenerRegistration listener;

    ArrayList<String> classNames = new ArrayList<>();
    ArrayList<String> classIds = new ArrayList<>();
    ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_teacher_dashboard);

        SharedPreferences sp = getSharedPreferences("focus", MODE_PRIVATE);
        schoolCode = sp.getString("schoolCode", "");
        String teacherName = sp.getString("teacherName", "");

        TextView tvWelcome = findViewById(R.id.tvWelcome);
        tvWelcome.setText("Welcome, " + teacherName);

        classesRef = FirebaseFirestore.getInstance()
                .collection("schools").document(schoolCode).collection("classes");

        ListView list = findViewById(R.id.listClasses);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, classNames);
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent i = new Intent(this, ClassActivity.class);
            i.putExtra("classId", classIds.get(position));
            i.putExtra("className", classNames.get(position));
            startActivity(i);
        });

        Button btnAdd = findViewById(R.id.btnAddClass);
        btnAdd.setOnClickListener(v -> showAddClassDialog());

        listener = classesRef.addSnapshotListener((snap, e) -> {
            if (snap == null) return;
            classNames.clear();
            classIds.clear();
            for (DocumentSnapshot d : snap.getDocuments()) {
                classIds.add(d.getId());
                classNames.add(d.getString("name"));
            }
            adapter.notifyDataSetChanged();
        });
    }

    private void showAddClassDialog() {
        EditText et = new EditText(this);
        et.setHint("Class name (e.g. BCA-1)");

        new AlertDialog.Builder(this)
                .setTitle("Add Class")
                .setView(et)
                .setPositiveButton("Add", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter class name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Map<String, Object> data = new HashMap<>();
                    data.put("name", name);
                    classesRef.add(data)
                            .addOnFailureListener(e ->
                                    Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (listener != null) listener.remove();
    }
}