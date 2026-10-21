# Queued: port DPAD-Messaging's MMS receive structure

Status: **done** — see MmsReceiver, LibraryMmsReceivedReceiver, MmsReceivePostProcessor, MmsTransmitter and MessageSender. mmslib is vendored as the `:mmslib` module (FossifyOrg/mmslib) rather than fetched from JitPack. Reference: https://github.com/jbriones95/DPAD-Messaging (MIT — keep attribution on anything copied).

## Why
TurboText receives MMS with its own code: `MmsDeliverReceiver` + `MmsPduParser` parse the WAP push,
`SmsManager.downloadMultimediaMessage` fetches into a cache file, then `MmsDownloadReceiver` +
`MmsRetrieveParser` parse the M-Retrieve-Conf by hand and `SmsRepository.insertReceivedMms` writes the
rows/parts into `content://mms` itself. Every carrier quirk (empty `message-id=` URLs, transaction-id
appending, duplicate pushes, group addressing) has to be handled by that hand-written code.

DPAD hands all of that to the Klinker/AOSP MMS library instead, and only does post-processing itself.

## DPAD's structure
| Piece | DPAD file | Role |
|---|---|---|
| WAP push receiver | `receivers/MmsReceiver.kt` | `class MmsReceiver : com.android.mms.transaction.PushReceiver()` — one line. Library parses the push, de-dupes, appends transaction-id, downloads. |
| Library settings | `helpers/MmsSender.kt` `initLibraryReceive()` (called from `App.onCreate`) | Sets `Transaction.settings = Settings().apply { setUseSystemSending(true); setGroup(true); setDeliveryReports(...) }`. |
| Download-complete | `receivers/LibraryMmsReceivedReceiver.kt` | `: MmsReceivedReceiver()`, manifest `taskAffinity="com.klinker.android.messaging.MMS_RECEIVED"`. `onMessageReceived(uri)` → enqueue worker with the row id; `onError` → enqueue a fallback scan (some ROMs persist via the system MmsService instead). |
| Alt. completion | `receivers/MmsDownloadReceiver.kt` | `taskAffinity`/action `com.klinker.android.messaging.NEW_MMS_DOWNLOADED`; routes to the same worker. |
| Post-processing | `receivers/MmsReceiveWorker.kt` | WorkManager job: reads `content://mms/<id>` (requires `m_type=132`, `msg_box=1`, real thread_id), FROM address from `/addr` `type=137`, builds preview from parts, notifies, invalidates caches, posts refresh. Retries up to 3×; fallback picks newest `m_type=132` inbox row in the last 3 min. |

## Porting notes for TurboText
- TurboText already depends on `com.klinkerapps:android-smsmms:5.2.6` (used for sending), so no new
  dependency is needed. DPAD uses the `org.fossify:mmslib` fork — check that `PushReceiver`,
  `MmsReceivedReceiver`, and `Transaction.settings` exist with the same signatures in 5.2.6 before copying.
- Replace the manifest's `.MmsDeliverReceiver` (WAP_PUSH_DELIVER) with the `PushReceiver` subclass; add the
  `MMS_RECEIVED` receiver.
- Post-processing must keep TurboText's existing side effects from `MmsDownloadReceiver`: `MessageCache`
  refresh, group display-name logic (`getThreadParticipants`, `GroupNicknameHelper`),
  `NotificationHelper.showIncoming`, `SoundNotificationHelper.notifyNewMessage`, `ReadAloudHelper`, and
  `ProviderChangeTracker.bump()`.
- Reading images/vCards/audio back must work from library-written parts (`content://mms/part/<id>`) —
  verify `SmsRepository`'s MMS message reader handles them, since it was written against our own inserts.
- WorkManager would be a new dependency; `goAsync()` + a background thread (the current pattern) is fine
  instead.
- Once stable, `MmsPduParser`, `MmsRetrieveParser`, and `insertReceivedMms` can be deleted.
