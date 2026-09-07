package top.niunaijun.blackbox.utils;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.URL;


public class LogSender {
    private static final String TAG = "LogSender";

    public static String send(String chatId, File logFile, String caption) {
        // Dual Space: remote log upload is permanently disabled. Crash logs stay on the
        // device (see com.dualspace.clone.util.CrashLog) and are shown to the user only.
        Slog.w(TAG, "Remote log upload disabled");
        return "disabled";
    }
}
