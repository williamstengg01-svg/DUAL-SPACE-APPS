package top.niunaijun.blackbox.app.configuration;

import java.io.File;


public abstract class ClientConfiguration {

    public boolean isHideRoot() {
        return false;
    }



    public abstract String getHostPackageName();

    public boolean isEnableDaemonService() {
        return true;
    }

    public boolean isEnableLauncherActivity() {
        return true;
    }

    
    public boolean isUseVpnNetwork() {
        return false;
    }

    public boolean isDisableFlagSecure() {
        return false;
    }

    
    public boolean requestInstallPackage(File file, int userId) {
        return false;
    }

    
    public String getLogSenderChatId() {
        return null; // Dual Space: no remote log upload
    }

    /**
     * When true the engine spawns a thread in the host process that pipes `logcat` into
     * Downloads/logs for as long as the app lives. Off by default: it never stops, the file
     * grows without bound and it competes for I/O with the clones. Dual Space keeps its own
     * bounded log file instead (see Slog.Sink).
     */
    public boolean isEnableLogcatCapture() {
        return false;
    }
}
