<p align="center">
  <img src="assets/intro_banner.png" alt="CoChat" width="760" />
</p>

# CoChat 💬

**a 2022 native Android chat side quest that taught me a lot about realtime state.**

I built CoChat while I was getting deeper into Kotlin + Firebase and wanted to see how much of a small messenger I could build myself: phone-number sign in, contact discovery, realtime chats, typing indicators, presence, profiles, themes, invites and push-notification experiments.

It is very much a snapshot of how I built Android apps in 2022 — Activities, XML, RecyclerViews and Firestore listeners everywhere. I still like it for exactly that reason.

> **Status:** historical / learning project. This is not maintained as a production messenger and should not be treated as one.

## what it actually does

- **phone-number sign in** using Firebase Phone Auth + a 6-digit OTP flow
- **Sri Lanka-focused onboarding** using the `+94` phone prefix
- **device contact matching** so registered people can appear using the name saved in your address book
- **one-to-one conversations** backed by Firestore rooms + message subcollections
- **realtime messages** through Firestore snapshot listeners
- **typing indicators** stored on each room
- **online / offline presence** based on the signed-in Android screens
- **recent conversation search** on the home screen
- **profiles** with editable username, bio and a set of bundled avatars
- **multiple chat backgrounds** stored as a per-user setting
- **invite sharing** for contacts who are not registered
- **network-state UI** for screens that depend on an active connection
- an early **Firebase Cloud Messaging experiment** for chat notifications

The old README called this a group chat app, but the implementation is really a **direct-message prototype**: a room contains two member UIDs and the chat UI is designed around one sender + one receiver.

## screenshots

<p align="center">
  <img src="assets/screenshots.png" alt="CoChat screenshots" width="900" />
</p>

## under the hood

```text
Android / Kotlin / XML
        │
        ├── Firebase Phone Auth
        │      └── OTP sign in
        │
        ├── Cloud Firestore
        │      ├── users
        │      ├── settings
        │      └── rooms
        │             └── messages
        │
        ├── Android Contacts Provider
        │      └── match phone-book contacts to CoChat users
        │
        └── Firebase Cloud Messaging
               └── receiving-side experiment kept in the snapshot
```

The UI is the Android stack I was using at the time: `AppCompatActivity`, `ConstraintLayout`, `RecyclerView`, `ViewPager2`, Material components, Lottie and a custom PIN input.

The app's data is intentionally simple:

```text
users/{uid}
  username
  phoneNumber
  profilePicture
  bio
  joinedDate
  isOnline
  rooms[]

settings/{uid}
  chatBackground
  isPushNotificationEnabled

rooms/{roomId}
  members[2]
  conversationStarterUid
  lastMessage
  lastUpdatedTimestamp
  isConversationStarterTyping
  isNonConversationStarterTyping

rooms/{roomId}/messages/{messageId}
  senderUid
  receiverUid
  timestamp
  message
```

There is a much deeper walkthrough in **[docs/engineering.md](docs/engineering.md)**.

## a few things I cleaned up before leaving it here

I wanted the repository to stay recognizably *the 2022 project*, but I also didn't want obvious old mistakes to be the current public version.

### 1. duplicate chat rooms

The original room lookup loop loaded each room asynchronously and called `createRoom()` as soon as it encountered a room that belonged to somebody else. If the matching room happened to be later in the list, more than one direct-message room could be created.

The current snapshot queries rooms containing the signed-in user, finds the room containing the receiver, and only creates a room **once** when no match exists.

### 2. duplicate message rows

The first version appended every Firestore `ADDED` *and* `MODIFIED` document to the RecyclerView list. A changed message could therefore appear twice.

The current listener treats each ordered Firestore snapshot as the source of truth and replaces the in-memory list instead of blindly appending changes.

### 3. contact discovery

The invite flow originally attached a Firestore users listener **for every phone-book contact**, while other paths repeatedly scanned the whole contacts provider for individual users.

The current version does one contacts read + one Firestore user read for the invite list, normalizes the `+94` / local number formats, then filters locally.

### 4. presence crash

The old base Activity built a Firestore user reference using `currentUser!!` before the Activity had even started. An expired or signed-out session could crash during construction.

Presence updates are now null-safe. The underlying presence model is still deliberately the old Activity-level design; I would solve that differently today.

### 5. the push-notification sender

This was the important security cleanup.

The original prototype posted directly to the legacy FCM HTTP API from the Android client. That design requires an FCM server credential to exist in client code / the built APK, which is not an acceptable place for a server credential.

That sender path — Retrofit API, notification request models and client-side server-key usage — has been removed from the public snapshot. The receiving-side `FirebaseMessagingService` is still here because it is useful context for the experiment.

A real rebuild would send notifications from a trusted backend or Cloud Function after a message is created.

### 6. the repo itself is build-shaped again

At some point I gitignored the Gradle files, manifest and other normal Android project files, which made a fresh clone incomplete.

The project scaffolding has been restored from the app's own history, while project-specific Firebase configuration stays ignored.

## running the historical project

This repo uses the original-era toolchain rather than pretending it is a 2026 Android app:

`Kotlin 1.7.x` · `AGP 7.2.x` · `Gradle 7.3.3` · `compileSdk 32`

To experiment with it:

1. Clone the repo and open it in Android Studio.
2. Create your **own** Firebase project and register Android package `com.blackeyedghoul.cochat`.
3. Enable **Phone Authentication** and **Cloud Firestore**.
4. Download your Firebase Android config and place it at `app/google-services.json`.
5. Configure Firestore rules for your own test project. A production ruleset is intentionally not included here.
6. Build/run with the included Gradle wrapper.

Push **sending** is intentionally not wired from the Android client anymore. If you want to experiment with notifications, put the sender on trusted infrastructure rather than adding a server key back into the app.

## what I would build differently now

If I rebuilt CoChat today, the UI would probably be Compose and the app would have a much clearer data/domain boundary, but the bigger changes would be architectural rather than cosmetic.

I would use a repository layer + ViewModels/StateFlow instead of letting Activities coordinate Firestore callbacks directly; query only the current user's rooms instead of listening broadly and filtering on-device; model presence at the app/session level; normalize phone numbers properly instead of assuming one country format; move contact matching into a single indexed pipeline; use transactions/server-side logic where room creation needs uniqueness; paginate message history instead of keeping the full conversation live; and make notification delivery a backend responsibility.

I would also add proper Firestore security rules, emulator-backed integration tests, message delivery/read state, lifecycle-aware listeners and a much less optimistic definition of “online.” 😅

## why keep this public?

Because it is a useful little timestamp.

There is a lot here that I would not architect the same way now, but this project was where I spent time figuring out realtime listeners, auth flows, phone contacts, chat state, RecyclerViews, presence and all the small edge cases that show up once an app has two users changing data at the same time.

That progression is more interesting to me than rewriting the whole thing until it looks like it was built yesterday.

---

built as a Kotlin side project by [Senith Umesha](https://github.com/SenithUmesha) · 2022
