package com.blackeyedghoul.cochat

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues.TAG
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.blackeyedghoul.cochat.adapters.ChatAdapter
import com.blackeyedghoul.cochat.models.Configuration
import com.blackeyedghoul.cochat.models.Message
import com.blackeyedghoul.cochat.models.Room
import com.blackeyedghoul.cochat.models.User
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObject

class Chat : CheckAvailability() {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private var alertDialog: AlertDialog? = null
    private lateinit var recyclerView: RecyclerView
    private lateinit var back: ImageView
    private lateinit var name: TextView
    private lateinit var status: TextView
    private lateinit var messageBox: TextInputEditText
    private lateinit var send: CardView
    private lateinit var receiver: User
    private lateinit var sender: User
    private lateinit var room: Room
    private lateinit var configurations: Configuration
    private lateinit var profilePicture: ImageView
    private lateinit var progressDialogActivity: WelcomeScreen
    private lateinit var chatBackground: ImageView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var messagesArrayList: ArrayList<Message>

    @SuppressLint("ResourceAsColor")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        init()

        receiver = intent.getParcelableExtra("RECEIVER")!!
        sender = intent.getParcelableExtra("SENDER")!!
        val roomId = intent.getStringExtra("ROOM_ID") ?: "_"

        back.setOnClickListener { onBackPressed() }
        messageBox.addTextChangedListener(messageTextWatcher)

        checkNetworkConnection()
        progressDialogActivity.showProgressDialog(this)

        setChatBackground(object : FetchConfigurationCallback {
            override fun onCallback(configs: Configuration) {
                configurations = configs
                chatAdapter = ChatAdapter(messagesArrayList, sender, configs.chatBackground == "01")
                recyclerView.adapter = chatAdapter
            }
        })

        name.text = receiver.username
        setProfilePicture()
        observeReceiverStatus()

        if (roomId == "_") {
            findOrCreateRoom()
        } else {
            room = intent.getParcelableExtra("ROOM")!!
            observeMessages(room.id)
            observeTypingStatus()
        }

        Log.d(TAG, "Members: Sender: ${sender.uid} | Receiver: ${receiver.uid}")
    }

    /**
     * Listen to the full ordered message query and replace the in-memory list on each snapshot.
     *
     * The original prototype appended every ADDED/MODIFIED document returned by Firestore,
     * which meant a modified document could be rendered twice. Treating each snapshot as the
     * source of truth keeps the RecyclerView deterministic and avoids duplicate rows.
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun observeMessages(roomId: String) {
        db.collection("rooms")
            .document(roomId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.d(TAG, "Message listener failed", error)
                    progressDialogActivity.dismissProgressDialog()
                    return@addSnapshotListener
                }

                val messages = snapshot?.documents
                    ?.mapNotNull { it.toObject<Message>() }
                    .orEmpty()

                messagesArrayList.clear()
                messagesArrayList.addAll(messages)

                if (::chatAdapter.isInitialized) {
                    chatAdapter.notifyDataSetChanged()
                }

                if (messagesArrayList.isNotEmpty()) {
                    recyclerView.scrollToPosition(messagesArrayList.lastIndex)
                }

                progressDialogActivity.dismissProgressDialog()
            }
    }

    @SuppressLint("ResourceAsColor")
    private fun setChatBackground(fetchConfigurationCallback: FetchConfigurationCallback) {
        db.collection("settings").document(sender.uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.d(TAG, "Settings listener failed", error)
                    Toast.makeText(applicationContext, error.message, Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val configuration = snapshot.toObject<Configuration>() ?: return@addSnapshotListener

                    when (configuration.chatBackground) {
                        "01" -> chatBackground.setImageDrawable(null)
                        "02" -> chatBackground.setImageResource(R.drawable.chat_background_2)
                        "03" -> chatBackground.setImageResource(R.drawable.chat_background_3)
                        "04" -> chatBackground.setImageResource(R.drawable.chat_background_4)
                        "05" -> chatBackground.setImageResource(R.drawable.chat_background_5)
                        "06" -> chatBackground.setImageResource(R.drawable.chat_background_6)
                        "07" -> chatBackground.setImageResource(R.drawable.chat_background_7)
                        "08" -> chatBackground.setImageResource(R.drawable.chat_background_8)
                        "09" -> chatBackground.setImageResource(R.drawable.chat_background_9)
                    }

                    fetchConfigurationCallback.onCallback(configuration)
                }
            }
    }

    interface FetchConfigurationCallback {
        fun onCallback(configs: Configuration)
    }

    @SuppressLint("SetTextI18n")
    private fun observeReceiverStatus() {
        db.collection("users").document(receiver.uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.d(TAG, "Presence listener failed", error)
                    return@addSnapshotListener
                }

                val user = snapshot?.toObject<User>() ?: return@addSnapshotListener
                receiver.isOnline = user.isOnline
                renderStatus()
            }
    }

    private fun renderStatus(isTyping: Boolean = false) {
        status.text = when {
            isTyping -> "Typing"
            receiver.isOnline -> "Online"
            else -> "Offline"
        }
    }

    private val messageTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit

        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            val message = messageBox.text.toString().trim()

            if (message.isNotEmpty()) {
                send.setBackgroundResource(R.drawable.btn_bg_proceed_enable)
                send.isClickable = true
                send.isFocusable = true

                send.setOnClickListener {
                    if (!::room.isInitialized || room.id.isBlank()) {
                        Toast.makeText(applicationContext, "Chat is still getting ready", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    sendMessage(message)
                    messageBox.text?.clear()
                }
            } else {
                send.setBackgroundResource(R.drawable.btn_bg_proceed_disable)
                send.isClickable = false
                send.isFocusable = false
            }

            if (::room.isInitialized) {
                updateTypingStatus(message.isNotEmpty())
            }
        }

        override fun afterTextChanged(p0: Editable?) = Unit
    }

    private fun isCurrentUserConversationStarter(): Boolean {
        if (!::room.isInitialized) return false

        return if (room.conversationStarterUid.isNotBlank()) {
            room.conversationStarterUid == auth.currentUser?.uid
        } else {
            room.members.firstOrNull() == auth.currentUser?.uid
        }
    }

    private fun updateTypingStatus(isTyping: Boolean) {
        if (!::room.isInitialized || room.id.isBlank()) return

        val field = if (isCurrentUserConversationStarter()) {
            "isConversationStarterTyping"
        } else {
            "isNonConversationStarterTyping"
        }

        db.collection("rooms").document(room.id)
            .update(field, isTyping)
            .addOnFailureListener { error ->
                Log.d(TAG, "Failed to update typing status", error)
            }
    }

    private fun sendMessage(message: String) {
        val roomRef = db.collection("rooms").document(room.id)
        val messageRef = roomRef.collection("messages").document()
        val msg = Message(
            messageRef.id,
            sender.uid,
            receiver.uid,
            Timestamp.now(),
            message
        )

        messageRef.set(msg)
            .addOnSuccessListener {
                val updates = mutableMapOf<String, Any>(
                    "lastMessage" to message,
                    "lastUpdatedTimestamp" to Timestamp.now()
                )

                if (room.conversationStarterUid.isBlank()) {
                    room.conversationStarterUid = sender.uid
                    updates["conversationStarterUid"] = sender.uid
                }

                room.lastMessage = message
                roomRef.update(updates)
                    .addOnFailureListener { error ->
                        Log.d(TAG, "Failed to update room preview", error)
                    }
            }
            .addOnFailureListener { error ->
                Log.d(TAG, "Error creating message", error)
                Toast.makeText(
                    applicationContext,
                    "Message send failed: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }

        // Push delivery is deliberately not sent from the Android client.
        // The old prototype called the legacy FCM HTTP API with a server key that had to be
        // bundled into the APK. A production version should dispatch notifications from a
        // trusted backend or Cloud Function after the Firestore message write.
    }

    /**
     * Find the single direct-message room shared by these two users.
     *
     * The old implementation iterated sender.rooms asynchronously and created a new room for
     * every non-matching result. That could create duplicates before a later matching room was
     * reached. Querying the current user's rooms once lets us decide exactly once whether a room
     * exists.
     */
    private fun findOrCreateRoom() {
        db.collection("rooms")
            .whereArrayContains("members", sender.uid)
            .get()
            .addOnSuccessListener { snapshot ->
                val existingRoom = snapshot.documents
                    .mapNotNull { it.toObject<Room>() }
                    .firstOrNull { candidate -> receiver.uid in candidate.members }

                if (existingRoom != null) {
                    room = existingRoom
                    observeMessages(room.id)
                    observeTypingStatus()
                    Log.d(TAG, "Room exists: true, Id: ${room.id}")
                } else {
                    createRoom()
                }
            }
            .addOnFailureListener { error ->
                Log.d(TAG, "Room lookup failed", error)
                progressDialogActivity.dismissProgressDialog()
                Toast.makeText(applicationContext, error.message, Toast.LENGTH_SHORT).show()
            }
    }

    private fun observeTypingStatus() {
        db.collection("rooms").document(room.id)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.d(TAG, "Typing listener failed", error)
                    return@addSnapshotListener
                }

                val updatedRoom = snapshot?.toObject<Room>() ?: return@addSnapshotListener
                room = updatedRoom

                val otherUserIsTyping = if (isCurrentUserConversationStarter()) {
                    updatedRoom.isNonConversationStarterTyping
                } else {
                    updatedRoom.isConversationStarterTyping
                }

                renderStatus(otherUserIsTyping)
            }
    }

    private fun createRoom() {
        val roomRef = db.collection("rooms").document()
        room = Room(
            id = roomRef.id,
            members = listOf(sender.uid, receiver.uid),
            isConversationStarterTyping = false,
            isNonConversationStarterTyping = false,
            lastUpdatedTimestamp = null,
            lastMessage = "",
            conversationStarterUid = ""
        )

        roomRef.set(room)
            .addOnSuccessListener {
                addRoomToUser(roomRef.id, receiver.uid)
                addRoomToUser(roomRef.id, sender.uid)
                observeMessages(roomRef.id)
                observeTypingStatus()
                Log.d(TAG, "Room created: ${roomRef.id}")
            }
            .addOnFailureListener { error ->
                Log.d(TAG, "Error creating room", error)
                progressDialogActivity.dismissProgressDialog()
                Toast.makeText(applicationContext, error.message, Toast.LENGTH_SHORT).show()
            }
    }

    private fun addRoomToUser(roomId: String, uid: String) {
        db.collection("users").document(uid)
            .update("rooms", FieldValue.arrayUnion(roomId))
            .addOnFailureListener { error ->
                Log.d(TAG, "Failed to attach room to user", error)
            }
    }

    private fun setProfilePicture() {
        val imageResource = when (receiver.profilePicture) {
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

        profilePicture.setImageResource(imageResource)
    }

    private fun init() {
        recyclerView = findViewById(R.id.ch_chat)
        back = findViewById(R.id.ch_back)
        name = findViewById(R.id.ch_username)
        status = findViewById(R.id.ch_status)
        messageBox = findViewById(R.id.ch_message_txt)
        send = findViewById(R.id.ch_send_card)
        progressDialogActivity = WelcomeScreen()
        profilePicture = findViewById(R.id.ch_profile_picture)
        chatBackground = findViewById(R.id.ch_background)
        messagesArrayList = arrayListOf()
    }

    private fun checkNetworkConnection() {
        val networkConnection = InternetConnection(this)
        networkConnection.observe(this) { isConnected ->
            val view = View.inflate(this, R.layout.no_internet_alert, null)
            val builder = AlertDialog.Builder(this, R.style.FullscreenAlertDialog)
            builder.setView(view)

            if (isConnected) {
                Log.d(TAG, "NetworkConnection: true")
                alertDialog?.dismiss()
            } else {
                Log.d(TAG, "NetworkConnection: false")
                alertDialog = builder.create()
                alertDialog?.window?.setBackgroundDrawableResource(android.R.color.white)
                alertDialog?.show()

                val dismiss = alertDialog?.findViewById(R.id.ni_dismiss) as? Button
                dismiss?.setOnClickListener { alertDialog?.dismiss() }
            }
        }
    }
}
