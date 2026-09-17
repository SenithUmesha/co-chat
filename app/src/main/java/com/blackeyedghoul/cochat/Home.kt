package com.blackeyedghoul.cochat

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues.TAG
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.blackeyedghoul.cochat.adapters.MessagesAdapter
import com.blackeyedghoul.cochat.models.Conversation
import com.blackeyedghoul.cochat.models.Room
import com.blackeyedghoul.cochat.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject
import java.util.Locale

class Home : CheckAvailability() {

    private lateinit var auth: FirebaseAuth
    private lateinit var sender: User
    private lateinit var contacts: ImageView
    private lateinit var profile: ImageView
    private lateinit var search: SearchView
    private lateinit var greeting: TextView
    private lateinit var progressDialogActivity: WelcomeScreen
    private val contactPermissionCode = 1
    private lateinit var contactsActivity: Contacts
    private var alertDialog: AlertDialog? = null
    private val db = FirebaseFirestore.getInstance()
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messagesAdapter: MessagesAdapter
    private lateinit var conversationsArrayList: ArrayList<Conversation>
    private lateinit var backupConversationsArrayList: ArrayList<Conversation>
    private lateinit var searchConversationsArrayList: ArrayList<Conversation>
    private lateinit var noResults: TextView
    private var dataObserversStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        auth = FirebaseAuth.getInstance()
        init()

        progressDialogActivity.showProgressDialog(this)
        messagesAdapter = MessagesAdapter(searchConversationsArrayList, this)
        messagesRecyclerView.adapter = messagesAdapter

        checkNetworkConnection()

        contacts.setOnClickListener {
            if (checkContactsPermission()) {
                openContacts()
            } else {
                requestContactsPermission()
            }
        }

        profile.setOnClickListener {
            startActivity(Intent(this, Profile::class.java))
        }

        search.setOnClickListener { search.isIconified = false }
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true

            @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText.orEmpty().trim().lowercase(Locale.getDefault())

                searchConversationsArrayList.clear()
                if (query.isEmpty()) {
                    searchConversationsArrayList.addAll(backupConversationsArrayList)
                } else {
                    searchConversationsArrayList.addAll(
                        backupConversationsArrayList.filter {
                            it.receiver.username.lowercase(Locale.getDefault()).contains(query)
                        }
                    )
                }

                messagesAdapter.notifyDataSetChanged()
                updateEmptyState(newText.orEmpty())
                return true
            }
        })
    }

    private fun checkNetworkConnection() {
        val networkConnection = InternetConnection(this)
        networkConnection.observe(this) { isConnected ->
            val view = View.inflate(this, R.layout.no_internet_alert, null)
            val builder = AlertDialog.Builder(this, R.style.FullscreenAlertDialog)
            builder.setView(view)

            if (isConnected) {
                alertDialog?.dismiss()

                // Connectivity can flap several times during one Activity lifetime. Starting a new
                // Firestore snapshot listener on every reconnect would duplicate observers and UI
                // updates, so attach the realtime listeners once.
                if (!dataObserversStarted) {
                    dataObserversStarted = true
                    observeInbox()
                }
            } else {
                progressDialogActivity.dismissProgressDialog()
                alertDialog = builder.create()
                alertDialog?.window?.setBackgroundDrawableResource(android.R.color.white)
                alertDialog?.show()

                val dismiss = alertDialog?.findViewById(R.id.ni_dismiss) as? Button
                dismiss?.setOnClickListener { alertDialog?.dismiss() }
            }
        }
    }

    private fun observeInbox() {
        fetchSender(object : FetchSenderCallback {
            @SuppressLint("SetTextI18n")
            override fun onCallback(user: User) {
                sender = user
                greeting.text = "Hello ${getFirstWord(user.username).trim()},"
                setProfilePicture(user.profilePicture)

                fetchReceivers(object : FetchReceiversCallback {
                    override fun onCallback(users: ArrayList<User>) {
                        fetchRooms(object : FetchRoomsCallback {
                            override fun onCallback(rooms: ArrayList<Room>) {
                                rebuildConversationList(users, rooms)
                            }
                        })
                    }
                })
            }
        })
    }

    /**
     * Listen only to rooms that contain the signed-in user.
     *
     * The original app listened to the entire rooms collection and filtered membership locally.
     * This keeps the same Firestore model but avoids reading unrelated room documents.
     */
    private fun fetchRooms(fetchRoomsCallback: FetchRoomsCallback) {
        val uid = auth.currentUser?.uid ?: return

        db.collection("rooms")
            .whereArrayContains("members", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.d(TAG, "Room listener failed", error)
                    return@addSnapshotListener
                }

                val rooms = snapshot?.documents
                    ?.mapNotNull { it.toObject<Room>() }
                    ?.toCollection(ArrayList())
                    ?: arrayListOf()

                fetchRoomsCallback.onCallback(rooms)
            }
    }

    private fun fetchReceivers(fetchReceiversCallback: FetchReceiversCallback) {
        db.collection("users")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.d(TAG, "User listener failed", error)
                    return@addSnapshotListener
                }

                val users = snapshot?.documents
                    ?.mapNotNull { it.toObject<User>() }
                    ?.toCollection(ArrayList())
                    ?: arrayListOf()

                fetchReceiversCallback.onCallback(users)
            }
    }

    private fun fetchSender(fetchSenderCallback: FetchSenderCallback) {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.d(TAG, "Sender listener failed", error)
                    Toast.makeText(applicationContext, error.message, Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                val user = snapshot?.toObject<User>() ?: return@addSnapshotListener
                fetchSenderCallback.onCallback(user)
            }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun rebuildConversationList(users: ArrayList<User>, rooms: ArrayList<Room>) {
        val currentUid = auth.currentUser?.uid ?: return
        val usersById = users.associateBy { it.uid }

        conversationsArrayList.clear()

        rooms.forEach { room ->
            if (room.lastMessage.isBlank()) return@forEach

            val receiverUid = room.members.firstOrNull { it != currentUid } ?: return@forEach
            val receiver = usersById[receiverUid]?.copy() ?: return@forEach

            if (checkContactsPermission()) {
                val localNumber = contactsActivity.convertPhoneNumber(receiver.phoneNumber)
                receiver.username = contactsActivity.getContactName(this, receiver.phoneNumber)
                    ?: contactsActivity.getContactName(this, localNumber)
                    ?: receiver.username
            }

            conversationsArrayList.add(Conversation(room, sender, receiver))
        }

        val sorted = conversationsArrayList
            .distinctBy { it.room.id }
            .sortedByDescending { it.room.lastUpdatedTimestamp }

        backupConversationsArrayList.clear()
        backupConversationsArrayList.addAll(sorted)
        searchConversationsArrayList.clear()
        searchConversationsArrayList.addAll(sorted)

        messagesAdapter.notifyDataSetChanged()
        updateEmptyState("")
        progressDialogActivity.dismissProgressDialog()
    }

    @SuppressLint("SetTextI18n")
    private fun updateEmptyState(query: String) {
        if (messagesAdapter.itemCount == 0) {
            noResults.text = if (query.isNotBlank()) {
                "No results found for '$query'"
            } else {
                "No messages found"
            }
            noResults.visibility = View.VISIBLE
        } else {
            noResults.visibility = View.GONE
        }
    }

    interface FetchRoomsCallback {
        fun onCallback(rooms: ArrayList<Room>)
    }

    interface FetchSenderCallback {
        fun onCallback(user: User)
    }

    interface FetchReceiversCallback {
        fun onCallback(users: ArrayList<User>)
    }

    private fun checkContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestContactsPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.READ_CONTACTS),
            contactPermissionCode
        )
    }

    private fun openContacts() {
        if (!::sender.isInitialized) {
            Toast.makeText(this, "Contacts are still loading", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, Contacts::class.java)
        intent.putExtra("SENDER", sender)
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != contactPermissionCode) return

        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openContacts()
        } else {
            Toast.makeText(applicationContext, "Required permission denied", Toast.LENGTH_SHORT).show()

            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }

    private fun setProfilePicture(profilePicture: String) {
        val resource = when (profilePicture) {
            "01" -> R.drawable.pp_1
            "02" -> R.drawable.pp_2
            "03" -> R.drawable.pp_3
            "04" -> R.drawable.pp_4
            "05" -> R.drawable.pp_5
            "06" -> R.drawable.pp_6
            "07" -> R.drawable.pp_7
            "08" -> R.drawable.pp_8
            "09" -> R.drawable.pp_9
            "10" -> R.drawable.pp_10
            "11" -> R.drawable.pp_11
            "12" -> R.drawable.pp_12
            "13" -> R.drawable.pp_13
            "14" -> R.drawable.pp_14
            "15" -> R.drawable.pp_15
            "16" -> R.drawable.pp_16
            else -> R.drawable.pp_1
        }

        profile.setImageResource(resource)
    }

    private fun getFirstWord(fullName: String): String {
        return fullName.substringBefore(" ")
    }

    private fun init() {
        contacts = findViewById(R.id.h_edit)
        profile = findViewById(R.id.h_profile)
        search = findViewById(R.id.h_search_view)
        greeting = findViewById(R.id.h_greeting)
        progressDialogActivity = WelcomeScreen()
        contactsActivity = Contacts()
        messagesRecyclerView = findViewById(R.id.h_recycler_view)
        conversationsArrayList = arrayListOf()
        searchConversationsArrayList = arrayListOf()
        backupConversationsArrayList = arrayListOf()
        noResults = findViewById(R.id.h_no_results_text)
    }

    override fun onBackPressed() {
        finishAffinity()
    }
}
