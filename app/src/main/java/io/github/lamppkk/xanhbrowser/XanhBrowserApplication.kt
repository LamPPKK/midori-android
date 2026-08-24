package io.github.lamppkk.xanhbrowser

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** Locks the credential vault when the whole app, not an individual Activity, backgrounds. */
class XanhBrowserApplication : Application(), DefaultLifecycleObserver {
    override fun onCreate() {
        super<Application>.onCreate()
        PrivateProfileManager.deleteStaleProfiles()
        AdBlockCoordinator.get(this).installDefaultServiceWorkerClient()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        SyncCoordinator.get(this).apply {
            lockVault()
            schedulePreSleepSync()
        }
    }
}
