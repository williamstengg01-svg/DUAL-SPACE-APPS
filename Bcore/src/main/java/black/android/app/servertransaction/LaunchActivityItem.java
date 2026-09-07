package black.android.app.servertransaction;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BField;
import top.niunaijun.blackreflection.annotation.BMethod;

@BClassName("android.app.servertransaction.LaunchActivityItem")
public interface LaunchActivityItem {
    @BField
    ActivityInfo mInfo();

    @BField
    Intent mIntent();

    /**
     * Android 15+: the activity token moved from ClientTransaction into
     * ActivityTransactionItem (the superclass of LaunchActivityItem).
     */
    @BField
    IBinder mActivityToken();

    /** Android 15+: public accessor for the same token. */
    @BMethod
    IBinder getActivityToken();
}
