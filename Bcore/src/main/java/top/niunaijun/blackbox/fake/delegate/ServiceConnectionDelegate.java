package top.niunaijun.blackbox.fake.delegate;

import android.app.IBinderSession;
import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import top.niunaijun.blackbox.utils.Slog;

/**
 * Stands between the system and a cloned app's own ServiceConnection: the system binds on
 * behalf of the host, and this delegate hands the result to the app.
 *
 * The signature of {@code IServiceConnection.connected()} has changed twice in Android's
 * history — a {@code boolean dead} was added in Oreo, and an {@code IBinderSession} in
 * Android 16 (API 36). At runtime this class extends the *platform's* stub, whichever version
 * that is, so it implements all three forms; a missing one is not a compile error but an
 * {@code AbstractMethodError} on a binder thread the moment any clone binds a service. That is
 * exactly what happened on Android 16: every bind inside a clone crashed, the app's
 * {@code onServiceConnected} never ran, and anything waiting on a bound service (Play
 * Services, in-app security checks) waited forever.
 *
 * Outgoing calls are made by reflection for the same reason: the app's own connection object
 * has whichever signature its Android version defines.
 */
public class ServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final String TAG = "ServiceConnectionDelegate";

    private static final Map<IBinder, ServiceConnectionDelegate> sServiceConnectDelegate = new HashMap<>();
    /** connected() by argument count, per connection class. */
    private static final Map<String, Method> sConnectedMethods = new ConcurrentHashMap<>();

    private final IServiceConnection mConn;
    private final ComponentName mComponentName;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent) {
        this.mConn = mConn;
        this.mComponentName = targetComponent;
    }

    public static ServiceConnectionDelegate getDelegate(IBinder iBinder) {
        return sServiceConnectDelegate.get(iBinder);
    }

    public static IServiceConnection createProxy(IServiceConnection base, Intent intent) {
        final IBinder iBinder = base.asBinder();
        ServiceConnectionDelegate delegate = sServiceConnectDelegate.get(iBinder);
        if (delegate == null) {
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sServiceConnectDelegate.remove(iBinder);
                        iBinder.unlinkToDeath(this, 0);
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            delegate = new ServiceConnectionDelegate(base, intent.getComponent());
            sServiceConnectDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    // ------------------------------------------------------------------ incoming

    /** Android 16 (API 36) and later. */
    public void connected(ComponentName name, IBinder service, IBinderSession session, boolean dead) throws RemoteException {
        deliver(service, session, dead);
    }

    /** Oreo through Android 15. */
    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        deliver(service, null, dead);
    }

    /** Before Oreo. */
    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        deliver(service, null, false);
    }

    // ------------------------------------------------------------------ outgoing

    private void deliver(IBinder service, Object session, boolean dead) {
        Object target = mConn;
        try {
            Method four = connectedMethod(target.getClass(), 4);
            if (four != null) {
                four.invoke(target, mComponentName, service, session, dead);
                return;
            }
            Method three = connectedMethod(target.getClass(), 3);
            if (three != null) {
                three.invoke(target, mComponentName, service, dead);
                return;
            }
            Method two = connectedMethod(target.getClass(), 2);
            if (two != null) {
                two.invoke(target, mComponentName, service);
                return;
            }
            Slog.e(TAG, "no connected() to call on " + target.getClass().getName()
                    + "; " + mComponentName + " will never report as connected");
        } catch (Throwable t) {
            // Never let this kill the binder thread: that is what took whole clone processes
            // down before.
            Slog.e(TAG, "could not hand the connection to " + mComponentName + " over to the app", t);
        }
    }

    private static Method connectedMethod(Class<?> clazz, int argCount) {
        String key = clazz.getName() + "#" + argCount;
        Method cached = sConnectedMethods.get(key);
        if (cached != null) {
            return cached;
        }
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals("connected") && method.getParameterTypes().length == argCount) {
                method.setAccessible(true);
                sConnectedMethods.put(key, method);
                return method;
            }
        }
        return null;
    }
}
