package app.secure.kyber.GroupCreationBackend

import android.content.Context
import android.util.Base64
import android.util.Log
import app.secure.kyber.backend.common.DisappearTime
import app.secure.kyber.adapters.AddedMembers
import app.secure.kyber.dataClasses.Group
import app.secure.kyber.dataClasses.GroupMessage
import app.secure.kyber.roomdb.GroupMessageEntity
import app.secure.kyber.roomdb.GroupsEntity
import app.secure.kyber.roomdb.GroupDao
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.roomViewModel.GroupMessagesViewModel
import app.secure.kyber.roomdb.roomViewModel.GroupsViewModel
import com.google.firebase.database.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

class GroupManager {
    private val database =
        FirebaseDatabase.getInstance("https://kyber-b144e-default-rtdb.asia-southeast1.firebasedatabase.app/")
    private val groupsRef = database.getReference("groups")
    private val groupMessagesRef = database.getReference("group_messages")
    private val userGroupsRef = database.getReference("user_groups")
    private val invalidFirebaseKeyChars = Regex("""[\/.#$\[\]]""")

    // ── Size limits for group media sent via Firebase Base64 ──────────────────
    // Firebase has a ~10 MB per-node limit; Base64 adds ~33% overhead.
    // Raw file limits: images ≤ 4 MB, audio/video ≤ 7 MB.
    private val MAX_IMAGE_BYTES = 4 * 1024 * 1024L   // 4 MB raw
    private val MAX_AV_BYTES    = 7 * 1024 * 1024L   // 7 MB raw


    // ── Base64 helpers (group media only — private media uses MediaUploadWorker) ──

    /**
     * Read [uriString] (file:// or content://) and encode it to a Base64 string.
     * Returns null if the file is missing, unreadable, or exceeds [maxBytes].
     */
    fun encodeFileToBase64(context: Context, uriString: String, maxBytes: Long): String? {
        return try {
            val bytes: ByteArray = when {
                uriString.startsWith("file://") || uriString.startsWith("/") -> {
                    val file = File(uriString.removePrefix("file://"))
                    if (!file.exists() || file.length() > maxBytes) return null
                    file.readBytes()
                }
                else -> {
                    val uri = android.net.Uri.parse(uriString)
                    val stream = context.contentResolver.openInputStream(uri) ?: return null
                    val b = stream.readBytes(); stream.close()
                    if (b.size > maxBytes) return null
                    b
                }
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("GroupManager", "encodeFileToBase64 failed: ${e.message}")
            null
        }
    }

    /**
     * Decode a Base64 [payload] to a local cache file and return its `file://` URI.
     * [ext] is the file extension to use (e.g. "jpg", "mp4", "m4a").
     * Idempotent — reuses cached files by content hash.
     */
    fun decodeBase64ToFile(context: Context, payload: String, ext: String): String? {
        return try {
            val bytes = Base64.decode(payload, Base64.NO_WRAP)
            val cacheDir = File(context.filesDir, "group_media_cache").apply { mkdirs() }
            val hash = payload.hashCode()
            val file = File(cacheDir, "gm_${hash}.$ext")
            if (!file.exists()) file.writeBytes(bytes)
            "file://${file.absolutePath}"
        } catch (e: Exception) {
            Log.e("GroupManager", "decodeBase64ToFile failed: ${e.message}")
            null
        }
    }

    /**
     * Copies the source media bytes (file:// or content://) into the local
     * group_media_cache directory and returns a stable `file://` path.
     * This is used so the Room row stores only a lightweight path — never a
     * multi-megabyte Base64 blob.
     *
     * Returns null if the source cannot be read.
     */
    fun saveMediaToLocalCache(context: Context, sourceUri: String, ext: String): String? {
        return try {
            val bytes: ByteArray = when {
                sourceUri.startsWith("file://") || sourceUri.startsWith("/") -> {
                    val f = File(sourceUri.removePrefix("file://"))
                    if (!f.exists()) return null
                    f.readBytes()
                }
                else -> {
                    val uri = android.net.Uri.parse(sourceUri)
                    val stream = context.contentResolver.openInputStream(uri) ?: return null
                    val b = stream.readBytes(); stream.close(); b
                }
            }
            val cacheDir = File(context.filesDir, "group_media_cache").apply { mkdirs() }
            // Use a timestamp-based name so each send gets a unique file
            val name = "gm_${System.currentTimeMillis()}_${bytes.size}.$ext"
            val dest = File(cacheDir, name)
            dest.writeBytes(bytes)
            "file://${dest.absolutePath}"
        } catch (e: Exception) {
            Log.e("GroupManager", "saveMediaToLocalCache failed: ${e.message}")
            null
        }
    }

    // ── Group creation ──────────────────────────────────────────────────────────

    suspend fun createGroup(
        groupName: String,
        groupImage: String = "",
        members: List<AddedMembers>,
        currentUserId: String,
        currentUserName: String,
        groupViewModel: GroupsViewModel,
        isAnonymous: Boolean = false,
        burnDurationMs: Long? = null
    ): String? {
        return try {
            val groupId = groupsRef.push().key ?: return null
            Log.d("GroupManager", "STEP 1 OK — groupId=$groupId, currentUserId=$currentUserId, members=${members.size}")

            // Firebase RTDB does not allow '.' in node keys — it treats it as a path separator.
            // Use a sanitized key (dot->comma) as the Firebase node name, but keep the original
            // onionAddress as the stored "id" VALUE so the isMember check works correctly.
            val membersMap = members.associate {
                sanitizeFirebaseKey(it.id) to mapOf("id" to it.id, "name" to it.name)
            }.toMutableMap()
            membersMap[sanitizeFirebaseKey(currentUserId)] = mapOf("id" to currentUserId, "name" to currentUserName)
            Log.d("GroupManager", "STEP 2 OK — membersMap keys: ${membersMap.keys}")

            val now = System.currentTimeMillis()
            val groupExpiresAt = burnDurationMs?.takeIf { it > 0L }?.let { now + it } ?: 0L
            // anonymousAliases keys must also be sanitized for Firebase
            val anonymousAliases = if (isAnonymous) {
                membersMap.values
                    .mapNotNull { it["id"] }
                    .filter { it != currentUserId }
                    .sorted()
                    .mapIndexed { index, memberId -> sanitizeFirebaseKey(memberId) to "BK${index + 1}" }
                    .toMap()
            } else {
                emptyMap()
            }

            val group = Group(
                groupId = groupId,
                groupName = groupName,
                groupImage = groupImage,
                createdBy = currentUserId,
                createdAt = now,
                members = membersMap,
                lastMessage = "",
                lastMessageTime = "0",
                lastMessageSenderId = "",
                newMessagesCount = 0,
                anonymous = isAnonymous,
                anonymousAliases = anonymousAliases,
                groupExpiresAt = groupExpiresAt
            )

            val groupLocal = GroupsEntity(
                groupId = groupId,
                groupName = groupName,
                lastMessage = "",
                newMessagesCount = 0,
                profileImageResId = 0,
                timeSpan = System.currentTimeMillis(),
                chatTime = "",
                createdBy = currentUserId,
                createdAt = now,
                noOfMembers = membersMap.size,
                isAnonymous = isAnonymous,
                groupExpiresAt = groupExpiresAt,
                anonymousAliases = org.json.JSONObject(anonymousAliases).toString()
            )

            try {
                groupsRef.child(groupId).setValue(group).await()
                Log.d("GroupManager", "STEP 3 OK — group saved to Firebase")
            } catch (e: Exception) {
                Log.e("GroupManager", "STEP 3 FAILED — groupsRef.setValue threw: ${e.javaClass.simpleName}: ${e.message}", e)
                throw e
            }

            try {
                groupViewModel.saveGroup(groupLocal)
                Log.d("GroupManager", "STEP 4 OK — group saved to local DB")
            } catch (e: Exception) {
                Log.e("GroupManager", "STEP 4 FAILED — saveGroup threw: ${e.javaClass.simpleName}: ${e.message}")
                // Non-fatal: continue
            }

            membersMap.keys.forEachIndexed { idx, userId ->
                val sanitizedUserId = sanitizeFirebaseKey(userId)
                Log.d("GroupManager", "STEP 5.$idx — writing userGroupsRef for key: '$sanitizedUserId'")
                try {
                    userGroupsRef.child(sanitizedUserId).child(groupId).setValue(true).await()
                    Log.d("GroupManager", "STEP 5.$idx OK")
                } catch (e: Exception) {
                    Log.e("GroupManager", "STEP 5.$idx FAILED — userGroupsRef.child('$sanitizedUserId') threw: ${e.javaClass.simpleName}: ${e.message}", e)
                    throw e
                }
            }

            Log.d("GroupManager", "STEP 6 OK — Group created successfully: $groupId")
            groupId
        } catch (e: Exception) {
            Log.e("GroupManager", "createGroup FAILED — ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }


    // ── Fetch and sync group metadata ───────────────────────────────────────

    /**
     * Fetches the full group object from Firebase (including anonymousAliases)
     * and updates the local GroupsEntity with the aliases.
     * For existing anonymous groups with empty aliases, regenerates them.
     * This ensures that the adapter has access to the anonymousAliases for display.
     */
    suspend fun syncGroupMetadataFromFirebase(
        groupId: String,
        groupsViewModel: GroupsViewModel
    ) {
        try {
            val groupSnapshot = groupsRef.child(groupId).get().await()
            val group = groupSnapshot.getValue(Group::class.java) ?: return
            
            Log.d("GroupManager", "Fetched group metadata from Firebase. Anonymous: ${group.anonymous}, Aliases count: ${group.anonymousAliases.size}")
            
            // Fetch the local group entity
            val localGroup = groupsViewModel.getGroupById(groupId) ?: return
            
            // If group is anonymous but has no aliases, regenerate them from members
            var aliasesToUse = group.anonymousAliases
            if (group.anonymous && aliasesToUse.isEmpty()) {
                Log.d("GroupManager", "Regenerating aliases for existing anonymous group: $groupId")
                aliasesToUse = regenerateAnonymousAliases(group)
            }
            
            // Update with anonymousAliases from Firebase (or regenerated)
            val updatedGroup = localGroup.copy(
                isAnonymous = group.anonymous,
                anonymousAliases = org.json.JSONObject(aliasesToUse).toString()
            )
            
            groupsViewModel.saveGroup(updatedGroup)
            
            // If we regenerated aliases, also update Firebase to persist them
            if (aliasesToUse.isNotEmpty() && group.anonymousAliases.isEmpty()) {
                try {
                    groupsRef.child(groupId).child("anonymousAliases").setValue(aliasesToUse).await()
                    Log.d("GroupManager", "Persisted regenerated aliases to Firebase")
                } catch (e: Exception) {
                    Log.w("GroupManager", "Could not persist aliases to Firebase (non-critical): ${e.message}")
                }
            }
            
            Log.d("GroupManager", "Updated local group with anonymousAliases")
        } catch (e: Exception) {
            Log.e("GroupManager", "Error syncing group metadata from Firebase: ${e.message}", e)
        }
    }

    /**
     * Regenerates anonymousAliases for an existing group based on its members.
     * Creates aliases like "BK1", "BK2", etc. for all non-creator members.
     */
    private fun regenerateAnonymousAliases(group: Group): Map<String, String> {
        Log.d("GroupManager", "Regenerating aliases - Group ID: ${group.groupId}, Creator: ${group.createdBy}")
        Log.d("GroupManager", "Members in Firebase: ${group.members.size}")
        
        val members = group.members.values
            .mapNotNull { it["id"] }
            .filter { it != group.createdBy }
            .sorted()
        
        Log.d("GroupManager", "Non-creator members to alias: $members")
        
        val aliases = members
            .mapIndexed { index, memberId -> sanitizeFirebaseKey(memberId) to "BK${index + 1}" }
            .toMap()
        
        Log.d("GroupManager", "Generated aliases: $aliases")
        return aliases
    }

    // ── Send message ────────────────────────────────────────────────────────────

    /**
     * Sends a group message.
     *
     * For TEXT messages: [uri] and [firebasePayload] are both null.
     *
     * For IMAGE / VIDEO / AUDIO messages:
     *  - [uri]            = local `file://` path to the media file.
     *                       This is what gets stored in the Room row so the
     *                       row stays small (no blob → no CursorWindow crash).
     *  - [firebasePayload] = Base64-encoded bytes to put in the Firebase node
     *                       so remote receivers can download the media.
     *                       If null, [uri] is used in the Firebase node as-is
     *                       (fine for TEXT / backward-compat scenarios).
     *
     * Callers:
     *  - Use [GroupManager.saveMediaToLocalCache] to obtain [uri].
     *  - Use [GroupManager.encodeFileToBase64] to obtain [firebasePayload].
     */
    suspend fun sendMessage(
        groupId: String,
        senderId: String,
        senderName: String,
        messageText: String,
        groupMessagesViewModel: GroupMessagesViewModel,
        type: String = "TEXT",
        uri: String? = null,
        ampsJson: String? = null,
        firebasePayload: String? = null,   // No longer used for media, kept for signature compatibility
        replyToText: String? = null,
        context: Context? = null
    ): Boolean {
        return try {
            val messageId = groupMessagesRef.child(groupId).push().key ?: return false

            val isMedia = type != "TEXT"
            val initialUploadState = if (isMedia) "uploading" else "done"
            val initialUploadProgress = if (isMedia) 0 else 100

            val disappearTimerMs = context?.let { app.secure.kyber.backend.common.Prefs.getDisappearingTimerMs(it) } ?: 0L
            val localExpiresAt = if (disappearTimerMs > 0L) System.currentTimeMillis() + disappearTimerMs else 0L

            // Room entity — always use the local file path (uri).
            val roomEntity = GroupMessageEntity(
                messageId = messageId,
                group_id  = groupId,
                senderOnion = senderId,
                senderName  = senderName,
                msg         = messageText,
                time        = System.currentTimeMillis().toString(),
                isSent      = true,
                type        = type,
                uri         = uri,                 
                ampsJson    = ampsJson.orEmpty(),
                uploadState = initialUploadState,
                uploadProgress = initialUploadProgress,
                localFilePath = uri?.removePrefix("file://"),
                expiresAt = localExpiresAt,
                replyToText = replyToText ?: ""
            )

            groupMessagesViewModel.saveMessage(roomEntity)

            if (!isMedia) {
                // Send TEXT message immediately
                val firebasePayload = hashMapOf<String, Any>(
                    "messageId" to roomEntity.messageId,
                    "group_id" to roomEntity.group_id,
                    "senderOnion" to roomEntity.senderOnion,
                    "senderName" to roomEntity.senderName,
                    "msg" to roomEntity.msg,
                    "time" to roomEntity.time,
                    "isSent" to roomEntity.isSent,
                    "type" to roomEntity.type,
                    "ampsJson" to roomEntity.ampsJson,
                    "disappear_ttl" to disappearTimerMs,
                    "replyToText" to roomEntity.replyToText
                )
                groupMessagesRef.child(groupId).child(messageId).setValue(firebasePayload).await()
                
                val lastMsg = "$senderName: $messageText"
                val updates = hashMapOf<String, Any>(
                    "lastMessage"       to lastMsg,
                    "lastMessageTime"   to roomEntity.time,
                    "lastMessageSenderId" to senderId
                )
                groupsRef.child(groupId).updateChildren(updates).await()
            } else if (context != null && uri != null) {
                // Media message: Enqueue Chunked Upload Worker
                val request = app.secure.kyber.workers.GroupMediaUploadWorker.buildRequest(
                    messageId = messageId,
                    groupId = groupId,
                    filePath = uri,
                    mimeType = type,
                    caption = messageText,
                    senderId = senderId,
                    senderName = senderName,
                    disappearTtl = disappearTimerMs,
                    replyToText = roomEntity.replyToText
                )
                androidx.work.WorkManager.getInstance(context).enqueue(request)
            }
            
            // ALWAYS update local preview immediately for all message types
            val localLastMsg = when (type.uppercase()) {
                "IMAGE" -> "$senderName: sent an image"
                "VIDEO" -> "$senderName: sent a video"
                "AUDIO" -> "$senderName: sent a voice message"
                else -> "$senderName: $messageText"
            }
            AppDb.get(context ?: return true).groupsDao().updateLastMessage(
                groupId, localLastMsg, roomEntity.time.toLongOrNull() ?: 0L
            )
            
            true
        } catch (e: Exception) {
            Log.e("GroupManager", "Error sending message: ${e.message}", e)
            false
        }
    }

    // ── Listen for incoming messages ────────────────────────────────────────────

    /**
     * Attaches a Firebase listener that mirrors incoming group messages to Room.
     *
     * Key behaviours:
     *
     * 1. **File-safe persistence** — When a media message arrives from Firebase,
     *    if the `uri` field looks like a Base64 payload (not a `file://` path),
     *    it is decoded to a local cache file and only the resulting `file://`
     *    path is stored in Room. This prevents `SQLiteBlobTooBigException`.
     *
     * 2. **Unread counting** — Only messages whose `time` timestamp is strictly
     *    greater than [listenerStartedAt] (the moment this listener was attached)
     *    AND that are not from the current user trigger an unread increment.
     *    This prevents incrementing for historical messages that are loaded on
     *    every `onDataChange` call.
     *
     * 3. **Anonymous masking** —
     *    - Creator is always shown with their real name to everyone.
     *    - When [isGroupAnonymous] is true, any sender who is neither the creator
     *      nor the local user is stored with senderName = "Anonymous Member".
     *    - The local user's own messages always show their real name.
     */
    fun listenForMessages(
        groupId: String,
        mySenderId: String,
        groupMessageViewModel: GroupMessagesViewModel,
        groupsViewModel: GroupsViewModel,
        isGroupAnonymous: Boolean = false,
        groupCreatorId: String = "",
        context: Context? = null
    ): DatabaseReference {
        val listenerStartedAt = System.currentTimeMillis()
        val messagesListener = groupMessagesRef.child(groupId)

        messagesListener.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                CoroutineScope(Dispatchers.IO).launch {
                    for (messageSnapshot in snapshot.children) {
                        try {
                            val messageId   = messageSnapshot.child("messageId").getValue(String::class.java) ?: ""
                            val group_id    = messageSnapshot.child("group_id").getValue(String::class.java) ?: ""
                            val sender_id   = messageSnapshot.child("senderOnion").getValue(String::class.java) ?: ""
                            val rawName     = messageSnapshot.child("senderName").getValue(String::class.java) ?: ""
                            val messageText = messageSnapshot.child("msg").getValue(String::class.java) ?: ""
                            val timeStamp   = messageSnapshot.child("time").getValue(String::class.java) ?: ""
                            val isSent      = messageSnapshot.child("isSent").getValue(Boolean::class.java) ?: false
                            val rawType     = messageSnapshot.child("type").getValue(String::class.java) ?: "TEXT"
                            val type = if (rawType == "CHUNKED_MEDIA") {
                                messageSnapshot.child("mediaType").getValue(String::class.java) ?: "IMAGE"
                            } else rawType
                            
                            val rawUri      = messageSnapshot.child("uri").getValue(String::class.java)
                            val ampsJson    = messageSnapshot.child("ampsJson").getValue(String::class.java) ?: ""
                            val reaction    = messageSnapshot.child("reaction").getValue(String::class.java) ?: ""
                            val replyToText = messageSnapshot.child("replyToText").getValue(String::class.java) ?: ""

                            // ── Anonymous masking ────────────────────────────────
                            val displayName = resolveAnonymousDisplayName(
                                context = context,
                                groupId = groupId,
                                senderId = sender_id,
                                rawName = rawName,
                                isGroupAnonymous = isGroupAnonymous,
                                groupCreatorId = groupCreatorId,
                                mySenderId = mySenderId
                            )

                            val isMyMessage = sender_id == mySenderId

                            // ── Resolve media URI ────────────────────────────────
                            var resolvedUri: String? = null
                            if (rawType == "CHUNKED_MEDIA") {
                                val thumbnailB64 = messageSnapshot.child("thumbnail").getValue(String::class.java) ?: ""
                                if (thumbnailB64.isNotEmpty()) {
                                    resolvedUri = resolveIncomingUri(context, thumbnailB64, if (type == "VIDEO") "jpg" else "jpg")
                                }
                            } else {
                                resolvedUri = resolveIncomingUri(context, rawUri, type)
                            }

                            val message = GroupMessageEntity(
                                messageId   = messageId,
                                group_id    = group_id,
                                msg         = messageText,
                                senderOnion = sender_id,
                                senderName  = displayName,
                                time        = timeStamp,
                                isSent      = isMyMessage,
                                type        = type,
                                uri         = resolvedUri,
                                ampsJson    = ampsJson,
                                reaction    = reaction,
                                downloadState = if (rawType == "CHUNKED_MEDIA") "pending" else "done",
                                replyToText = replyToText
                            )

                            // DEEP MERGE: Preserve local file/upload data
                            val dao = AppDb.get(context ?: return@launch).groupsMessagesDao()
                            val existing = dao.getMessageById(messageId)
                            if (existing != null) {
                                val hasLocalData = !existing.localFilePath.isNullOrBlank() || 
                                                  existing.downloadState == "done" || 
                                                  existing.uploadState == "done" ||
                                                  existing.downloadState == "downloading" ||
                                                  (existing.isSent && existing.uploadState == "uploading")
                                
                                if (hasLocalData) {
                                    val updated = existing.copy(
                                        msg = messageText,
                                        reaction = reaction,
                                        senderName = displayName,
                                        downloadProgress = existing.downloadProgress,
                                        uploadProgress = existing.uploadProgress,
                                        localFilePath = existing.localFilePath,
                                        uri = existing.uri,
                                        downloadState = existing.downloadState,
                                        uploadState = existing.uploadState
                                    )
                                    dao.insertGroupMessage(updated)
                                } else {
                                    dao.insertGroupMessage(message)
                                }
                            } else {
                                dao.insertGroupMessage(message)
                                
                                // Trigger Download Worker immediately if this beat GlobalGroupSync
                                if (rawType == "CHUNKED_MEDIA" && !isMyMessage) {
                                    val mediaId = messageSnapshot.child("mediaId").getValue(String::class.java) ?: ""
                                    val totalChunks = messageSnapshot.child("totalChunks").getValue(Int::class.java) ?: 0
                                    if (mediaId.isNotEmpty() && totalChunks > 0 && context != null) {
                                        val downloadRequest = app.secure.kyber.workers.GroupMediaDownloadWorker.buildRequest(
                                            messageId = messageId,
                                            groupId = group_id,
                                            mediaId = mediaId,
                                            totalChunks = totalChunks,
                                            mimeType = type
                                        )
                                        androidx.work.WorkManager.getInstance(context).enqueue(downloadRequest)
                                    }
                                }
                            }

                            // Note: Local preview updating loop has been removed to prevent UI flickering.
                            // GlobalGroupSync natively handles groupsDao().updateLastMessage for individual new incoming elements cleanly.

                        } catch (e: Exception) {
                            Log.e("listenForMessages", "Error processing message", e)
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("listenForMessages", "Database error: ${error.message}")
            }
        })

        return messagesListener
    }

    /**
     * Resolves an incoming media URI from Firebase.
     *
     * - If [rawUri] is null or blank → returns null.
     * - If [rawUri] already starts with `file://` or `/` → it's already a
     *   local path (shouldn't happen from Firebase, but safe to pass through).
     * - If [rawUri] starts with `http` → remote URL, pass through.
     * - Otherwise (Base64 payload) → decode to a local cache file and return
     *   the `file://` path.  If no [context] is available, returns null.
     */
    private fun resolveIncomingUri(context: Context?, rawUri: String?, type: String): String? {
        if (rawUri.isNullOrBlank()) return null
        if (rawUri.startsWith("file://") || rawUri.startsWith("/") || rawUri.startsWith("http")) {
            return rawUri
        }
        // Base64 payload — decode to a local file so the Room row stays small
        if (context == null) return null 
        val ext = when (type.uppercase()) {
            "VIDEO" -> "mp4"
            "AUDIO" -> "m4a"
            else    -> "jpg"
        }
        return try {
            decodeBase64ToFile(context, rawUri, ext)
        } catch (e: Exception) {
            Log.e("GroupManager", "resolveIncomingUri decode failed: ${e.message}")
            null
        }
    }

    // ── Global Background Sync Operations ───────────────────────────────────────

    suspend fun processIncomingGlobalMessage(
        context: Context,
        snapshot: DataSnapshot,
        groupId: String,
        mySenderId: String,
        isGroupAnonymous: Boolean,
        groupCreatorId: String,
        listenerStartedAt: Long
    ) {
        val messageId   = snapshot.child("messageId").getValue(String::class.java) ?: return
        val sender_id   = snapshot.child("senderOnion").getValue(String::class.java) ?: ""
        
        // Prevent duplicate processing
        val db = AppDb.get(context)
        val dao = db.groupsMessagesDao()
        if (dao.getMessageById(messageId) != null) return

        val rawName     = snapshot.child("senderName").getValue(String::class.java) ?: ""
        val messageText = snapshot.child("msg").getValue(String::class.java) ?: ""
        val timeStamp   = snapshot.child("time").getValue(String::class.java) ?: ""
        val type        = snapshot.child("type").getValue(String::class.java) ?: "TEXT"
        val rawUri      = snapshot.child("uri").getValue(String::class.java)
        val ampsJson    = snapshot.child("ampsJson").getValue(String::class.java) ?: ""
        val reaction    = snapshot.child("reaction").getValue(String::class.java) ?: ""
        val replyToText = snapshot.child("replyToText").getValue(String::class.java) ?: ""
        val disappearTtl = snapshot.child("disappear_ttl").getValue(Long::class.java) ?: 0L
        val msgTimeRaw = timeStamp.toLongOrNull() ?: 0L
        val sentBaseMs = if (msgTimeRaw > 0L) msgTimeRaw else System.currentTimeMillis()
        val localExpiresAt = DisappearTime.expiresAtFromSent(sentBaseMs, disappearTtl)

        val displayName = resolveAnonymousDisplayName(
            context = context,
            groupId = groupId,
            senderId = sender_id,
            rawName = rawName,
            isGroupAnonymous = isGroupAnonymous,
            groupCreatorId = groupCreatorId,
            mySenderId = mySenderId
        )

        val isMyMessage = sender_id == mySenderId
        val msgTime = msgTimeRaw

        if (type == "CHUNKED_MEDIA") {
            val mediaId = snapshot.child("mediaId").getValue(String::class.java) ?: ""
            val totalChunks = snapshot.child("totalChunks").getValue(Int::class.java) ?: 0
            val mediaType = snapshot.child("mediaType").getValue(String::class.java) ?: "IMAGE"
            val thumbnailB64 = snapshot.child("thumbnail").getValue(String::class.java) ?: ""

            val placeholderPath = if (thumbnailB64.isNotEmpty()) {
                decodeBase64ToFile(context, thumbnailB64, if (mediaType == "VIDEO") "jpg" else "jpg")
            } else null

            val message = GroupMessageEntity(
                messageId   = messageId,
                group_id    = groupId,
                msg         = messageText,
                senderOnion = sender_id,
                senderName  = displayName,
                time        = timeStamp,
                isSent      = isMyMessage,
                type        = mediaType,
                uri         = placeholderPath, // Show thumbnail while downloading
                ampsJson    = ampsJson,
                reaction    = reaction,
                downloadState = "pending",
                downloadProgress = 0,
                remoteMediaId = mediaId,
                expiresAt = localExpiresAt,
                replyToText = replyToText
            )
            
            // PRESERVE SENDER/LOCAL DATA:
            val existing = dao.getMessageById(messageId)
            if (existing != null) {
                // If it already has a local file or is already working, don't revert to placeholder
                val hasLocalData = !existing.localFilePath.isNullOrBlank() || 
                                  existing.downloadState == "done" || 
                                  existing.uploadState == "done" ||
                                  existing.downloadState == "downloading" ||
                                  (existing.isSent && existing.uploadState == "uploading")
                
                if (hasLocalData) {
                    // Update only metadata/reactions, PRESERVE everything else
                    val updated = existing.copy(
                        msg = messageText,
                        reaction = reaction,
                        senderName = displayName,
                        downloadProgress = existing.downloadProgress // Keep current progress
                    )
                    dao.insertGroupMessage(updated)
                } else {
                    dao.insertGroupMessage(message)
                }
            } else {
                dao.insertGroupMessage(message)
            }

            // Trigger Download Worker
            if (!isMyMessage) {
                val downloadRequest = app.secure.kyber.workers.GroupMediaDownloadWorker.buildRequest(
                    messageId = messageId,
                    groupId = groupId,
                    mediaId = mediaId,
                    totalChunks = totalChunks,
                    mimeType = mediaType
                )
                androidx.work.WorkManager.getInstance(context).enqueue(downloadRequest)
            }
        } else {
            // Legacy/Text message
            val resolvedUri = resolveIncomingUri(context, rawUri, type)
            val message = GroupMessageEntity(
                messageId   = messageId,
                group_id    = groupId,
                msg         = messageText,
                senderOnion = sender_id,
                senderName  = displayName,
                time        = timeStamp,
                isSent      = isMyMessage,
                type        = type,
                uri         = resolvedUri,
                ampsJson    = ampsJson,
                reaction    = reaction,
                expiresAt   = localExpiresAt,
                replyToText = replyToText
            )
            dao.insertGroupMessage(message)
        }

        // Sync metadata (Last Message Preview)
        val lastMsg = when (type.uppercase()) {
            "IMAGE" -> "$displayName: sent an image"
            "VIDEO" -> "$displayName: sent a video"
            "AUDIO" -> "$displayName: sent a voice message"
            "CHUNKED_MEDIA" -> "$displayName: sent a media file"
            else -> "$displayName: $messageText"
        }
        db.groupsDao().updateLastMessage(groupId, lastMsg, msgTime)

        // Only trigger unread increments and notifications for truly new messages
        if (!isMyMessage && msgTime > listenerStartedAt) {
            val myApp = context.applicationContext as? app.secure.kyber.MyApp.MyApp
            if (myApp?.activeGroupId != groupId) {
                db.groupsDao().incrementUnread(groupId)

                // Show notification with secure generic format
                val displayType = snapshot.child("mediaType").getValue(String::class.java) ?: type
                showGroupNotification(context, groupId, displayType)
            }
        }
    }

    suspend fun processIncomingGlobalReaction(
        context: Context,
        snapshot: DataSnapshot,
        groupId: String,
        mySenderId: String,
        isGroupAnonymous: Boolean,
        groupCreatorId: String
    ) {
        val messageId = snapshot.child("messageId").getValue(String::class.java) ?: return
        val newReaction = snapshot.child("reaction").getValue(String::class.java) ?: ""
        
        val db = AppDb.get(context)
        val dao = db.groupsMessagesDao()
        val existing = dao.getMessageById(messageId) ?: return
        
        if (existing.reaction != newReaction) {
            dao.updateReaction(messageId, newReaction)
            
            if (newReaction.isNotEmpty()) {
                val reactorId = snapshot.child("senderOnion").getValue(String::class.java) ?: ""
                val rawName = snapshot.child("senderName").getValue(String::class.java) ?: "Someone"
                
                val rName = resolveAnonymousDisplayName(
                    context = context,
                    groupId = groupId,
                    senderId = reactorId,
                    rawName = rawName,
                    isGroupAnonymous = isGroupAnonymous,
                    groupCreatorId = groupCreatorId,
                    mySenderId = mySenderId
                )

                // Push Reaction Preview to Group List (Matches private chat)
                val msgTypeStr = when (existing.type.uppercase()) {
                    "IMAGE" -> "a photo"
                    "VIDEO" -> "a video"
                    "AUDIO" -> "a voice message"
                    else -> "a message"
                }

                val formattedMsg = if (reactorId == mySenderId) {
                    "You reacted $newReaction to $msgTypeStr"
                } else if (existing.senderOnion == mySenderId) {
                    "$rName reacted $newReaction to your $msgTypeStr"
                } else {
                    "$rName reacted $newReaction to $msgTypeStr"
                }
                
                // Pushes the group to the top of the chat list with the new reaction preview
                db.groupsDao().updateLastMessage(groupId, formattedMsg, System.currentTimeMillis())

                if (existing.senderOnion == mySenderId && reactorId != mySenderId) {
                    showGroupNotification(context, groupId, "REACTION")
                }
            }
        }
    }

    fun showGroupNotification(context: Context, groupId: String, messageType: String) {
        // Notification Suppression: do not show if user is actively in this group
        try {
            val myApp = context.applicationContext as app.secure.kyber.MyApp.MyApp
            if (myApp.activeGroupId == groupId) {
                Log.d("GroupManager", "Notification suppressed: user is in group $groupId")
                return
            }
        } catch (e: Exception) {}

        val secureBody = when (messageType.uppercase()) {
            "IMAGE" -> "Photo"
            "VIDEO" -> "Video"
            "AUDIO" -> "Voice message"
            "REACTION" -> "Someone reacted to your message"
            "CHUNKED_MEDIA" -> "Photo"
            else -> "Text message"
        }

        val intent = android.content.Intent(context, app.secure.kyber.activities.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("OPEN_GROUP_CHAT", "true")
            putExtra("GROUP_ID", groupId)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, groupId.hashCode(), intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "group_messages_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId, "Group Messages", android.app.NotificationManager.IMPORTANCE_HIGH
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(app.secure.kyber.R.mipmap.ic_launcher)
            .setContentTitle("You have a new message")
            .setContentText(secureBody)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(groupId.hashCode(), notification)
    }

    // ── Other operations ────────────────────────────────────────────────────────

    suspend fun updateReaction(groupId: String, messageId: String, reaction: String): Boolean {
        return try {
            groupMessagesRef.child(groupId).child(messageId).child("reaction").setValue(reaction).await()
            true
        } catch (e: Exception) {
            Log.e("GroupManager", "Error updating reaction: ${e.message}", e)
            false
        }
    }

    suspend fun deleteMessage(groupId: String, messageId: String): Boolean {
        return try {
            groupMessagesRef.child(groupId).child(messageId).removeValue().await()
            true
        } catch (e: Exception) {
            Log.e("GroupManager", "Error deleting message: ${e.message}", e)
            false
        }
    }

    suspend fun updateLastMessage(groupId: String, lastMsg: String, lastTime: String, senderId: String) {
        try {
            val updates = hashMapOf<String, Any>(
                "lastMessage"         to lastMsg,
                "lastMessageTime"     to lastTime,
                "lastMessageSenderId" to senderId
            )
            groupsRef.child(groupId).updateChildren(updates).await()
        } catch (e: Exception) {
            Log.e("GroupManager", "Error updating last message: ${e.message}", e)
        }
    }

    suspend fun deleteGroupEverywhere(groupId: String, members: Collection<String>) {
        try {
            groupsRef.child(groupId).removeValue().await()
            groupMessagesRef.child(groupId).removeValue().await()
            members.forEach { userId ->
                val sanitizedUserId = sanitizeFirebaseKey(userId)
                userGroupsRef.child(sanitizedUserId).child(groupId).removeValue().await()
            }
        } catch (e: Exception) {
            Log.e("GroupManager", "Error deleting group globally: ${e.message}", e)
        }
    }

    fun listenForNewMessages(
        groupId: String,
        onNewMessage: (GroupMessage) -> Unit
    ): DatabaseReference {
        val messagesListener = groupMessagesRef.child(groupId)
        messagesListener.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val message = snapshot.getValue(GroupMessage::class.java)
                message?.let { onNewMessage(it) }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e("GroupManager", "listenForNewMessages error: ${error.message}")
            }
        })
        return messagesListener
    }


    fun getUserGroups(userId: String, onGroupsReceived: (List<Group>) -> Unit) {
        userGroupsRef.child(sanitizeFirebaseKey(userId)).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val groups = mutableListOf<Group>()
                val groupIds = snapshot.children.mapNotNull { it.key }
                if (groupIds.isEmpty()) { onGroupsReceived(emptyList()); return }
                var processedCount = 0
                groupIds.forEach { groupId ->
                    groupsRef.child(groupId).get().addOnSuccessListener { groupSnapshot ->
                        val group = groupSnapshot.getValue(Group::class.java)
                        group?.let { groups.add(it) }
                        processedCount++
                        if (processedCount == groupIds.size) {
                            groups.sortByDescending { it.lastMessageTime.toLongOrNull() ?: 0L }
                            onGroupsReceived(groups)
                        }
                    }.addOnFailureListener {
                        processedCount++
                        if (processedCount == groupIds.size) onGroupsReceived(groups)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("GroupManager", "getUserGroups error: ${error.message}")
            }
        })
    }

    fun removeListener(reference: DatabaseReference) {
        // Pass the actual listener object to removeEventListener if needed
    }

    private fun resolveAnonymousDisplayName(
        context: Context?,
        groupId: String,
        senderId: String,
        rawName: String,
        isGroupAnonymous: Boolean,
        groupCreatorId: String,
        mySenderId: String
    ): String {
        if (!isGroupAnonymous) return rawName
        if (senderId == groupCreatorId) return rawName
        if (mySenderId == groupCreatorId) return rawName
        if (senderId == mySenderId) return rawName
        val alias = context?.let { resolveAnonymousAlias(it, groupId, senderId) }
        return alias ?: "Anonymous User"
    }

    private fun resolveAnonymousAlias(context: Context, groupId: String, senderId: String): String? {
        return try {
            val raw = context.getSharedPreferences("anonymous_aliases", Context.MODE_PRIVATE)
                .getString(groupId, null) ?: return null
            val json = org.json.JSONObject(raw)
            json.optString(senderId, json.optString(sanitizeFirebaseKey(senderId), "")).ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeFirebaseKey(raw: String): String {
        if (raw.isBlank()) return "_"
        val sanitized = raw.replace(invalidFirebaseKeyChars, ",")
        return if (sanitized.isBlank()) "_" else sanitized
    }
}
