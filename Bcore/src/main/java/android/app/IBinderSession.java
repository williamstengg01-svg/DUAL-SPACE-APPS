package android.app;

/**
 * Compile-time stand-in for the hidden framework interface added in Android 16 (API 36),
 * where {@code IServiceConnection.connected()} gained an {@code IBinderSession} parameter.
 *
 * Only the *name* matters: at runtime the real framework interface is loaded from the boot
 * classpath and this copy is ignored. It exists so the engine can declare a method with the
 * exact signature the platform calls (see ServiceConnectionDelegate); the engine never calls
 * anything on it.
 */
public interface IBinderSession extends android.os.IInterface {
}
