package com.blackeyedghoul.cochat

import android.content.ContentValues.TAG
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Small legacy presence helper used by the signed-in screens.
 *
 * CoChat originally created a Firestore document reference with currentUser!! as soon as the
 * Activity instance was constructed. That made a signed-out or expired session capable of
 * crashing before onCreate. Resolve the user lazily instead and simply skip the presence update
 * when there is no authenticated user.
 *
 * Note: this still represents the 2022 Activity-level presence model. A modern rebuild should
 * track foreground/background state at the application/session layer rather than interpreting
 * every Activity pause as "offline".
 */
open class CheckAvailability : AppCompatActivity() {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val users by lazy { FirebaseFirestore.getInstance().collection("users") }

    override fun onPause() {
        super.onPause()
        updateAvailability(false)
    }

    override fun onResume() {
        super.onResume()
        updateAvailability(true)
    }

    private fun updateAvailability(isOnline: Boolean) {
        val uid = auth.currentUser?.uid ?: return

        Log.d(TAG, "CheckAvailability: ${if (isOnline) "online" else "offline"}")
        users.document(uid)
            .update("isOnline", isOnline)
            .addOnFailureListener { error ->
                Log.d(TAG, "Presence update failed", error)
            }
    }
}
