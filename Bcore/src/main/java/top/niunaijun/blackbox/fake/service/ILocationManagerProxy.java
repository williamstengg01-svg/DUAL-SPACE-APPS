package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.location.LocationManager;
import android.os.IInterface;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

import black.android.location.BRILocationListener;
import black.android.location.BRILocationManagerStub;
import black.android.location.provider.BRProviderProperties;
import black.android.location.provider.ProviderProperties;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.location.BLocation;
import top.niunaijun.blackbox.fake.frameworks.BLocationManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;


public class ILocationManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ILocationManagerProxy";

    public ILocationManagerProxy() {
        super(BRServiceManager.get().getService(Context.LOCATION_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRILocationManagerStub.get().asInterface(BRServiceManager.get().getService(Context.LOCATION_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Every package name the system sees must be the host's: the clone's own, and — when
        // the mirrored Play Services asks on behalf of an app — that app's package too.
        // (Location requests from the mirrored Play Services used to be blocked outright here;
        // that left the Fused Location Provider silent, so apps kept asking for location.)
        MethodParameterUtils.replaceAllAppPkgCached(args);
        try {
            return super.invoke(proxy, method, args);
        } catch (SecurityException e) {
            // The host has no location permission (yet). Answer like a phone without a fix
            // instead of crashing the caller; Dual Space asks the user for the permission.
            Log.w(TAG, "location." + method.getName() + " refused: " + e.getMessage());
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            return null;
        }
    }

    @ProxyMethod("registerGnssStatusCallback")
    public static class RegisterGnssStatusCallback extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return true;
        }
    }

    @ProxyMethod("getLastLocation")
    public static class GetLastLocation extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                return BLocationManager.get().getLocation(BActivityThread.getUserId(), BActivityThread.getAppPackageName()).convert2SystemLocation();
            }
            
            
            try {
                return method.invoke(who, args);
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException) {
                    Log.w(TAG, "Location permission denied, returning null for getLastLocation");
                    return null;
                }
                throw e;
            }
        }
    }

    @ProxyMethod("getLastKnownLocation")
    public static class GetLastKnownLocation extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                return BLocationManager.get().getLocation(BActivityThread.getUserId(), BActivityThread.getAppPackageName()).convert2SystemLocation();
            }
            
            
            try {
                return method.invoke(who, args);
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException) {
                    Log.w(TAG, "Location permission denied, returning null for getLastKnownLocation");
                    return null;
                }
                throw e;
            }
        }
    }

    @ProxyMethod("requestLocationUpdates")
    public static class RequestLocationUpdates extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                if (args[1] instanceof IInterface) {
                    IInterface listener = (IInterface) args[1];
                    BLocationManager.get().requestLocationUpdates(listener.asBinder());
                    return 0;
                }
            }
            
            
            try {
                return method.invoke(who, args);
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException) {
                    Log.w(TAG, "Location permission denied for requestLocationUpdates, returning 0");
                    return 0;
                }
                throw e;
            }
        }
    }

    @ProxyMethod("removeUpdates")
    public static class RemoveUpdates extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args[0] instanceof IInterface) {
                IInterface listener = (IInterface) args[0];
                BLocationManager.get().removeUpdates(listener.asBinder());
                return 0;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getProviderProperties")
    public static class GetProviderProperties extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object providerProperties = method.invoke(who, args);
            if (BLocationManager.isFakeLocationEnable()) {
                BRProviderProperties.get(providerProperties)._set_mHasNetworkRequirement(false);
                if (BLocationManager.get().getCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName()) == null) {
                    BRProviderProperties.get(providerProperties)._set_mHasCellRequirement(false);
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("removeGpsStatusListener")
    public static class RemoveGpsStatusListener extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return 0;
        }
    }

    @ProxyMethod("getBestProvider")
    public static class GetBestProvider extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                return LocationManager.GPS_PROVIDER;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getAllProviders")
    public static class GetAllProviders extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return Arrays.asList(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER);
        }
    }

    @ProxyMethod("isProviderEnabledForUser")
    public static class isProviderEnabledForUser extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String provider = (String) args[0];
            return Objects.equals(provider, LocationManager.GPS_PROVIDER);
        }
    }

    @ProxyMethod("setExtraLocationControllerPackageEnabled")
    public static class setExtraLocationControllerPackageEnabled extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }
}
