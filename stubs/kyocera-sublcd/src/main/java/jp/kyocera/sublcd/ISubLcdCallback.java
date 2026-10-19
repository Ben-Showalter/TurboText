package jp.kyocera.sublcd;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.view.KeyEvent;

/**
 * COMPILE-ONLY STUB. Never packaged into TurboText (:app depends on this
 * module with compileOnly); on a Kyocera phone the framework's own class is
 * loaded instead, and on any other phone TurboText never reaches code that
 * uses it.
 *
 * Declares the shape of Kyocera's AIDL callback for its cover-screen service,
 * exactly as MatChat's on-device probe listed it (Kyocera DuraXV Extreme+):
 * six methods, transaction codes 1-6. That is all TurboText needs in order to
 * extend {@link Stub}. Copied from MatChat (stubs/kyocera-sublcd, its
 * docs/adr/0009 and docs/COVER-DISPLAY.md).
 */
public interface ISubLcdCallback extends IInterface {

    boolean onKeyDown(int keyCode, KeyEvent event) throws RemoteException;

    boolean onKeyLongPress(int keyCode, KeyEvent event) throws RemoteException;

    boolean onKeyMultiple(int keyCode, int count, KeyEvent event) throws RemoteException;

    boolean onKeyUp(int keyCode, KeyEvent event) throws RemoteException;

    void onNotificationCancel(String tag, int id) throws RemoteException;

    /** Cover screen on/off: SubLcdManager.STATE_SCREEN_ON (1) / STATE_SCREEN_OFF (0). */
    void onScreenStateChanged(int state) throws RemoteException;

    /** Bodies are placeholders; the real ones come from the phone at runtime. */
    abstract class Stub extends Binder implements ISubLcdCallback {
        public Stub() {
            throw new RuntimeException("Stub!");
        }

        public static ISubLcdCallback asInterface(IBinder obj) {
            throw new RuntimeException("Stub!");
        }

        @Override
        public IBinder asBinder() {
            throw new RuntimeException("Stub!");
        }
    }
}
