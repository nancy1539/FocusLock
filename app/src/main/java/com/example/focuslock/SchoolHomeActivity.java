package com.example.focuslock;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class SchoolHomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_school_home);

        Button reg = findViewById(R.id.btnRegisterSchool);
        Button stu = findViewById(R.id.btnStudentLogin);
        Button tea = findViewById(R.id.btnTeacherLogin);

        reg.setOnClickListener(v ->
                startActivity(new Intent(this, SchoolRegisterActivity.class)));
        stu.setOnClickListener(v ->
                startActivity(new Intent(this, StudentLoginActivity.class)));
        tea.setOnClickListener(v ->
                startActivity(new Intent(this, TeacherLoginActivity.class)));
    }
}