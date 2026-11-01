package com.example.focuslock;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ClassActivity extends AppCompatActivity {

    CollectionReference studentsRef, attendanceRef;
    DocumentReference sessionRef;
    ListenerRegistration studentsListener, attendanceListener;
    ListView list;
    TextView tvCount;
    Button btnDate;

    ArrayList<String> labels = new ArrayList<>();
    ArrayList<String> ids = new ArrayList<>();
    Set<String> presentSet = new HashSet<>();
    ArrayAdapter<String> adapter;

    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    String selectedDate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_class);

        String classId = getIntent().getStringExtra("classId");
        String className = getIntent().getStringExtra("className");
        String schoolCode = getSharedPreferences("focus", MODE_PRIVATE)
                .getString("schoolCode", "");

        ((TextView) findViewById(R.id.tvClassName)).setText(className);
        tvCount = findViewById(R.id.tvCount);
        btnDate = findViewById(R.id.btnDate);

        DocumentReference classRef = FirebaseFirestore.getInstance()
                .collection("schools").document(schoolCode)
                .collection("classes").document(classId);
        studentsRef = classRef.collection("students");
        attendanceRef = classRef.collection("attendance");
        sessionRef = classRef.collection("session").document("current");

        list = findViewById(R.id.listStudents);
        adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, labels);
        list.setAdapter(adapter);

        selectedDate = fmt.format(Calendar.getInstance().getTime());
        btnDate.setText("Date: " + selectedDate + "  (tap to change)");
        btnDate.setOnClickListener(v -> pickDate());

        // Tap = Present/Absent for the selected date
        list.setOnItemClickListener((parent, view, position, id) -> {
            String studentId = ids.get(position);
            boolean present = list.isItemChecked(position);
            if (present) presentSet.add(studentId); else presentSet.remove(studentId);
            updateCount();

            Object change = present ? FieldValue.arrayUnion(studentId)
                    : FieldValue.arrayRemove(studentId);
            attendanceRef.document(selectedDate)
                    .set(Collections.singletonMap("presentIds", change), SetOptions.merge());
        });

        // Long press = delete student
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            new AlertDialog.Builder(this)
                    .setTitle("Delete student?")
                    .setMessage(labels.get(position))
                    .setPositiveButton("Delete", (d, w) ->
                            studentsRef.document(ids.get(position)).delete())
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        findViewById(R.id.btnAddStudent).setOnClickListener(v -> showAddStudentDialog());
        findViewById(R.id.btnStartLock).setOnClickListener(v -> showLockDialog());
        findViewById(R.id.btnStopLock).setOnClickListener(v -> stopLock());

        studentsListener = studentsRef.orderBy("rollNo", Query.Direction.ASCENDING)
                .addSnapshotListener((snap, e) -> {
                    if (snap == null) return;
                    labels.clear();
                    ids.clear();
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        ids.add(d.getId());
                        labels.add(d.getString("rollNo") + " - " + d.getString("name")
                                + "  (" + d.getString("phone") + ")");
                    }
                    adapter.notifyDataSetChanged();
                    applyChecks();
                });

        listenAttendance();
    }

    private void listenAttendance() {
        if (attendanceListener != null) attendanceListener.remove();
        attendanceListener = attendanceRef.document(selectedDate)
                .addSnapshotListener((doc, e) -> {
                    presentSet.clear();
                    if (doc != null && doc.exists()) {
                        Object o = doc.get("presentIds");
                        if (o instanceof List) {
                            for (Object item : (List<?>) o) presentSet.add(String.valueOf(item));
                        }
                    }
                    applyChecks();
                });
    }

    private void applyChecks() {
        for (int i = 0; i < ids.size(); i++) {
            list.setItemChecked(i, presentSet.contains(ids.get(i)));
        }
        updateCount();
    }

    private void updateCount() {
        int present = 0;
        for (String id : ids) if (presentSet.contains(id)) present++;
        tvCount.setText("Present: " + present + " / " + ids.size());
    }

    private void pickDate() {
        Calendar c = Calendar.getInstance();
        new DatePickerDialog(this, (view, y, m, d) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(y, m, d);
            selectedDate = fmt.format(picked.getTime());
            btnDate.setText("Date: " + selectedDate + "  (tap to change)");
            listenAttendance();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showAddStudentDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 0);

        EditText etName = new EditText(this);
        etName.setHint("Student name");
        EditText etRoll = new EditText(this);
        etRoll.setHint("Roll no");
        etRoll.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText etPhone = new EditText(this);
        etPhone.setHint("Phone number (10 digits)");
        etPhone.setInputType(InputType.TYPE_CLASS_PHONE);

        box.addView(etName);
        box.addView(etRoll);
        box.addView(etPhone);

        new AlertDialog.Builder(this)
                .setTitle("Add Student")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    String roll = etRoll.getText().toString().trim();
                    String phone = etPhone.getText().toString().trim();

                    if (name.isEmpty() || roll.isEmpty() || phone.length() != 10) {
                        Toast.makeText(this, "Fill all details correctly", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Map<String, Object> data = new HashMap<>();
                    data.put("name", name);
                    data.put("rollNo", roll);
                    data.put("phone", phone);

                    studentsRef.add(data).addOnFailureListener(e ->
                            Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLockDialog() {
        String today = fmt.format(Calendar.getInstance().getTime());
        if (!selectedDate.equals(today)) {
            Toast.makeText(this, "Select today's date to start a lock", Toast.LENGTH_LONG).show();
            return;
        }
        if (presentSet.isEmpty()) {
            Toast.makeText(this, "Mark at least one student Present", Toast.LENGTH_LONG).show();
            return;
        }

        String[] names = {"Instagram", "YouTube", "WhatsApp", "Snapchat", "Facebook", "Telegram", "Chrome"};
        String[] pkgs = {"com.instagram.android", "com.google.android.youtube", "com.whatsapp",
                "com.snapchat.android", "com.facebook.katana", "org.telegram.messenger",
                "com.android.chrome"};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 0);

        EditText etMin = new EditText(this);
        etMin.setHint("Lock time in minutes (e.g. 60)");
        etMin.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(etMin);

        CheckBox cbAll = new CheckBox(this);
        cbAll.setText("Block ALL apps (calls always allowed)");
        box.addView(cbAll);

        TextView label = new TextView(this);
        label.setText("Or choose apps to block:");
        label.setPadding(0, 24, 0, 0);
        box.addView(label);

        CheckBox[] cbs = new CheckBox[names.length];
        for (int i = 0; i < names.length; i++) {
            cbs[i] = new CheckBox(this);
            cbs[i].setText(names[i]);
            box.addView(cbs[i]);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Start Lock (" + presentSet.size() + " present)")
                .setView(sv)
                .setPositiveButton("Start", (d, w) -> {
                    String m = etMin.getText().toString().trim();
                    if (m.isEmpty() || Long.parseLong(m) <= 0) {
                        Toast.makeText(this, "Enter lock time in minutes", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ArrayList<String> chosen = new ArrayList<>();
                    for (int i = 0; i < cbs.length; i++) {
                        if (cbs[i].isChecked()) chosen.add(pkgs[i]);
                    }
                    if (!cbAll.isChecked() && chosen.isEmpty()) {
                        Toast.makeText(this, "Choose apps or select Block ALL", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startLock(Long.parseLong(m), cbAll.isChecked(), chosen);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startLock(long minutes, boolean blockAll, ArrayList<String> blockedApps) {
        Map<String, Object> data = new HashMap<>();
        data.put("active", true);
        data.put("endTime", System.currentTimeMillis() + minutes * 60000L);
        data.put("blockAll", blockAll);
        data.put("blockedApps", blockedApps);
        data.put("presentIds", new ArrayList<>(presentSet));
        data.put("date", selectedDate);

        sessionRef.set(data)
                .addOnSuccessListener(a -> Toast.makeText(this, "Lock started", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void stopLock() {
        sessionRef.update("active", false)
                .addOnSuccessListener(a -> Toast.makeText(this, "Lock stopped", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "No active lock", Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (studentsListener != null) studentsListener.remove();
        if (attendanceListener != null) attendanceListener.remove();
    }
}