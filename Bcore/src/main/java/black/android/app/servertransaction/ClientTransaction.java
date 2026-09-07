package black.android.app.servertransaction;

import android.os.IBinder;

import java.util.List;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BField;

@BClassName("android.app.servertransaction.ClientTransaction")
public interface ClientTransaction {
    /** Android 9 – 15. Deprecated in 15 and removed in Android 16. */
    @BField
    List<Object> mActivityCallbacks();

    /** Android 15+. The only list that exists on Android 16 and newer. */
    @BField
    List<Object> mTransactionItems();

    /** Android 9 – 15. Removed in Android 16; use the token carried by each item instead. */
    @BField
    IBinder mActivityToken();

    @BField
    Object mLifecycleStateRequest();
}
