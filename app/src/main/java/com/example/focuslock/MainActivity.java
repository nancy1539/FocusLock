package com.example.focuslock;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnUser = findViewById(R.id.btnUser);
        Button btnSchool = findViewById(R.id.btnSchool);

        btnUser.setOnClickListener(v ->
                startActivity(new Intent(this, UserLoginActivity.class)));

        btnSchool.setOnClickListener(v ->
                startActivity(new Intent(this, SchoolHomeActivity.class)));
    }
}