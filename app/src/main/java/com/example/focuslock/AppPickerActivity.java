package com.example.focuslock;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lets the signed-in Personal User choose which installed apps a focus session restricts.
 *
 *  - Only launchable, non-essential apps are listed (InstalledApps / AppEligibility).
 *  - The list is a RecyclerView; labels load on a background thread and icons lazily, so
 *    hundreds of apps stay smooth.
 *  - The selection is saved per account (PersonalSessionController.saveSelection) on Save and
 *    whenever the screen leaves the foreground.
 */
public class AppPickerActivity extends AppCompatActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService iconPool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Drawable> icons = new LruCache<>(150);

    private final List<InstalledApps.AppEntry> all = new ArrayList<>();
    private final List<InstalledApps.AppEntry> shown = new ArrayList<>();
    private final Set<String> selected = new TreeSet<>();

    private PersonalSessionController controller;
    private String uid;
    private boolean loaded = false;
    private boolean enumerationFailed = false;
    private boolean dirty = false;

    private RecyclerView rv;
    private TextView tvCount, tvEmpty;
    private View progress;
    private AppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_app_picker);

        View root = findViewById(R.id.pickerRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return insets;
        });

        FirebaseUser user;
        try {
            user = FirebaseAuth.getInstance().getCurrentUser();
        } catch (RuntimeException e) {
            user = null;
        }
        if (user == null) {
            finish(); // UserHomeActivity handles the signed-out redirect
            return;
        }
        uid = user.getUid();
        controller = PersonalSessions.controller(this);

        rv = findViewById(R.id.rvApps);
        tvCount = findViewById(R.id.tvPickerCount);
        tvEmpty = findViewById(R.id.tvPickerEmpty);
        progress = findViewById(R.id.progressPicker);
        adapter = new AppAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        rv.setHasFixedSize(true);

        EditText search = findViewById(R.id.etSearch);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter(s == null ? "" : s.toString());
            }
        });
        findViewById(R.id.btnSelectAll).setOnClickListener(v -> selectAllShown());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            if (!selected.isEmpty()) {
                selected.clear();
                dirty = true;
                adapter.notifyDataSetChanged();
                updateCount();
            }
        });
        findViewById(R.id.btnPickerDone).setOnClickListener(v -> {
            if (persist()) {
                finish();
            }
        });

        load();
    }

    @Override
    protected void onPause() {
        if (loaded && dirty) {
            persist();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        iconPool.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        progress.setVisibility(View.VISIBLE);
        final String forUid = uid;
        final PersonalSessionController c = controller;
        io.execute(() -> {
            List<InstalledApps.AppEntry> apps = InstalledApps.selectableWithLabels(getApplicationContext());
            Set<String> saved = c.loadSelection(forUid);
            Set<String> pkgs = new TreeSet<>();
            for (InstalledApps.AppEntry e : apps) {
                pkgs.add(e.packageName);
            }
            AppEligibility.Reconciled r = AppEligibility.reconcile(saved, pkgs);
            ui.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                all.clear();
                all.addAll(apps);
                selected.clear();
                selected.addAll(r.kept);
                enumerationFailed = apps.isEmpty();
                if (!r.removed.isEmpty()) {
                    dirty = true; // persist the reconciled selection
                    toast(getString(R.string.picker_removed, r.removed.size()));
                }
                loaded = true;
                progress.setVisibility(View.GONE);
                EditText search = findViewById(R.id.etSearch);
                applyFilter(search.getText().toString());
            });
        });
    }

    private boolean persist() {
        if (!loaded) {
            return true;
        }
        if (!controller.saveSelection(uid, selected)) {
            toast(getString(R.string.picker_save_failed));
            return false;
        }
        dirty = false;
        return true;
    }

    // ---------------------------------------------------------------- filter

    private void applyFilter(String query) {
        String q = query.trim().toLowerCase(Locale.getDefault());
        shown.clear();
        for (InstalledApps.AppEntry e : all) {
            if (q.isEmpty() || e.label.toLowerCase(Locale.getDefault()).contains(q)
                    || e.packageName.toLowerCase(Locale.US).contains(q)) {
                shown.add(e);
            }
        }
        adapter.notifyDataSetChanged();
        boolean empty = shown.isEmpty() && loaded;
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        tvEmpty.setText(enumerationFailed ? R.string.picker_none_found : R.string.picker_empty);
        updateCount();
    }

    private void updateCount() {
        tvCount.setText(getString(R.string.picker_count, selected.size(), all.size(),
                PersonalRules.MAX_APPS));
    }

    private void selectAllShown() {
        if (shown.isEmpty()) {
            toast(getString(R.string.picker_nothing_shown));
            return;
        }
        int added = 0;
        boolean limited = false;
        for (InstalledApps.AppEntry e : shown) {
            if (selected.contains(e.packageName)) {
                continue;
            }
            if (selected.size() >= PersonalRules.MAX_APPS) {
                limited = true;
                break;
            }
            selected.add(e.packageName);
            added++;
        }
        dirty = dirty || added > 0;
        adapter.notifyDataSetChanged();
        updateCount();
        toast(limited
                ? getString(R.string.picker_all_limited, added, PersonalRules.MAX_APPS)
                : getString(R.string.picker_all_selected, added));
    }

    private void toggle(InstalledApps.AppEntry e, int position) {
        if (selected.contains(e.packageName)) {
            selected.remove(e.packageName);
        } else if (selected.size() >= PersonalRules.MAX_APPS) {
            toast(getString(R.string.picker_limit, PersonalRules.MAX_APPS));
            return;
        } else {
            selected.add(e.packageName);
        }
        dirty = true;
        adapter.notifyItemChanged(position);
        updateCount();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    // --------------------------------------------------------------- adapter

    private final class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView pkg;
            final CheckBox check;

            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.ivIcon);
                name = v.findViewById(R.id.tvAppName);
                pkg = v.findViewById(R.id.tvAppPackage);
                check = v.findViewById(R.id.cbSelected);
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false));
        }

        @Override
        public void onBindViewHolder(final Holder h, int position) {
            final InstalledApps.AppEntry e = shown.get(position);
            h.name.setText(e.label);
            h.pkg.setText(e.packageName);
            h.check.setChecked(selected.contains(e.packageName));
            h.itemView.setOnClickListener(v -> {
                @SuppressWarnings("deprecation")
                int p = h.getAdapterPosition();
                if (p != RecyclerView.NO_POSITION && p < shown.size()) {
                    toggle(shown.get(p), p);
                }
            });

            h.icon.setTag(e.packageName);
            Drawable cached = icons.get(e.packageName);
            if (cached != null) {
                h.icon.setImageDrawable(cached);
            } else {
                h.icon.setImageDrawable(null);
                final String pkgName = e.packageName;
                try {
                    iconPool.execute(() -> {
                        Drawable d;
                        try {
                            d = getPackageManager().getApplicationIcon(pkgName);
                        } catch (Exception ex) { // NameNotFound / RuntimeException
                            return;
                        }
                        icons.put(pkgName, d);
                        ui.post(() -> {
                            if (pkgName.equals(h.icon.getTag())) {
                                h.icon.setImageDrawable(d);
                            }
                        });
                    });
                } catch (RuntimeException rejected) {
                    // activity is closing
                }
            }
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}
