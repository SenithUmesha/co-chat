package com.blackeyedghoul.cochat

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.PhoneLookup
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.blackeyedghoul.cochat.adapters.ContactsAdapter
import com.blackeyedghoul.cochat.adapters.InviteContactsAdapter
import com.blackeyedghoul.cochat.models.Contact
import com.blackeyedghoul.cochat.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.util.Locale

class Contacts : CheckAvailability() {

    private lateinit var menu: ImageView
    private lateinit var back: ImageView
    private lateinit var recyclerViewContacts: RecyclerView
    private lateinit var recyclerViewInvite: RecyclerView
    private lateinit var usersArrayList: ArrayList<User>
    private lateinit var backupUsersArrayList: ArrayList<User>
    private lateinit var searchUsersArrayList: ArrayList<User>
    private lateinit var contactsAdapter: ContactsAdapter
    private lateinit var inviteContactsAdapter: InviteContactsAdapter
    private lateinit var progressDialogActivity: WelcomeScreen
    private lateinit var search: SearchView
    private lateinit var contactList: ArrayList<Contact>
    private lateinit var noResults: TextView
    private var alertDialog: AlertDialog? = null
    private lateinit var sender: User
    private lateinit var auth: FirebaseAuth
    private lateinit var inviteCard: CardView
    private lateinit var inviteLayout: ConstraintLayout
    private lateinit var expandIcon: ImageView

    private val db = FirebaseFirestore.getInstance()

    @SuppressLint("DiscouragedPrivateApi", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)

        init()
        sender = intent.getParcelableExtra("SENDER")!!

        contactsAdapter = ContactsAdapter(searchUsersArrayList, this, sender)
        inviteContactsAdapter = InviteContactsAdapter(contactList, this)
        recyclerViewContacts.adapter = contactsAdapter
        recyclerViewInvite.adapter = inviteContactsAdapter

        checkNetworkConnection()

        inviteCard.setOnClickListener {
            TransitionManager.beginDelayedTransition(inviteLayout, AutoTransition())

            if (recyclerViewInvite.isShown) {
                expandIcon.setImageResource(R.drawable.forward_gray)
                recyclerViewInvite.visibility = View.GONE
            } else {
                expandIcon.setImageResource(R.drawable.down_gray)
                recyclerViewInvite.visibility = View.VISIBLE

                if (contactList.isEmpty()) {
                    progressDialogActivity.showProgressDialog(this)
                    loadInviteContacts()
                }
            }
        }

        menu.setOnClickListener {
            val popupMenu = PopupMenu(this, it)
            popupMenu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.c_menu_invite -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Hey 👋🏼 wanna try out CoChat? It's a Kotlin chat side project I built. https://github.com/SenithUmesha/co-chat"
                            )
                            type = "text/plain"
                        }
                        startActivity(Intent.createChooser(intent, "Share"))
                        true
                    }
                    else -> false
                }
            }

            popupMenu.inflate(R.menu.contacts_menu)

            try {
                val fieldPopup = PopupMenu::class.java.getDeclaredField("mPopup")
                fieldPopup.isAccessible = true
                val popup = fieldPopup.get(popupMenu)
                popup.javaClass
                    .getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                    .invoke(popup, true)
            } catch (error: Exception) {
                Log.d(TAG, "Could not force popup icons", error)
            } finally {
                popupMenu.show()
            }
        }

        back.setOnClickListener { onBackPressed() }

        search.setOnClickListener { search.isIconified = false }
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true

            @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText.orEmpty().trim().lowercase(Locale.getDefault())

                searchUsersArrayList.clear()
                if (query.isEmpty()) {
                    searchUsersArrayList.addAll(backupUsersArrayList)
                } else {
                    searchUsersArrayList.addAll(
                        backupUsersArrayList.filter {
                            it.username.lowercase(Locale.getDefault()).contains(query)
                        }
                    )
                }

                contactsAdapter.notifyDataSetChanged()

                if (contactsAdapter.itemCount == 0) {
                    noResults.text = if (query.isNotEmpty()) {
                        "No results found for '$newText'"
                    } else {
                        "No contacts found"
                    }
                    noResults.visibility = View.VISIBLE
                } else {
                    noResults.visibility = View.GONE
                }

                return true
            }
        })
    }

    override fun onStart() {
        super.onStart()
        contactList.clear()
        searchUsersArrayList.clear()
        usersArrayList.clear()
        backupUsersArrayList.clear()
    }

    private fun checkNetworkConnection() {
        val networkConnection = InternetConnection(this)
        networkConnection.observe(this) { isConnected ->
            val view = View.inflate(this, R.layout.no_internet_alert, null)
            val builder = AlertDialog.Builder(this, R.style.FullscreenAlertDialog)
            builder.setView(view)

            progressDialogActivity.showProgressDialog(this)

            if (isConnected) {
                alertDialog?.dismiss()
                fetchUsers()
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

    /**
     * Build the invite list with one contacts query and one Firestore read.
     *
     * The first version attached a Firestore users listener for every device contact. On a large
     * address book that created an N+1 listener pattern. This version snapshots the phone book,
     * fetches registered users once, then filters locally.
     */
    @SuppressLint("Range", "NotifyDataSetChanged")
    private fun loadInviteContacts() {
        val deviceContacts = linkedMapOf<String, Contact>()
        val projection = arrayOf(
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)
                val rawNumber = cursor.getString(numberIndex)
                val normalized = normalizePhoneNumber(rawNumber)

                if (normalized.isNotBlank()) {
                    deviceContacts.putIfAbsent(normalized, Contact(name, rawNumber))
                }
            }
        }

        db.collection("users").get()
            .addOnSuccessListener { snapshot ->
                val registeredNumbers = snapshot.documents
                    .mapNotNull { it.toObject(User::class.java)?.phoneNumber }
                    .map(::normalizePhoneNumber)
                    .toSet()

                contactList.clear()
                contactList.addAll(
                    deviceContacts
                        .filterKeys { it !in registeredNumbers }
                        .values
                        .sortedBy { it.name.lowercase(Locale.getDefault()) }
                )

                inviteContactsAdapter.notifyDataSetChanged()
                progressDialogActivity.dismissProgressDialog()
            }
            .addOnFailureListener { error ->
                progressDialogActivity.dismissProgressDialog()
                Toast.makeText(applicationContext, error.message, Toast.LENGTH_SHORT).show()
            }
    }

    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun fetchUsers() {
        auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser ?: return

        db.collection("users")
            .orderBy("username", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.d(TAG, "User listener failed", error)
                    progressDialogActivity.dismissProgressDialog()
                    return@addSnapshotListener
                }

                usersArrayList.clear()

                snapshot?.documents?.forEach { document ->
                    val user = document.toObject(User::class.java) ?: return@forEach
                    if (user.uid == currentUser.uid) return@forEach

                    val localNumber = normalizePhoneNumber(user.phoneNumber)
                    val contactName = getContactName(this, user.phoneNumber)
                        ?: getContactName(this, localNumber)

                    if (contactName != null) {
                        user.username = contactName
                        usersArrayList.add(user)
                    }
                }

                val sortedUsers = usersArrayList
                    .distinctBy { it.uid }
                    .sortedBy { it.username.lowercase(Locale.getDefault()) }

                backupUsersArrayList.clear()
                backupUsersArrayList.addAll(sortedUsers)
                searchUsersArrayList.clear()
                searchUsersArrayList.addAll(sortedUsers)

                contactsAdapter.notifyDataSetChanged()
                noResults.visibility = if (contactsAdapter.itemCount == 0) View.VISIBLE else View.GONE
                if (contactsAdapter.itemCount == 0) noResults.text = "No contacts found"

                progressDialogActivity.dismissProgressDialog()
            }
    }

    @SuppressLint("Range")
    fun getContactName(context: Context, phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) return null

        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        context.contentResolver
            .query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndex(PhoneLookup.DISPLAY_NAME))
                }
            }

        return null
    }

    fun contactExists(context: Context, number: String?): Boolean {
        if (number.isNullOrBlank()) return false

        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver
            .query(uri, arrayOf(PhoneLookup._ID), null, null, null)
            ?.use { cursor ->
                return cursor.moveToFirst()
            }

        return false
    }

    /**
     * CoChat's phone-auth flow is Sri Lanka-focused (+94). Normalize the formats the prototype
     * commonly sees in the phone book so +94xxxxxxxxx and 0xxxxxxxxx compare consistently.
     */
    fun normalizePhoneNumber(phoneNumber: String): String {
        val cleaned = phoneNumber
            .trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")

        return when {
            cleaned.startsWith("+94") && cleaned.length > 3 -> "0${cleaned.substring(3)}"
            cleaned.startsWith("94") && cleaned.length > 2 -> "0${cleaned.substring(2)}"
            else -> cleaned
        }
    }

    // Kept for Home.kt, which used the original helper name.
    fun convertPhoneNumber(phoneNumber: String): String = normalizePhoneNumber(phoneNumber)

    private fun init() {
        menu = findViewById(R.id.c_more)
        search = findViewById(R.id.c_search_view)
        back = findViewById(R.id.c_back)
        recyclerViewContacts = findViewById(R.id.c_contacts_recycler_view)
        recyclerViewInvite = findViewById(R.id.c_invite_recycler_view)
        progressDialogActivity = WelcomeScreen()
        usersArrayList = arrayListOf()
        searchUsersArrayList = arrayListOf()
        contactList = arrayListOf()
        backupUsersArrayList = arrayListOf()
        noResults = findViewById(R.id.c_no_results_text)
        inviteCard = findViewById(R.id.c_invite_card)
        inviteLayout = findViewById(R.id.c_invite_layout)
        expandIcon = findViewById(R.id.c_invite_expand_icon)
    }
}
