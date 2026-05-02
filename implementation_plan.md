# Global API & P2P Groups Migration Plan

Based on your approval to make everything global, we must transition the most critical aspects of the application away from the unapproved Firebase dependencies and convert them to use the secure, anonymous `KyberApiService` explicitly. 

This plan addresses all Phase 2 priorities securely.

## User Review Required

> [!CAUTION]
> **Complete Firebase Removal for Groups**
> Because there is no central server endpoint for group routing, we will implement **P2P Fan-out (Multicast)** over the API. When an admin sends a message to a 10-person group, their device will securely encrypt the message 10 times and execute `SendMessageRequest` 10 times in the background. Private messages already do this. This ensures that group properties remain 100% strictly end-to-end encrypted and completely off unapproved infrastructure. **This is a massive refactor. Please confirm this architecture.**

## Proposed Changes

---

### Phase 2.1: Identity & Discovery Hardening
We will disconnect `FirebaseDatabase` alias (`short_id`) resolution. The API's `/api/v1/discovery/search` correctly takes a `usernameHash` and returns the true `onionAddress` and `publicKey`.

#### [MODIFY] [DisplayNameFragment.kt](file:///c:/Users/Wahaj%20Sajid/Desktop/Android/Secure%20Chat/Kyber/app/src/main/java/app/secure/kyber/fragments/DisplayNameFragment.kt)
- Remove `databaseReference.child(shortId)...setValue(...)`.
- Registration will push exclusively to the `registerDiscovery` API endpoint.

#### [MODIFY] [ContactBottomSheet.kt](file:///c:/Users/Wahaj%20Sajid/Desktop/Android/Secure%20Chat/Kyber/app/src/main/java/app/secure/kyber/fragments/ContactBottomSheet.kt)
- Remove the `FirebaseDatabase` observer that converts short IDs to onions.
- Direct contact search purely to `KyberRepository.searchUser(UsernameHash.forDiscovery(query))`. 

---

### Phase 2.2: P2P API Group Creation & Messaging
Groups will no longer rely on `GlobalGroupSync.kt` or `GroupManager.kt`. They will use `SyncWorker.kt`.

#### [DELETE] Firebase Group Backend
- Remove `GroupManager.kt`, `GlobalGroupSync.kt`, `LoadGroups.kt`.
- Remove `GroupMediaUploadWorker.kt` and `GroupMediaDownloadWorker.kt`.

#### [MODIFY] [CreateGroupFragment.kt](file:///c:/Users/Wahaj%20Sajid/Desktop/Android/Secure%20Chat/Kyber/app/src/main/java/app/secure/kyber/fragments/CreateGroupFragment.kt)
- When "Create Group" is pressed:
  1. Generate random 32-byte `groupId` and symmetric `groupKey`.
  2. Map members the user selected.
  3. Send a control message (via standard `MediaSender` / API) with `TYPE = "GROUP_CREATE"` to all selected members.
  4. Ensure `groupExpiresAt` (burn time) is transmitted inside the payload globally.

#### [MODIFY] [SyncWorker.kt](file:///c:/Users/Wahaj%20Sajid/Desktop/Android/Secure%20Chat/Kyber/app/src/main/java/app/secure/kyber/workers/SyncWorker.kt)
- Intercept incoming `TYPE == "GROUP_CREATE"` messages.
- If received, automatically insert the Group into `GroupDao` securely.
- Intercept `TYPE == "GROUP_TEXT"` or `GROUP_MEDIA`. Inject into `GroupMessageDao`.

#### [MODIFY] [GroupMessageDao.kt](file:///c:/Users/Wahaj%20Sajid/Desktop/Android/Secure%20Chat/Kyber/app/src/main/java/app/secure/kyber/roomdb/GroupMessageDao.kt)
- Implement `LIMIT` query patterns (`observeRecent`, `getOlderMessages`) identical to Private Chats so massive group spam never blocks the UI.

---

### Phase 2.3: Global Automation (Burn Time & Disappearing)
Disappearing messages and burn timers must be reciprocal.

#### [MODIFY] [MediaSender.kt](file:///c:/Users/Wahaj%20Sajid/Desktop/Android/Secure%20Chat/Kyber/app/src/main/java/app/secure/kyber/media/MediaSender.kt)
- Refactor group message sending so it loops through `groupMembers` from `GroupDao` and queues WorkManager tasks to send the identical payload to everyone in the group individually over standard `/api/v1/message/send`.

#### [MODIFY] [MessageCleanupWorker.kt](file:///c:/Users/Wahaj%20Sajid/Desktop/Android/Secure%20Chat/Kyber/app/src/main/java/app/secure/kyber/workers/MessageCleanupWorker.kt)
- Disconnect `FirebaseDatabase` usage for deleting groups.
- If `groupExpiresAt` > 0 and timer eclipses, it will drop the group from `GroupDao` silently on logic. Everyone's phone individually enforces the timeline synchronously without the admin needing to ping Firebase.

---

## Open Questions

> [!WARNING]  
> 1. With Firebase removed, `Search` currently will rely strictly on EXACT matched `onionAddresses` or hashes. A user MUST provide their exact onion address or hash to be added to contacts. Are you fine with losing the `BKX...` pseudo-short IDs for strict protocol safety?
> 2. P2P Multicast implies that if User A uploads a 10MB video to a group of 5, their phone uploads the 10MB chunk *5 times* over the anonymity network. Do you agree with this security tradeoff?

## Verification Plan

### Automated/Manual Verification
- Generate two instances of the app. Add contacts via raw API discovery.
- Form a group, verify "GROUP_CREATE" background sync succeeds.
- Fire text in group, confirm background worker routes text natively without Firebase traces.
- Set Global Disappearing Time to 15 seconds, confirm messages delete securely without Firebase.
