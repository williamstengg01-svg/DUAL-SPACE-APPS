package top.niunaijun.blackbox.proxy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.R;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.proxy.record.ProxyActivityRecord;
import top.niunaijun.blackbox.proxy.record.ProxyPendingRecord;
import top.niunaijun.blackbox.utils.Slog;


public class ProxyActivity extends Activity {
    public static final String TAG = "ProxyActivity";

    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        finish();

        HookManager.get().checkEnv(HCallbackProxy.class);


        ProxyActivityRecord record = ProxyActivityRecord.create(getIntent());
        if (record.mTarget != null) {
            // Normally HCallbackProxy swaps this stub for the real activity before onCreate
            // ever runs. Reaching this point means the hand-over did not happen. Retrying is
            // fine once or twice (the process may just have been restarted), but if it keeps
            // happening we would loop forever: stub starts, finishes, starts the target, stub
            // again. Stop after a few attempts and tell the user instead of bouncing them back
            // to the home screen with no explanation.
            if (!shouldRetryHandOver()) {
                Slog.e(TAG, "Giving up on " + record.mTarget.getComponent() + ": activity hand-over keeps failing");
                try {
                    android.widget.Toast.makeText(this, R.string.launcher_start_loop, android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
                return;
            }
            record.mTarget.setExtrasClassLoader(BlackBoxCore.getApplication().getClassLoader());
            startActivity(record.mTarget);
            return;
        }
    }

    private static final long HAND_OVER_WINDOW_MS = 10_000L;
    private static final int HAND_OVER_MAX_ATTEMPTS = 3;
    private static long sHandOverWindowStart = 0L;
    private static int sHandOverAttempts = 0;

    private static synchronized boolean shouldRetryHandOver() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sHandOverWindowStart > HAND_OVER_WINDOW_MS) {
            sHandOverWindowStart = now;
            sHandOverAttempts = 0;
        }
        sHandOverAttempts++;
        return sHandOverAttempts <= HAND_OVER_MAX_ATTEMPTS;
    }

    public static class P0 extends ProxyActivity {

    }

    public static class P1 extends ProxyActivity {

    }

    public static class P2 extends ProxyActivity {

    }

    public static class P3 extends ProxyActivity {

    }

    public static class P4 extends ProxyActivity {

    }

    public static class P5 extends ProxyActivity {

    }

    public static class P6 extends ProxyActivity {

    }

    public static class P7 extends ProxyActivity {

    }

    public static class P8 extends ProxyActivity {

    }

    public static class P9 extends ProxyActivity {

    }

    public static class P10 extends ProxyActivity {

    }

    public static class P11 extends ProxyActivity {

    }

    public static class P12 extends ProxyActivity {

    }

    public static class P13 extends ProxyActivity {

    }

    public static class P14 extends ProxyActivity {

    }

    public static class P15 extends ProxyActivity {

    }

    public static class P16 extends ProxyActivity {

    }

    public static class P17 extends ProxyActivity {

    }

    public static class P18 extends ProxyActivity {

    }

    public static class P19 extends ProxyActivity {

    }

    public static class P20 extends ProxyActivity {

    }

    public static class P21 extends ProxyActivity {

    }

    public static class P22 extends ProxyActivity {

    }

    public static class P23 extends ProxyActivity {

    }

    public static class P24 extends ProxyActivity {

    }

    public static class P25 extends ProxyActivity {

    }

    public static class P26 extends ProxyActivity {

    }

    public static class P27 extends ProxyActivity {

    }

    public static class P28 extends ProxyActivity {

    }

    public static class P29 extends ProxyActivity {

    }

    public static class P30 extends ProxyActivity {

    }

    public static class P31 extends ProxyActivity {

    }

    public static class P32 extends ProxyActivity {

    }

    public static class P33 extends ProxyActivity {

    }

    public static class P34 extends ProxyActivity {

    }

    public static class P35 extends ProxyActivity {

    }

    public static class P36 extends ProxyActivity {

    }

    public static class P37 extends ProxyActivity {

    }

    public static class P38 extends ProxyActivity {

    }

    public static class P39 extends ProxyActivity {

    }

    public static class P40 extends ProxyActivity {

    }

    public static class P41 extends ProxyActivity {

    }

    public static class P42 extends ProxyActivity {

    }

    public static class P43 extends ProxyActivity {

    }

    public static class P44 extends ProxyActivity {

    }

    public static class P45 extends ProxyActivity {

    }

    public static class P46 extends ProxyActivity {

    }

    public static class P47 extends ProxyActivity {

    }

    public static class P48 extends ProxyActivity {

    }

    public static class P49 extends ProxyActivity {

    }
}
