package app.orchard.tv.system

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The storage grant the USB art screensaver needs on Android 6-10.
 *
 * [app.orchard.tv.wallpaper.ArtGallery.usb] lists /storage with File, which needs
 * READ_EXTERNAL_STORAGE granted at runtime on those versions. From Android 11 up the same read is
 * covered by MANAGE_EXTERNAL_STORAGE, which tools/install.sh grants over adb; that permission does
 * not exist below 11, so on an Android 9/10 set (Huawei's HarmonyOS 2/3/4 smart screens are exactly
 * that) the screensaver had no way to get the grant at all: the manifest capped the permission at
 * maxSdkVersion 23 until 1.2.1.
 *
 * Asked for when the owner picks "USB art" in the screensaver settings, and at start when that is
 * already the chosen screensaver (an update from a version that could not ask). Never at every
 * start: a TV that declined must not be asked again on each boot.
 */
object UsbArtStorage {
    private const val TAG = "XiaoheiStorage"
    private const val REQUEST = 2201

    /** Whether the screensaver would find nothing on a plugged-in drive for want of a grant. */
    fun needed(context: Context): Boolean =
        Build.VERSION.SDK_INT in 23..29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * Shows the system's dialog when there is a window to show it in. Returns whether it asked: a
     * screensaver or the phone remote's server has no activity under it, and the dialog would be
     * dropped.
     */
    fun request(context: Context): Boolean {
        if (!needed(context)) return false
        var current: Context = context
        while (true) {
            if (current is Activity) {
                val activity = current
                if (activity.isFinishing || activity.isDestroyed) return false
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    REQUEST,
                )
                Log.i(TAG, "asked for READ_EXTERNAL_STORAGE for the USB art screensaver")
                return true
            }
            val base = (current as? ContextWrapper)?.baseContext ?: return false
            if (base === current) return false
            current = base
        }
    }
}
