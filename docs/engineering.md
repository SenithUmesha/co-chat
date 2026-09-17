# CoChat engineering notes

CoChat is a 2022 Kotlin/Android direct-message prototype built around Firebase Phone Auth, Cloud Firestore, the Android contacts provider and an early Firebase Cloud Messaging experiment.

This document explains the project as it actually exists rather than retrofitting a modern architecture onto it. Some correctness/security issues have been cleaned up in the current public snapshot, but the overall design is intentionally still recognizably the original app.

## 1. High-level shape

The app is Activity-heavy and uses XML layouts. There is no ViewModel/repository/domain layer; screens talk directly to Firebase and Android platform APIs.

```text
WelcomeScreen / VerifyOtp
        │
        │ Firebase Phone Auth
        ▼
SignUpUserName → SignUpProfilePicture
        │
        │ create users/{uid} + settings/{uid}
        ▼
      Home
        │
        ├── Contacts ── Android Contacts Provider
        │                 + Firestore users
        │
        ├── Profile
        │     ├── ChangeUsernameSheet
        │     ├── ChangeBioSheet
        │     └── ChangeProfilePictureSheet
        │
        ├── Settings ── chat background / logout
        │
        └── Chat
              ├── rooms/{roomId}
              ├── rooms/{roomId}/messages
              ├── typing flags
              └── presence from users/{uid}
```

Most realtime behavior comes from Firestore snapshot listeners. Models are simple Kotlin data classes and several of them are `Parcelable` so complete user/room objects can be passed between Activities.

## 2. Authentication and onboarding

### Phone-number entry

`WelcomeScreen.kt` is the launcher Activity. The original experience was written for Sri Lankan phone numbers: the UI expects a nine-digit local number and prepends `+94` before calling Firebase Phone Auth.

The screen also runs a small auto-advancing `ViewPager2` using three bundled illustrations. If Firebase Auth already has a current user, the Activity skips onboarding and opens `Home`.

### OTP verification

`VerifyOtp.kt` receives the Firebase verification ID / resend token and uses a six-character `PinView`. Once six digits have been entered, it builds a `PhoneAuthCredential` and signs in with Firebase.

After authentication:

```text
users/{uid} exists? ── yes ──> Home
        │
        no
        ▼
SignUpUserName
        ▼
SignUpProfilePicture
```

### First user document

The signup flow collects a display name and one of the bundled profile avatars, then writes a Firestore `users/{uid}` document. The original shape contains:

```text
username
profilePicture
joinedDate
phoneNumber
bio
uid
isOnline
rooms[]
fcmToken
```

A matching `settings/{uid}` document is also created with a chat-background choice and the historical push-notification preference.

The stored `fcmToken` field is now only useful to a **trusted notification backend**. The Android client no longer sends FCM requests with a server credential, and `FirebaseService.onNewToken()` keeps the stored device token current when Firebase rotates it.

## 3. Conversation model

Although the old README described CoChat as a group chat app, the source model is specifically one-to-one.

`Room.kt` contains:

```kotlin
id: String
members: List<String>
isConversationStarterTyping: Boolean
isNonConversationStarterTyping: Boolean
lastUpdatedTimestamp: Timestamp?
lastMessage: String
conversationStarterUid: String
```

`members` is used as a two-user list. The chat screen receives a sender and receiver, and every `Message` stores one `senderUid` and one `receiverUid`.

### Finding or creating a room

The original implementation walked `sender.rooms` and asynchronously loaded each room. For every room that did *not* contain the requested receiver it could immediately call `createRoom()`. Because the Firestore reads completed independently, a non-match could create a duplicate before a later matching room was discovered.

The current public snapshot instead does:

```text
rooms.whereArrayContains("members", senderUid)
        │
        ▼
find first room whose members also contain receiverUid
        │
        ├── found ──> use it
        │
        └── absent ─> create exactly one room
```

When a new room is created, its ID is added to each user's `rooms` array using `FieldValue.arrayUnion`, which also avoids re-writing a stale locally cached list.

This is still not a hard uniqueness guarantee. Two clients could theoretically race and create two rooms at the same time. A production design would enforce direct-message identity with a deterministic room ID or a trusted transaction/server-side creation path.

## 4. Message flow

Messages live under a room:

```text
rooms/{roomId}/messages/{messageId}
```

`Message.kt` stores:

```text
id
senderUid
receiverUid
timestamp
message
```

Sending a message creates a Firestore document and then updates the room preview fields (`lastMessage`, `lastUpdatedTimestamp`, and the initial `conversationStarterUid`).

### Realtime listener

The original listener processed Firestore `DocumentChange` values and added both `ADDED` and `MODIFIED` messages into the existing ArrayList. That was easy to write, but it could duplicate a row when an existing document was modified.

The current snapshot listens to the messages collection ordered by `timestamp`, maps the complete snapshot to `Message` objects, clears the RecyclerView backing list and replaces it with the snapshot. It is still a simple small-chat approach, but its UI state is deterministic.

### Scaling limitation

Every message in the room remains inside the live query. There is no pagination, local cache abstraction or explicit message state machine.

For a real messenger I would use an initial page of recent messages, load older history on demand, represent delivery/read states separately, and isolate Firestore behind a repository so the screen is consuming a stream of UI state rather than managing listeners itself.

## 5. Typing state

Typing is represented by two booleans on each room:

```text
isConversationStarterTyping
isNonConversationStarterTyping
```

The message text watcher writes one of those fields as the local user types, and a room snapshot listener reads the *other* participant's flag to render `Typing` in the chat header.

This works for two users but couples UI behavior to the concept of “conversation starter.” It also generates Firestore writes while typing.

A modern implementation would normally treat typing as ephemeral presence data with throttling/debouncing and a short expiry, rather than persistent room state that can remain stale if a client disappears unexpectedly.

## 6. Online / offline presence

Signed-in Activities inherit from `CheckAvailability`.

The 2022 design writes:

```text
onResume -> users/{uid}.isOnline = true
onPause  -> users/{uid}.isOnline = false
```

That is intentionally still visible in the project because it shows the early presence model, but it does not mean “the user is actually online.” Navigating between Activities can produce transient offline writes, a crashed app may never send the desired final state, and there is no last-seen timestamp or server heartbeat.

The current cleanup makes the implementation null-safe: it no longer dereferences `FirebaseAuth.currentUser!!` during Activity construction.

A better presence design would live at the application/session level, combine lifecycle state with a server timestamp/heartbeat, and expose a `lastSeen` value instead of pretending presence is perfectly binary.

## 7. Contact discovery

One of the more interesting parts of CoChat is that it tries to make Firebase users feel like phone contacts.

The app requests `READ_CONTACTS`, reads phone numbers from the Android contacts provider, then compares those numbers with `users/{uid}.phoneNumber` in Firestore. When a registered number is found locally, the app substitutes the device contact's display name for the server-side username.

### Country assumption

The authentication flow is `+94` specific and the original normalizer simply removed the first three characters and prepended `0`.

The current snapshot still treats CoChat as a Sri Lanka-focused prototype, but normalizes the formats it actually expects more defensively:

```text
+94xxxxxxxxx -> 0xxxxxxxxx
94xxxxxxxxx  -> 0xxxxxxxxx
0xxxxxxxxx   -> unchanged
```

This is not international phone-number handling. A production app should normalize at account creation using a real phone-number library and store a canonical E.164 representation.

### N+1 cleanup

The original invite screen called a function for every local contact, and each call attached its own Firestore `users` snapshot listener. With a large address book, that creates an unnecessary number of listeners.

The current invite flow does one contacts read and one Firestore users read, builds normalized number sets, and filters locally. Individual contact lookups also use `PhoneLookup` instead of repeatedly walking the entire contacts cursor.

## 8. Home / recent conversations

`Home.kt` combines user documents with the current user's room documents to build `Conversation` rows for a RecyclerView. It keeps a backup list for local inbox search by contact/display name.

The historical implementation listened to the **entire** `rooms` collection and then filtered membership on the device. The current snapshot scopes that listener at the query level:

```kotlin
rooms.whereArrayContains("members", currentUid)
```

That avoids reading unrelated room documents and makes the data access match the direct-message model more closely.

The cleanup also prevents a new set of Firestore listeners from being attached every time connectivity flaps during one `Home` Activity lifetime, de-duplicates inbox rows by room ID, and sorts the inbox by `lastUpdatedTimestamp` descending. `MessagesAdapter` now compares a message timestamp to the actual current date instead of accidentally comparing the timestamp to itself (which previously made every conversation look like it happened “today”).

For larger datasets I would still denormalize the information needed by the inbox row — other participant preview/avatar, unread count, last message and last timestamp — so rendering the conversation list does not require joining multiple realtime streams in an Activity.

## 9. Profile and customization

The profile flow supports:

- editing the username
- editing the bio
- switching among bundled avatar illustrations
- selecting one of multiple bundled chat backgrounds
- logging out

These values are stored directly in Firestore and screens listen for updates.

The profile pictures and chat backgrounds are resource IDs represented as short strings (`"01"`, `"02"`, and so on), which keeps the prototype simple but tightly couples remote data to assets that must exist inside that exact APK.

A modern version could model these as typed theme/avatar identifiers or use uploaded media with a stable URL/asset model.

## 10. Connectivity handling

`InternetConnection.kt` is a `LiveData<Boolean>` wrapper around Android's `ConnectivityManager`. Screens observe it and show a full-screen custom no-internet dialog when connectivity disappears.

The implementation predates newer network APIs and still contains compatibility code for old Android versions. The restored manifest now explicitly includes `ACCESS_NETWORK_STATE`, which the connectivity checks depend on.

For a modern app I would not block the entire interface merely because the device is temporarily offline. Firestore has local persistence capabilities, and a chat UI should usually remain readable while showing a smaller connection/sync state indicator.

## 11. Push notifications: what changed and why

The 2022 version experimented with Firebase Cloud Messaging in two pieces:

1. `FirebaseMessagingService` received a data payload and displayed a local notification.
2. The chat screen used Retrofit to call the legacy `fcm/send` HTTP endpoint directly.

The second part is the problem. A client that calls the legacy FCM server endpoint needs a server credential, and anything bundled in Android source/resources is recoverable from the APK. Ignoring a `Constants.kt` file in Git does not make a secret safe if the value must still be compiled into the application.

The public snapshot therefore removes:

```text
NotificationApi.kt
RetrofitInstance.kt
NotificationData.kt
PushNotification.kt
client-side legacy FCM send calls
```

The receiving service remains, but it is now deliberately **receive-only**. It uses a proper immutable `PendingIntent` for modern Android and updates the signed-in user's `fcmToken` field when Firebase rotates the token. A trusted backend can use that token; the APK has no notification-sender credential.

### What a safe version would do

```text
Android client
   │
   ├── writes message to Firestore
   │
   ▼
trusted backend / Cloud Function
   │
   ├── validates sender + room membership
   ├── looks up recipient token(s)
   └── sends FCM through Admin SDK / HTTP v1
```

No server credential belongs in the app.

### Historical release warning

The repository has a 2022 APK release from the period when the client-side notification experiment existed. If the Firebase project or any legacy FCM credential from that era still exists, rotate/revoke it rather than assuming an old APK is private just because the current source has been cleaned up.

## 12. Repository/build cleanup

A later cleanup accidentally ignored normal Android project files including `build.gradle`, `app/build.gradle`, `settings.gradle`, `AndroidManifest.xml`, Gradle wrapper scripts and ProGuard configuration. That left the source visible but a new clone incomplete.

The current repository restores those files from the project's own history while deliberately continuing to ignore:

```text
.idea/
.gradle/
local.properties
build outputs
app/google-services.json
signing material
```

The tracked `.idea/` directory was also removed from the current tree.

`google-services.json` is project-specific Firebase configuration; anyone running the app should create their own Firebase project rather than inheriting the historical one.

The restored toolchain reflects the project period:

```text
Android Gradle Plugin 7.2.1
Kotlin 1.7.10
Gradle 7.3.3
compileSdk 32
targetSdk 32
minSdk 24
```

The restored manifest now exports only the launcher Activity and leaves internal Activities / the messaging service non-exported. It also declares the network-state permission required by the connectivity observer.

This is intentionally not a dependency-upgrade project. Changing the entire Android/Firebase toolchain would turn the repo into a migration exercise and erase some of its value as a snapshot.

## 13. Firestore model at a glance

```text
users/{uid}
├── username
├── profilePicture
├── phoneNumber
├── joinedDate
├── bio
├── uid
├── isOnline
├── rooms[]
└── fcmToken               # recipient token; sender lives on trusted infrastructure

settings/{uid}
├── chatBackground
└── isPushNotificationEnabled

rooms/{roomId}
├── id
├── members[]              # two UIDs in this prototype
├── isConversationStarterTyping
├── isNonConversationStarterTyping
├── lastUpdatedTimestamp
├── lastMessage
└── conversationStarterUid

rooms/{roomId}/messages/{messageId}
├── id
├── senderUid
├── receiverUid
├── timestamp
└── message
```

No production Firestore security rules are included in this repo. Anyone experimenting with it needs to create rules for their own Firebase project. For a real app, authorization rules are part of the architecture, not an afterthought.

## 14. If I rebuilt it now

The broad feature set would stay familiar, but the internals would look very different:

```text
Compose UI
   │
ViewModels + StateFlow
   │
use cases / repositories
   │
├── AuthRepository
├── ConversationRepository
├── ContactsRepository
├── ProfileRepository
└── PresenceRepository
   │
Firebase / local cache / backend
```

The most important changes would be:

- deterministic direct-message room identity or transactional room creation
- message pagination and explicit delivery/read state
- app-level presence + last-seen timestamps
- E.164 phone-number normalization
- one indexed contact-matching pass
- scoped Firestore queries throughout
- lifecycle-aware listener ownership
- backend-owned notification delivery
- Firestore Emulator tests for room/message authorization
- security rules checked into the repo and tested
- offline-friendly UI instead of blocking the screen on network loss
- Compose + ViewModels/StateFlow for predictable UI state

CoChat is useful because the current source makes those lessons easy to see. It is not presented as a modern production architecture; it is the project that helped surface why those architectural boundaries matter.
