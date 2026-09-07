package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import black.android.net.BRIConnectivityManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ScanClass;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

/**
 * Hooks the system ConnectivityManager binder for cloned apps.
 *
 * Why this matters: the network itself works inside a clone (sockets belong to the host
 * UID), but every call to the connectivity service carries the *calling package*, and the
 * service verifies it against the caller's UID. A clone context reports its own package
 * name, which does not belong to Dual Space's UID, so the service answers with a
 * SecurityException. Two things then go wrong in the app:
 *
 *  1. NetworkCallback registration ({@code requestNetwork} / {@code listenForNetwork})
 *     fails, so {@code onAvailable} never fires and the app shows "no connection" even
 *     though its HTTP calls succeed (typical: login works, home screen says offline).
 *  2. The previous hook masked that failure by returning {@code null}; ConnectivityManager
 *     then stores a null request for the callback and the app's later
 *     {@code unregisterNetworkCallback} throws "NetworkCallback was not registered" —
 *     a crash on the main thread.
 *
 * This proxy rewrites the calling package (and any AttributionSource) to the host before
 * every call. If a registration still fails it synthesises a local NetworkRequest so the
 * callback is registered and can be unregistered, and delivers one CALLBACK_AVAILABLE for
 * the phone's active network so the app knows it is online. Query methods fall back to a
 * "connected" answer only when the real call throws, never when it legitimately returns
 * null. Every failure is logged at WARN so it shows up in the Dual Space log file.
 */
@ScanClass(VpnCommonProxy.class)
public class IConnectivityManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IConnectivityManagerProxy";

    private static final AtomicInteger sFakeRequestId = new AtomicInteger(0);

    /**
     * Fallbacks ask the host context's ConnectivityManager, which goes through this very
     * proxy again. If that inner call fails too we must not fall back a second time, or the
     * thread recurses until it overflows.
     */
    private static final ThreadLocal<Boolean> sInFallback = new ThreadLocal<>();

    private static boolean enterFallback() {
        if (Boolean.TRUE.equals(sInFallback.get())) return false;
        sInFallback.set(Boolean.TRUE);
        return true;
    }

    private static void exitFallback() {
        sInFallback.set(Boolean.FALSE);
    }

    public IConnectivityManagerProxy() {
        super(BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIConnectivityManagerStub.get().asInterface(BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    // ------------------------------------------------------------------ common

    private static String clonePackage() {
        try {
            return BActivityThread.getAppPackageName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Calling-package / AttributionSource arguments must name the host, whose UID we run as. */
    static void fixArgs(Object[] args) {
        if (args == null) return;
        String clone = clonePackage();
        String host = BlackBoxCore.getHostPkg();
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof String) {
                if (clone != null && clone.equals(a)) args[i] = host;
            } else if (a != null && "android.content.AttributionSource".equals(a.getClass().getName())) {
                try {
                    ContextCompat.fixAttributionSourceState(a, BlackBoxCore.getHostUid());
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Invoke the real service method and surface the real exception (not the reflection wrapper). */
    static Object invokeReal(Object who, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(who, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    private static Object defaultFor(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == void.class) return null;
        return null;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        fixArgs(args);
        try {
            return super.invoke(proxy, method, args);
        } catch (SecurityException e) {
            // A method we do not hook explicitly still failed on the package/UID check even
            // with the host package in place. Do not take the app down for a connectivity
            // query; answer with the neutral value and leave a trace in the log.
            Slog.w(TAG, "connectivity." + method.getName() + " refused for clone " + clonePackage() + ": " + e.getMessage());
            return defaultFor(method.getReturnType());
        }
    }

    // ------------------------------------------------------------------ fabricated answers

    private static NetworkInfo connectedInfo(int type, String name) {
        try {
            NetworkInfo info = new NetworkInfo(type, 0, name, "");
            info.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, null);
            return info;
        } catch (Throwable t) {
            try {
                Constructor<NetworkInfo> c = NetworkInfo.class.getDeclaredConstructor(int.class, int.class, String.class, String.class);
                c.setAccessible(true);
                NetworkInfo info = c.newInstance(type, 0, name, "");
                Method m = NetworkInfo.class.getDeclaredMethod("setDetailedState", NetworkInfo.DetailedState.class, String.class, String.class);
                m.setAccessible(true);
                m.invoke(info, NetworkInfo.DetailedState.CONNECTED, null, null);
                return info;
            } catch (Throwable t2) {
                Slog.w(TAG, "cannot build NetworkInfo: " + t2);
                return null;
            }
        }
    }

    private static NetworkCapabilities fullCapabilities() {
        try {
            NetworkCapabilities nc = new NetworkCapabilities();
            int[] transports = {NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR};
            int[] caps = {NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                    NetworkCapabilities.NET_CAPABILITY_TRUSTED, NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
                    NetworkCapabilities.NET_CAPABILITY_NOT_VPN, NetworkCapabilities.NET_CAPABILITY_NOT_METERED};
            Method addTransport = NetworkCapabilities.class.getMethod("addTransportType", int.class);
            Method addCap = NetworkCapabilities.class.getMethod("addCapability", int.class);
            for (int t : transports) addTransport.invoke(nc, t);
            for (int c : caps) addCap.invoke(nc, c);
            return nc;
        } catch (Throwable t) {
            Slog.w(TAG, "cannot build NetworkCapabilities: " + t);
            return null;
        }
    }

    /** The host's own view of the network — same hooked binder, but a package the service accepts. */
    private static ConnectivityManager hostConnectivity() {
        try {
            return (ConnectivityManager) BlackBoxCore.getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ callback registration

    /**
     * {@code requestNetwork} (also used by registerDefaultNetworkCallback) and
     * {@code listenForNetwork} (registerNetworkCallback). Returns the real NetworkRequest;
     * on failure a synthetic one plus a one-shot "available" event.
     */
    private abstract static class Registration extends MethodHook {
        abstract boolean isListen();

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            fixArgs(args);
            try {
                Object request = invokeReal(who, method, args);
                if (request != null) return request;
                Slog.w(TAG, method.getName() + " returned null for clone " + clonePackage());
            } catch (Throwable t) {
                Slog.w(TAG, method.getName() + " failed for clone " + clonePackage() + ": " + t);
            }
            Object fake = synthesizeRequest(args, isListen());
            if (fake != null) {
                Slog.w(TAG, method.getName() + ": using synthetic NetworkRequest " + fake + " so the callback stays registered");
                deliverAvailableLater(args, fake);
            }
            return fake;
        }
    }

    @ProxyMethod("requestNetwork")
    public static class RequestNetwork extends Registration {
        @Override
        boolean isListen() { return false; }
    }

    @ProxyMethod("listenForNetwork")
    public static class ListenForNetwork extends Registration {
        @Override
        boolean isListen() { return true; }
    }

    @ProxyMethod("pendingRequestForNetwork")
    public static class PendingRequestForNetwork extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            fixArgs(args);
            try {
                return invokeReal(who, method, args);
            } catch (Throwable t) {
                Slog.w(TAG, "pendingRequestForNetwork failed: " + t);
                return null;
            }
        }
    }

    @ProxyMethod("pendingListenForNetwork")
    public static class PendingListenForNetwork extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            fixArgs(args);
            try {
                return invokeReal(who, method, args);
            } catch (Throwable t) {
                Slog.w(TAG, "pendingListenForNetwork failed: " + t);
                return null;
            }
        }
    }

    @ProxyMethod("registerConnectivityDiagnosticsCallback")
    public static class RegisterDiagnosticsCallback extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            fixArgs(args);
            try {
                return invokeReal(who, method, args);
            } catch (Throwable t) {
                Slog.w(TAG, "registerConnectivityDiagnosticsCallback failed: " + t);
                return null;
            }
        }
    }

    @ProxyMethod("releaseNetworkRequest")
    public static class ReleaseNetworkRequest extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return invokeReal(who, method, args);
            } catch (Throwable t) {
                // Releasing a synthetic (or already gone) request must never hurt the app.
                Slog.w(TAG, "releaseNetworkRequest failed: " + t);
                return null;
            }
        }
    }

    private static Object synthesizeRequest(Object[] args, boolean listen) {
        try {
            NetworkCapabilities nc = MethodParameterUtils.getFirstParam(args, NetworkCapabilities.class);
            if (nc == null) nc = new NetworkCapabilities();
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Enum> typeClass = (Class<Enum>) Class.forName("android.net.NetworkRequest$Type");
            @SuppressWarnings("unchecked")
            Object type = Enum.valueOf(typeClass, listen ? "LISTEN" : "REQUEST");
            Constructor<NetworkRequest> c = NetworkRequest.class.getDeclaredConstructor(NetworkCapabilities.class, int.class, int.class, typeClass);
            c.setAccessible(true);
            // Negative ids never collide with the ids the system hands out.
            int id = -(100_000 + sFakeRequestId.incrementAndGet());
            return c.newInstance(nc, -1 /* TYPE_NONE */, id, type);
        } catch (Throwable t) {
            Slog.w(TAG, "cannot synthesize NetworkRequest: " + t);
            return null;
        }
    }

    private static int callbackAvailableCode() {
        try {
            Field f = ConnectivityManager.class.getDeclaredField("CALLBACK_AVAILABLE");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return 0x00080000 + 2; // Protocol.BASE_CONNECTIVITY_MANAGER + 2, stable since Android 5
        }
    }

    /**
     * Tell the app about the phone's active network through the Messenger it registered,
     * exactly the way the system would. Delayed a little so ConnectivityManager has put the
     * callback into its map first.
     */
    private static void deliverAvailableLater(Object[] args, final Object request) {
        final Messenger messenger = MethodParameterUtils.getFirstParam(args, Messenger.class);
        if (messenger == null) return;
        new Thread(() -> {
            // Everything below asks the hooked service again; never fall back from here.
            enterFallback();
            try {
                Thread.sleep(600);
                ConnectivityManager cm = hostConnectivity();
                if (cm == null) return;
                Network net = cm.getActiveNetwork();
                if (net == null) {
                    Slog.w(TAG, "no active network on the phone; not faking onAvailable");
                    return;
                }
                NetworkCapabilities caps = null;
                LinkProperties lp = null;
                try { caps = cm.getNetworkCapabilities(net); } catch (Throwable ignored) { }
                try { lp = cm.getLinkProperties(net); } catch (Throwable ignored) { }
                if (caps == null) caps = fullCapabilities();
                Bundle b = new Bundle();
                b.putParcelable("NetworkRequest", (NetworkRequest) request);
                b.putParcelable("Network", net);
                if (caps != null) b.putParcelable("NetworkCapabilities", caps);
                if (lp != null) b.putParcelable("LinkProperties", lp);
                Message m = Message.obtain();
                m.what = callbackAvailableCode();
                m.arg1 = 0; // not blocked
                m.setData(b);
                messenger.send(m);
                Slog.i(TAG, "delivered synthetic onAvailable(" + net + ") for " + request);
            } catch (Throwable t) {
                Slog.w(TAG, "synthetic onAvailable failed: " + t);
            } finally {
                exitFallback();
            }
        }, "bb-net-available").start();
    }

    // ------------------------------------------------------------------ queries

    /** Real answer first; a "connected" stand-in only if the service refused the call. */
    private abstract static class Query extends MethodHook {
        abstract Object fallback(Object who, Method method, Object[] args);

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            fixArgs(args);
            try {
                return invokeReal(who, method, args);
            } catch (Throwable t) {
                Slog.w(TAG, method.getName() + " failed for clone " + clonePackage() + ": " + t);
                if (!enterFallback()) return defaultFor(method.getReturnType());
                try {
                    return fallback(who, method, args);
                } catch (Throwable t2) {
                    Slog.w(TAG, method.getName() + " fallback failed too: " + t2);
                    return defaultFor(method.getReturnType());
                } finally {
                    exitFallback();
                }
            }
        }
    }

    @ProxyMethod("getActiveNetworkInfo")
    public static class GetActiveNetworkInfo extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            return connectedInfo(ConnectivityManager.TYPE_WIFI, "WIFI");
        }
    }

    @ProxyMethod("getActiveNetworkInfoForUid")
    public static class GetActiveNetworkInfoForUid extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            return connectedInfo(ConnectivityManager.TYPE_WIFI, "WIFI");
        }
    }

    @ProxyMethod("getNetworkInfoForUid")
    public static class GetNetworkInfoForUid extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            return connectedInfo(ConnectivityManager.TYPE_WIFI, "WIFI");
        }
    }

    @ProxyMethod("getNetworkInfo")
    public static class GetNetworkInfo extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            Integer type = MethodParameterUtils.getFirstParam(args, Integer.class);
            int t = type == null ? ConnectivityManager.TYPE_WIFI : type;
            return connectedInfo(t, t == ConnectivityManager.TYPE_MOBILE ? "MOBILE" : "WIFI");
        }
    }

    @ProxyMethod("getAllNetworkInfo")
    public static class GetAllNetworkInfo extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            NetworkInfo wifi = connectedInfo(ConnectivityManager.TYPE_WIFI, "WIFI");
            NetworkInfo mobile = connectedInfo(ConnectivityManager.TYPE_MOBILE, "MOBILE");
            return new NetworkInfo[]{wifi, mobile};
        }
    }

    @ProxyMethod("getNetworkCapabilities")
    public static class GetNetworkCapabilities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            fixArgs(args);
            try {
                Object result = invokeReal(who, method, args);
                if (result instanceof NetworkCapabilities) {
                    // Some phones never mark the network VALIDATED for a sandboxed UID; apps
                    // treat that as "no internet". The transport is real, so say it is usable.
                    try {
                        Method addCap = NetworkCapabilities.class.getMethod("addCapability", int.class);
                        addCap.invoke(result, NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        addCap.invoke(result, NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    } catch (Throwable ignored) {
                    }
                }
                return result;
            } catch (Throwable t) {
                Slog.w(TAG, "getNetworkCapabilities failed for clone " + clonePackage() + ": " + t);
                // Prefer the phone's real capabilities for that network; fabricate only if
                // even the host context cannot get them.
                if (enterFallback()) {
                    try {
                        ConnectivityManager cm = hostConnectivity();
                        Network net = MethodParameterUtils.getFirstParam(args, Network.class);
                        NetworkCapabilities real = (cm != null && net != null) ? cm.getNetworkCapabilities(net) : null;
                        if (real != null) return real;
                    } catch (Throwable ignored) {
                    } finally {
                        exitFallback();
                    }
                }
                return fullCapabilities();
            }
        }
    }

    @ProxyMethod("getActiveNetwork")
    public static class GetActiveNetwork extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            ConnectivityManager cm = hostConnectivity();
            return cm == null ? null : cm.getActiveNetwork();
        }
    }

    @ProxyMethod("getActiveNetworkForUid")
    public static class GetActiveNetworkForUid extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            ConnectivityManager cm = hostConnectivity();
            return cm == null ? null : cm.getActiveNetwork();
        }
    }

    @ProxyMethod("getAllNetworks")
    public static class GetAllNetworks extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            ConnectivityManager cm = hostConnectivity();
            Network[] all = cm == null ? null : cm.getAllNetworks();
            return all != null ? all : (Network[]) Array.newInstance(Network.class, 0);
        }
    }

    @ProxyMethod("getNetworkForType")
    public static class GetNetworkForType extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            return null;
        }
    }

    @ProxyMethod("getLinkProperties")
    public static class GetLinkProperties extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            ConnectivityManager cm = hostConnectivity();
            Network net = cm == null ? null : cm.getActiveNetwork();
            return (cm == null || net == null) ? null : cm.getLinkProperties(net);
        }
    }

    @ProxyMethod("isActiveNetworkMetered")
    public static class IsActiveNetworkMetered extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            return false;
        }
    }

    @ProxyMethod("getRestrictBackgroundStatusByCaller")
    public static class GetRestrictBackgroundStatusByCaller extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            return ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
        }
    }

    @ProxyMethod("getDefaultProxy")
    public static class GetDefaultProxy extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            return null;
        }
    }

    @ProxyMethod("getProxyForNetwork")
    public static class GetProxyForNetwork extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            return null;
        }
    }

    @ProxyMethod("reportNetworkConnectivity")
    public static class ReportNetworkConnectivity extends Query {
        @Override
        Object fallback(Object who, Method method, Object[] args) {
            return null;
        }
    }
}
