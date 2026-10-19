/*
 * Copyright 2014 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms.transaction;

import static android.provider.Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION;
import static android.provider.Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION;
import static com.google.android.mms.pdu_alt.PduHeaders.MESSAGE_TYPE_DELIVERY_IND;
import static com.google.android.mms.pdu_alt.PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND;
import static com.google.android.mms.pdu_alt.PduHeaders.MESSAGE_TYPE_READ_ORIG_IND;

import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Mms.Inbox;
import android.telephony.SmsManager;
import android.text.TextUtils;

import com.android.mms.MmsConfig;
import com.android.mms.logs.LogTag;
import com.android.mms.service_alt.DownloadRequest;
import com.android.mms.service_alt.MmsNetworkManager;
import com.android.mms.service_alt.MmsRequestManager;
import com.google.android.mms.ContentType;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu_alt.DeliveryInd;
import com.google.android.mms.pdu_alt.GenericPdu;
import com.google.android.mms.pdu_alt.NotificationInd;
import com.google.android.mms.pdu_alt.PduHeaders;
import com.google.android.mms.pdu_alt.PduParser;
import com.google.android.mms.pdu_alt.PduPersister;
import com.google.android.mms.pdu_alt.ReadOrigInd;
import com.klinker.android.logger.Log;
import com.klinker.android.send_message.Settings;
import com.klinker.android.send_message.SmsManagerFactory;
import com.klinker.android.send_message.Utils;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Receives Intent.WAP_PUSH_RECEIVED_ACTION intents and starts the
 * TransactionService by passing the push-data to it.
 */
public class PushReceiver extends BroadcastReceiver {
    private static final String TAG = LogTag.TAG;
    /** TurboText: every step of a delivery report, in logcat under one tag
     *  (no message content), to see where a "Delivered" goes missing. */
    static final String REPORT_TAG = "TurboTextMmsReport";
    private static final boolean DEBUG = false;
    private static final boolean LOCAL_LOGV = true;

    static final String[] PROJECTION = new String[]{
            Mms.CONTENT_LOCATION,
            Mms.LOCKED
    };

    static final String[] TRANSACTION_ID_PROJECTION = new String[]{
            Mms.CONTENT_LOCATION,
            Mms.LOCKED
    };

    static final int COLUMN_CONTENT_LOCATION = 0;

    /** TurboText: download URL -> when its download was started. A
     *  carrier's repeat of the same notice within [DOWNLOAD_REPEAT_MS] is
     *  skipped, but a URL that comes back later (some carriers reuse
     *  them) is downloaded again; this used to block it for the life of
     *  the process, silently losing that message. */
    private static final Map<String, Long> downloadedUrls = new HashMap<String, Long>();
    private static final long DOWNLOAD_REPEAT_MS = 10 * 60 * 1000L;

    /** TurboText: delivery reports that arrived before the sent message's
     *  Message-ID was saved (it's written when the carrier's send
     *  confirmation comes back, which can lose the race), kept here and
     *  matched again by [retryPendingReports]. */
    private static final String PENDING_REPORTS_PREFS = "mms_pending_delivery_reports";
    private static final String PENDING_REPORTS_KEY = "reports";
    private static final long PENDING_REPORT_MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    /** TurboText: how far back to look for the sent message a delivery
     *  report belongs to, when its Message-ID only matches loosely. */
    private static final long REPORT_LOOKBACK_SECONDS = 7 * 24 * 60 * 60L;

    /** TurboText: a notice whose transaction ID matches a row older than
     *  this is a new message from a carrier that reuses IDs, not a repeat. */
    private static final long DUPLICATE_WINDOW_SECONDS = 24 * 60 * 60L;
    private static final ExecutorService PUSH_RECEIVER_EXECUTOR = Executors.newSingleThreadExecutor();

    private class ReceivePushTask extends AsyncTask<Intent, Void, Void> {
        private Context mContext;
        private PendingResult pendingResult;

        ReceivePushTask(Context context, PendingResult pendingResult) {
            mContext = context;
            this.pendingResult = pendingResult;
        }

        @Override
        protected Void doInBackground(Intent... intents) {
            Log.v(TAG, "receiving a new mms message");
            Intent intent = intents[0];

            // Get raw PDU push-data from the message and parse it
            byte[] pushData = intent.getByteArrayExtra("data");
            PduParser parser = new PduParser(pushData);
            GenericPdu pdu = parser.parse();

            if (null == pdu) {
                Log.e(TAG, "Invalid PUSH data");
                return null;
            }

            PduPersister p = PduPersister.getPduPersister(mContext);
            ContentResolver cr = mContext.getContentResolver();
            int type = pdu.getMessageType();
            long threadId = -1;
            android.util.Log.i(REPORT_TAG, "push received, type=" + type);
            int subId = intent.getIntExtra("subscription", Settings.DEFAULT_SUBSCRIPTION_ID);

            try {
                switch (type) {
                    case MESSAGE_TYPE_DELIVERY_IND:
                    case MESSAGE_TYPE_READ_ORIG_IND: {
                        threadId = findThreadId(mContext, pdu, type);
                        if (type == MESSAGE_TYPE_DELIVERY_IND) {
                            DeliveryInd ind = (DeliveryInd) pdu;
                            android.util.Log.i(REPORT_TAG, "delivery report: id=" + idForLog(ind.getMessageId())
                                    + " status=" + ind.getStatus()
                                    + (threadId == -1 ? " -> no sent message yet, kept" : " -> thread " + threadId));
                        }
                        if (threadId == -1) {
                            // The associated SendReq isn't found (yet).
                            // TurboText: a delivery report can beat the
                            // send confirmation that saves the sent
                            // message's Message-ID, so keep it to match
                            // once that's written (retryPendingReports).
                            if (type == MESSAGE_TYPE_DELIVERY_IND) {
                                savePendingReport(mContext, pushData, subId);
                            }
                            break;
                        }
                        persistReport(mContext, pdu, threadId, subId);
                        break;
                    }
                    case MESSAGE_TYPE_NOTIFICATION_IND: {
                        NotificationInd nInd = (NotificationInd) pdu;

                        boolean appendTransactionId = false;
                        Bundle configOverrides = SmsManagerFactory.createSmsManager(subId).getCarrierConfigValues();
                        appendTransactionId = configOverrides.getBoolean(SmsManager.MMS_CONFIG_APPEND_TRANSACTION_ID);

                        if (appendTransactionId) {
                            Log.v(TAG, "appending the transaction ID, based on the SMS manager overrides");
                        }

                        if (MmsConfig.getTransIdEnabled() || appendTransactionId) {
                            byte[] contentLocation = nInd.getContentLocation();
                            if ('=' == contentLocation[contentLocation.length - 1]) {
                                byte[] transactionId = nInd.getTransactionId();
                                byte[] contentLocationWithId = new byte[contentLocation.length
                                        + transactionId.length];
                                System.arraycopy(contentLocation, 0, contentLocationWithId,
                                        0, contentLocation.length);
                                System.arraycopy(transactionId, 0, contentLocationWithId,
                                        contentLocation.length, transactionId.length);
                                nInd.setContentLocation(contentLocationWithId);
                            }
                        }

                        if (!isDuplicateNotification(mContext, nInd)) {
                            // Save the pdu. If we can start downloading the real pdu immediately,
                            // don't allow persist() to create a thread for the notificationInd
                            // because it causes UI jank.
                            boolean group;

                            try {
                                group = com.klinker.android.send_message.Transaction.settings.getGroup();
                            } catch (Exception e) {
                                group = PreferenceManager.getDefaultSharedPreferences(mContext).getBoolean("group_message", true);
                            }

                            Uri uri = p.persist(pdu, Inbox.CONTENT_URI,
                                    !NotificationTransaction.allowAutoDownload(mContext),
                                    group,
                                    null,
                                    subId);

                            String location;
                            try {
                                location = getContentLocation(mContext, uri);
                            } catch (MmsException ex) {
                                location = p.getContentLocationFromPduHeader(pdu);
                                if (TextUtils.isEmpty(location)) {
                                    throw ex;
                                }
                            }

                            synchronized (downloadedUrls) {
                                long now = System.currentTimeMillis();
                                Long started = downloadedUrls.get(location);
                                if (started != null && now - started < DOWNLOAD_REPEAT_MS) {
                                    Log.v(TAG, "already added this download, don't download again");
                                    return null;
                                }
                                downloadedUrls.put(location, now);
                            }

                            String transactionId;
                            try {
                                transactionId = getTransactionId(mContext, uri);
                            } catch (MmsException ex) {
                                transactionId = p.getTransactionIdFromPduHeader(pdu);
                                if (TextUtils.isEmpty(transactionId)) {
                                    throw ex;
                                }
                            }

                            Log.v(TAG, "receiving on a lollipop+ device");
                            boolean useSystem = true;

                            if (com.klinker.android.send_message.Transaction.settings != null) {
                                useSystem = com.klinker.android.send_message.Transaction.settings
                                        .getUseSystemSending();
                            } else {
                                useSystem = PreferenceManager.getDefaultSharedPreferences(mContext)
                                        .getBoolean("system_mms_sending", useSystem);
                            }

                            if (useSystem) {
                                DownloadManager.getInstance().downloadMultimediaMessage(mContext, location, transactionId, uri, true, subId);
                            } else {
                                Log.v(TAG, "receiving with lollipop method");
                                MmsRequestManager requestManager = new MmsRequestManager(mContext);
                                DownloadRequest request = new DownloadRequest(requestManager,
                                        Utils.getDefaultSubscriptionId(),
                                        location, transactionId, uri, null, null,
                                        null, mContext);
                                MmsNetworkManager manager = new MmsNetworkManager(mContext, Utils.getDefaultSubscriptionId());
                                request.execute(mContext, manager);
                            }
                        } else if (LOCAL_LOGV) {
                            Log.v(TAG, "Skip downloading duplicate message: "
                                    + new String(nInd.getTransactionId()));
                        }
                        break;
                    }
                    default:
                        Log.e(TAG, "Received unrecognized PDU.");
                }
            } catch (MmsException e) {
                Log.e(TAG, "Failed to save the data from PUSH: type=" + type, e);
            } catch (RuntimeException e) {
                Log.e(TAG, "Unexpected RuntimeException.", e);
            }

            if (LOCAL_LOGV) {
                Log.v(TAG, "PUSH Intent processed.");
            }

            return null;
        }

        @Override
        public void onPostExecute(Void result) {
            if (pendingResult != null) {
                pendingResult.finish();
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.v(TAG, intent.getAction() + " " + intent.getType());
        if ((intent.getAction().equals(WAP_PUSH_DELIVER_ACTION) || intent.getAction().equals(WAP_PUSH_RECEIVED_ACTION))
                && ContentType.MMS_MESSAGE.equals(intent.getType())) {
            if (LOCAL_LOGV) {
                Log.v(TAG, "Received PUSH Intent: " + intent);
            }

            MmsConfig.init(context);
            // TurboText: goAsync keeps the process alive until the PDU is
            // saved; without it a cold start could be killed mid-save and
            // the message (or delivery report) lost.
            new ReceivePushTask(context, goAsync()).executeOnExecutor(PUSH_RECEIVER_EXECUTOR, intent);

            Log.v("mms_receiver", context.getPackageName() + " received and aborted");
        }
    }

    public static String getContentLocation(Context context, Uri uri)
            throws MmsException {
        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                uri, PROJECTION, null, null, null);

        if (cursor != null) {
            try {
                if ((cursor.getCount() == 1) && cursor.moveToFirst()) {
                    String location = cursor.getString(COLUMN_CONTENT_LOCATION);
                    cursor.close();
                    return location;
                }
            } finally {
                cursor.close();
            }
        }

        throw new MmsException("Cannot get X-Mms-Content-Location from: " + uri);
    }

    public static String getTransactionId(Context context, Uri uri)
            throws MmsException {
        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                uri, TRANSACTION_ID_PROJECTION, null, null, null);

        if (cursor != null) {
            try {
                if ((cursor.getCount() == 1) && cursor.moveToFirst()) {
                    String transactionId = cursor.getString(COLUMN_CONTENT_LOCATION);
                    cursor.close();
                    return transactionId;
                }
            } finally {
                cursor.close();
            }
        }

        throw new MmsException("Cannot get Transaction-id from: " + uri);
    }

    /** Saves a delivery or read report into the sent message's thread. */
    private static void persistReport(Context context, GenericPdu pdu, long threadId, int subId)
            throws MmsException {
        boolean group;

        try {
            group = com.klinker.android.send_message.Transaction.settings.getGroup();
        } catch (Exception e) {
            group = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("group_message", true);
        }

        PduPersister p = PduPersister.getPduPersister(context);
        Uri uri = p.persist(pdu, Inbox.CONTENT_URI, true,
                group, null, subId);
        // Update thread ID for ReadOrigInd & DeliveryInd.
        // TurboText: also mark it read and seen — it's a
        // report about a sent message, not a new message,
        // and must not count as unread.
        ContentValues values = new ContentValues(3);
        values.put(Mms.THREAD_ID, threadId);
        values.put(Mms.READ, 1);
        values.put(Mms.SEEN, 1);
        SqliteWrapper.update(context, context.getContentResolver(), uri, values, null, null);
    }

    /** TurboText: a Message-ID as the carrier may write it in one place
     *  and not another — surrounding space, quotes or angle brackets. */
    public static String normalizeMessageId(String id) {
        if (id == null) return "";
        String s = id.trim();
        while (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("<") && s.endsWith(">")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static long findThreadId(Context context, GenericPdu pdu, int type) {
        byte[] rawId;

        if (type == MESSAGE_TYPE_DELIVERY_IND) {
            rawId = ((DeliveryInd) pdu).getMessageId();
        } else {
            rawId = ((ReadOrigInd) pdu).getMessageId();
        }
        if (rawId == null) return -1;
        String messageId = new String(rawId);

        String selection = Mms.MESSAGE_ID + "=" + DatabaseUtils.sqlEscapeString(messageId)
                + " AND " + Mms.MESSAGE_TYPE + "=" + PduHeaders.MESSAGE_TYPE_SEND_REQ;

        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                Mms.CONTENT_URI, new String[]{Mms.THREAD_ID},
                selection, null, Mms.DATE + " DESC");
        if (cursor != null) {
            try {
                // TurboText: the newest match, not only a single one — a
                // repeated ID used to drop the report.
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0);
                }
            } finally {
                cursor.close();
            }
        }

        // TurboText: no exact match — compare the IDs of recent sent
        // messages loosely (see normalizeMessageId).
        String wanted = normalizeMessageId(messageId);
        if (wanted.isEmpty()) return -1;
        long since = System.currentTimeMillis() / 1000L - REPORT_LOOKBACK_SECONDS;
        Cursor loose = SqliteWrapper.query(context, context.getContentResolver(),
                Mms.CONTENT_URI, new String[]{Mms.THREAD_ID, Mms.MESSAGE_ID},
                Mms.MESSAGE_TYPE + "=" + PduHeaders.MESSAGE_TYPE_SEND_REQ
                        + " AND " + Mms.MESSAGE_ID + " IS NOT NULL AND " + Mms.DATE + " > " + since,
                null, Mms.DATE + " DESC");
        if (loose != null) {
            try {
                while (loose.moveToNext()) {
                    if (wanted.equals(normalizeMessageId(loose.getString(1)))) {
                        return loose.getLong(0);
                    }
                }
            } finally {
                loose.close();
            }
        }

        if (type == MESSAGE_TYPE_DELIVERY_IND) {
            return matchByRecipient(context, (DeliveryInd) pdu, messageId);
        }
        return -1;
    }

    /**
     * TurboText: when no sent message has an ID at all to match (the send
     * confirmation came back without one), the report belongs to the
     * newest sent picture message to its recipient that's still missing
     * its ID. That message is given the report's ID, so it's matched from
     * then on. Only messages from the last two days are considered.
     */
    private static long matchByRecipient(Context context, DeliveryInd ind, String messageId) {
        com.google.android.mms.pdu_alt.EncodedStringValue[] to = ind.getTo();
        if (to == null || to.length == 0 || to[0] == null) return -1;
        String recipient = to[0].getString();
        long since = System.currentTimeMillis() / 1000L - 2 * 24 * 60 * 60L;
        Cursor c = SqliteWrapper.query(context, context.getContentResolver(),
                Mms.CONTENT_URI, new String[]{Mms._ID, Mms.THREAD_ID},
                Mms.MESSAGE_TYPE + "=" + PduHeaders.MESSAGE_TYPE_SEND_REQ
                        + " AND " + Mms.MESSAGE_BOX + "=" + Mms.MESSAGE_BOX_SENT
                        + " AND (" + Mms.MESSAGE_ID + " IS NULL OR " + Mms.MESSAGE_ID + "='')"
                        + " AND " + Mms.DATE + " > " + since,
                null, Mms.DATE + " DESC");
        if (c == null) return -1;
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                Cursor addr = SqliteWrapper.query(context, context.getContentResolver(),
                        Uri.parse("content://mms/" + id + "/addr"), new String[]{"address"},
                        "type=" + PduHeaders.TO, null, null);
                boolean match = false;
                if (addr != null) {
                    try {
                        while (!match && addr.moveToNext()) {
                            match = android.telephony.PhoneNumberUtils.compare(recipient, addr.getString(0));
                        }
                    } finally {
                        addr.close();
                    }
                }
                if (match) {
                    ContentValues values = new ContentValues(1);
                    values.put(Mms.MESSAGE_ID, messageId);
                    SqliteWrapper.update(context, context.getContentResolver(),
                            Uri.parse("content://mms/" + id), values, null, null);
                    android.util.Log.i(REPORT_TAG, "matched by recipient to sent message " + id);
                    return c.getLong(1);
                }
            }
        } finally {
            c.close();
        }
        return -1;
    }

    /** A Message-ID for the log: its length and first few characters. */
    public static String idForLog(byte[] id) {
        if (id == null) return "none";
        String s = new String(id);
        return s.length() + ":" + (s.length() > 6 ? s.substring(0, 6) + "…" : s);
    }

    /** TurboText: keeps an unmatched delivery report for [retryPendingReports]. */
    private static void savePendingReport(Context context, byte[] pushData, int subId) {
        if (pushData == null) return;
        synchronized (PushReceiver.class) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PENDING_REPORTS_PREFS, Context.MODE_PRIVATE);
                JSONArray list = new JSONArray(prefs.getString(PENDING_REPORTS_KEY, "[]"));
                JSONObject entry = new JSONObject();
                entry.put("pdu", android.util.Base64.encodeToString(pushData, android.util.Base64.NO_WRAP));
                entry.put("sub", subId);
                entry.put("at", System.currentTimeMillis());
                list.put(entry);
                prefs.edit().putString(PENDING_REPORTS_KEY, list.toString()).apply();
                Log.v(TAG, "delivery report kept until its message is matched");
            } catch (Exception e) {
                Log.e(TAG, "couldn't keep unmatched delivery report", e);
            }
        }
    }

    /**
     * TurboText: matches delivery reports that arrived before their sent
     * message's Message-ID was saved, now that it may be. Call after
     * writing a sent message's Message-ID, off the main thread. Reports
     * still unmatched after a day are dropped. Returns how many matched.
     */
    public static int retryPendingReports(Context context) {
        synchronized (PushReceiver.class) {
            int matched = 0;
            try {
                SharedPreferences prefs = context.getSharedPreferences(PENDING_REPORTS_PREFS, Context.MODE_PRIVATE);
                JSONArray list = new JSONArray(prefs.getString(PENDING_REPORTS_KEY, "[]"));
                if (list.length() == 0) return 0;
                JSONArray keep = new JSONArray();
                long now = System.currentTimeMillis();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject entry = list.getJSONObject(i);
                    if (now - entry.optLong("at", 0) > PENDING_REPORT_MAX_AGE_MS) continue;
                    byte[] data = android.util.Base64.decode(entry.getString("pdu"), android.util.Base64.NO_WRAP);
                    GenericPdu pdu = new PduParser(data).parse();
                    if (pdu == null || pdu.getMessageType() != MESSAGE_TYPE_DELIVERY_IND) continue;
                    long threadId = findThreadId(context, pdu, MESSAGE_TYPE_DELIVERY_IND);
                    if (threadId == -1) {
                        keep.put(entry);
                        continue;
                    }
                    persistReport(context, pdu, threadId,
                            entry.optInt("sub", Settings.DEFAULT_SUBSCRIPTION_ID));
                    matched++;
                }
                prefs.edit().putString(PENDING_REPORTS_KEY, keep.toString()).apply();
                android.util.Log.i(REPORT_TAG, "kept reports retried: " + matched + " matched, "
                        + keep.length() + " still waiting");
            } catch (Exception e) {
                Log.e(TAG, "couldn't match kept delivery reports", e);
            }
            return matched;
        }
    }

    private static boolean isDuplicateNotification(
            Context context, NotificationInd nInd) {
        byte[] rawTransactionId = nInd.getTransactionId();
        if (rawTransactionId != null) {
            String location = new String(rawTransactionId);
            // TurboText: only a recent row counts as the same notice. Some
            // carriers reuse transaction IDs, and an old match used to drop
            // a brand-new message without a trace.
            long since = System.currentTimeMillis() / 1000L - DUPLICATE_WINDOW_SECONDS;
            String selection = Mms.TRANSACTION_ID + " = ? AND " + Mms.DATE + " > " + since;
            String[] selectionArgs = new String[]{location};
            Cursor cursor = SqliteWrapper.query(
                    context, context.getContentResolver(),
                    Mms.CONTENT_URI, new String[]{Mms._ID},
                    selection, selectionArgs, null);
            if (cursor != null) {
                try {
                    if (cursor.getCount() > 0) {
                        // We already received the same notification before.
                        cursor.close();
                        return true;
                    }
                } finally {
                    cursor.close();
                }
            }
        }
        return false;
    }
}
