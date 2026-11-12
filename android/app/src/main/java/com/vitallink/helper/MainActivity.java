package com.vitallink.helper;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One screen. Not linked: the server and the code from the website (Settings › Connected Devices › Add a device ›
 * Android phone). Linked: whose account it sends to, Allow access (Health Connect permissions), Sync now, Unlink.
 * Network and Health Connect calls run on a background thread.
 */
public final class MainActivity extends ComponentActivity {

    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private Store store;
    private LinearLayout root;
    private TextView result;

    private final ActivityResultLauncher<Set<String>> askPermissions =
            registerForActivityResult(HealthConnectBridge.permissionContract(), granted -> {
                render();
                if (!granted.isEmpty()) {
                    startSync();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        int pad = dp(20);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onDestroy() {
        background.shutdown();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        if (bold) {
            t.setTypeface(t.getTypeface(), Typeface.BOLD);
        }
        t.setPadding(0, dp(6), 0, dp(6));
        root.addView(t);
        return t;
    }

    private TextView text(String s, float size) {
        return text(s, size, false);
    }

    private Button button(String label, Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> onClick.run());
        root.addView(b);
        return b;
    }

    private EditText field(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine();
        root.addView(e);
        return e;
    }

    private void render() {
        root.removeAllViews();
        text("VitalLink Sync", 24f, true);
        text("Sends your Health Connect data to your VitalLink account.", 15f);

        int status = HealthConnectBridge.status(this);
        if (status == HealthConnectBridge.UPDATE_REQUIRED) {
            text("Health Connect needs to be installed or updated first.", 16f);
            button("Get Health Connect", () -> {
                Uri uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding");
                startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"));
            });
            return;
        }
        if (status != HealthConnectBridge.AVAILABLE) {
            text("Health Connect isn't available on this phone (it needs Android 8 or later).", 16f);
            return;
        }
        if (store.isLinked()) {
            renderLinked();
        } else {
            renderLink();
        }
    }

    private void renderLink() {
        text("1. On the VitalLink website, open Settings › Connected Devices › Add a device › Android phone.", 15f);
        text("2. Type the code it shows here.", 15f);
        EditText code = field("Code, e.g. ABCD-EFGH", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText server = field("Server", store.server(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        TextView status = text("", 14f);
        Button[] link = new Button[1];
        link[0] = button("Link this phone", () -> {
            String c = code.getText().toString().trim();
            if (c.isEmpty()) {
                status.setText("Enter the code from the website.");
                return;
            }
            link[0].setEnabled(false);
            status.setText("Linking… (the server can take up to a minute to wake up)");
            String s = server.getText().toString().trim();
            store.server(s.isEmpty() ? BuildConfig.DEFAULT_SERVER : s);
            String serverUrl = store.server();
            background.execute(() -> {
                try {
                    Api.Linked linked = new Api(serverUrl).link(c, SyncWorker.deviceName());
                    runOnUiThread(() -> {
                        store.linked(linked.token, linked.email);
                        SyncWorker.schedule(this);
                        render();
                    });
                } catch (IOException e) {
                    String message = e.getMessage() != null ? e.getMessage() : "Couldn't reach VitalLink.";
                    runOnUiThread(() -> {
                        status.setText(message);
                        link[0].setEnabled(true);
                    });
                }
            });
        });
    }

    private void renderLinked() {
        text("Sending to " + store.email(), 16f, true);
        TextView perms = text("Checking access…", 14f);
        result = text(store.lastResult() != null ? store.lastResult() : "Not synced yet.", 14f);
        button("Allow access", () -> askPermissions.launch(Reader.allPermissions()));
        button("Sync now", this::startSync);
        text("It also syncs about every 3 hours on its own.", 13f);
        button("Unlink this phone", () -> {
            SyncWorker.cancel(this);
            store.unlink();
            render();
        });
        text("Unlinking here stops uploads from this app. To void the link completely, also remove the phone on the website.", 13f);
        background.execute(() -> {
            Set<String> granted = HealthConnectBridge.granted(this);
            Set<String> wanted = Reader.readPermissions();
            int n = 0;
            for (String p : wanted) {
                if (granted.contains(p)) {
                    n++;
                }
            }
            String message;
            if (n == 0) {
                message = "No health data allowed yet. Tap Allow access.";
            } else if (!granted.contains(HealthConnectBridge.BACKGROUND)) {
                message = n + " of " + wanted.size() + " data types allowed. Background reading is off, so syncs happen when you open the app.";
            } else {
                message = n + " of " + wanted.size() + " data types allowed, including background reading.";
            }
            runOnUiThread(() -> perms.setText(message));
        });
    }

    private void startSync() {
        if (result != null) {
            result.setText("Syncing…");
        }
        UUID id = SyncWorker.now(this);
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this, info -> {
            if (info == null) {
                return;
            }
            if (info.getState().isFinished()) {
                if (!store.isLinked()) {
                    render();
                } else if (result != null) {
                    result.setText(store.lastResult() != null ? store.lastResult() : info.getState().name());
                }
            } else if (info.getState() == WorkInfo.State.ENQUEUED && result != null) {
                result.setText("Waiting for a network connection…");
            }
        });
    }
}
