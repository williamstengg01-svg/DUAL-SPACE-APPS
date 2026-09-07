package top.niunaijun.blackbox.utils;

import java.util.Arrays;
import java.util.HashSet;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;

public class MethodParameterUtils {

    public static <T> T getFirstParam(Object[] args, Class<T> tClass) {
        if (args == null) {
            return null;
        }
        int index = ArrayUtils.indexOfFirst(args, tClass);
        if (index != -1) {
            return (T) args[index];
        }
        return null;
    }

    public static String replaceFirstAppPkg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String) {
                String value = (String) args[i];
                if (BlackBoxCore.get().isInstalled(value, BlackBoxCore.getUserId())) {
                    args[i] = BlackBoxCore.getHostPkg();
                    return value;
                }
            }
        }
        return null;
    }

    public static void replaceAllAppPkg(Object[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null)
                continue;
            if (args[i] instanceof String) {
                String value = (String) args[i];
                if (BlackBoxCore.get().isInstalled(value, BlackBoxCore.getUserId())) {
                    args[i] = BlackBoxCore.getHostPkg();
                }
            }
        }
    }

    /** isInstalled() answers, so hot paths (permission checks) do not pay a Binder call per string. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> sSandboxPkgCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** True when [pkg] is the current clone or another package installed in this clone's slot. */
    public static boolean isSandboxPkg(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.indexOf('.') < 0) return false;
        if (pkg.equals(BlackBoxCore.getHostPkg())) return false;
        try {
            String own = BActivityThread.getAppPackageName();
            if (pkg.equals(own)) return true;
        } catch (Throwable ignored) {
        }
        Boolean cached = sSandboxPkgCache.get(pkg);
        if (cached != null) return cached;
        boolean installed;
        try {
            installed = BlackBoxCore.get().isInstalled(pkg, BlackBoxCore.getUserId());
        } catch (Throwable t) {
            return false; // do not cache a failure
        }
        sSandboxPkgCache.put(pkg, installed);
        return installed;
    }

    /** Like [replaceAllAppPkg] but with the cache above; safe to call on every Binder transaction. */
    public static void replaceAllAppPkgCached(Object[] args) {
        if (args == null) return;
        String host = BlackBoxCore.getHostPkg();
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String && isSandboxPkg((String) args[i])) {
                args[i] = host;
            }
        }
    }

    public static void replaceFirstUid(Object[] args) {
        if (args == null)
            return;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer) {
                int uid = (int) args[i];
                if (uid == BlackBoxCore.getBUid()) {
                    args[i] = BlackBoxCore.getHostUid();
                }
            }
        }
    }

    public static void replaceLastUid(Object[] args) {
        int index = ArrayUtils.indexOfLast(args, Integer.class);
        if (index != -1) {
            int uid = (int) args[index];
            if (uid == BlackBoxCore.getBUid()) {
                args[index] = BlackBoxCore.getHostUid();
            }
        }
    }

    public static String replaceLastAppPkg(Object[] args) {
        int index = ArrayUtils.indexOfLast(args, String.class);
        if (index != -1) {
            String pkg = (String) args[index];
            if (BlackBoxCore.get().isInstalled(pkg, BlackBoxCore.getUserId())) {
                args[index] = BlackBoxCore.getHostPkg();
            }
            return pkg;
        }
        return null;
    }

    public static String replaceSequenceAppPkg(Object[] args, int sequence) {
        int index = ArrayUtils.indexOf(args, String.class, sequence);
        if (index != -1) {
            String pkg = (String) args[index];
            if (BlackBoxCore.get().isInstalled(pkg, BlackBoxCore.getUserId())) {
                args[index] = BlackBoxCore.getHostPkg();
            }
            return pkg;
        }
        return null;
    }

    public static int getParamsIndex(Class[] args, Class<?> type) {
        for (int i = 0; i < args.length; i++) {
            Class obj = args[i];
            if (obj.equals(type)) {
                return i;
            }
        }
        return -1;
    }

    public static int getIndex(Object[] args, Class<?> type) {
        return getIndex(args, type, 0);
    }

    public static int getIndex(Object[] args, Class<?> type, int start) {
        for (int i = start; i < args.length; i++) {
            Object obj = args[i];
            if (obj != null && obj.getClass() == type) {
                return i;
            }
            if (type.isInstance(obj)) {
                return i;
            }
        }
        return -1;
    }

    public static Class<?>[] getAllInterface(Class clazz) {
        HashSet<Class<?>> classes = new HashSet<>();
        getAllInterfaces(clazz, classes);
        Class<?>[] result = new Class[classes.size()];
        classes.toArray(result);
        return result;
    }


    public static void getAllInterfaces(Class clazz, HashSet<Class<?>> interfaceCollection) {
        Class<?>[] classes = clazz.getInterfaces();
        if (classes.length != 0) {
            interfaceCollection.addAll(Arrays.asList(classes));
        }
        if (clazz.getSuperclass() != Object.class) {
            getAllInterfaces(clazz.getSuperclass(), interfaceCollection);
        }
    }

    public static int toInt(Object obj){
        if(obj instanceof Long){
            return ((Long) obj).intValue();
        }
        return (int)obj;
    }
}
