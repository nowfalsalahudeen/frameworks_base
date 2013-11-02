/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.content;

import com.android.internal.app.ThemeUtils;

import android.accounts.Account;
import android.accounts.AccountAndUser;
import android.accounts.AccountManager;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ISyncAdapter;
import android.content.ISyncContext;
import android.content.ISyncStatusObserver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PeriodicSync;
import android.content.ServiceConnection;
import android.content.SyncActivityTooManyDeletes;
import android.content.SyncAdapterType;
import android.content.SyncAdaptersCache;
import android.content.SyncInfo;
import android.content.SyncResult;
import android.content.SyncStatusInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.RegisteredServicesCache;
import android.content.pm.RegisteredServicesCacheListener;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.accounts.AccountManagerService;
import com.android.server.content.SyncStorageEngine.AuthorityInfo;
import com.android.server.content.SyncStorageEngine.OnSyncRequestListener;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;
import com.google.android.collect.Sets;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * @hide
 */
public class SyncManager {
    private static final String TAG = "SyncManager";

    /** Delay a sync due to local changes this long. In milliseconds */
    private static final long LOCAL_SYNC_DELAY;

    /**
     * If a sync takes longer than this and the sync queue is not empty then we will
     * cancel it and add it back to the end of the sync queue. In milliseconds.
     */
    private static final long MAX_TIME_PER_SYNC;

    static {
        final boolean isLargeRAM = !ActivityManager.isLowRamDeviceStatic();
        int defaultMaxInitSyncs = isLargeRAM ? 5 : 2;
        int defaultMaxRegularSyncs = isLargeRAM ? 2 : 1;
        MAX_SIMULTANEOUS_INITIALIZATION_SYNCS =
                SystemProperties.getInt("sync.max_init_syncs", defaultMaxInitSyncs);
        MAX_SIMULTANEOUS_REGULAR_SYNCS =
                SystemProperties.getInt("sync.max_regular_syncs", defaultMaxRegularSyncs);
        LOCAL_SYNC_DELAY =
                SystemProperties.getLong("sync.local_sync_delay", 30 * 1000 /* 30 seconds */);
        MAX_TIME_PER_SYNC =
                SystemProperties.getLong("sync.max_time_per_sync", 5 * 60 * 1000 /* 5 minutes */);
        SYNC_NOTIFICATION_DELAY =
                SystemProperties.getLong("sync.notification_delay", 30 * 1000 /* 30 seconds */);
    }

    private static final long SYNC_NOTIFICATION_DELAY;

    /**
     * When retrying a sync for the first time use this delay. After that
     * the retry time will double until it reached MAX_SYNC_RETRY_TIME.
     * In milliseconds.
     */
    private static final long INITIAL_SYNC_RETRY_TIME_IN_MS = 30 * 1000; // 30 seconds

    /**
     * Default the max sync retry time to this value.
     */
    private static final long DEFAULT_MAX_SYNC_RETRY_TIME_IN_SECONDS = 60 * 60; // one hour

    /**
     * How long to wait before retrying a sync that failed due to one already being in progress.
     */
    private static final int DELAY_RETRY_SYNC_IN_PROGRESS_IN_SECONDS = 10;

    private static final int INITIALIZATION_UNBIND_DELAY_MS = 5000;

    private static final String SYNC_WAKE_LOCK_PREFIX = "*sync*";
    private static final String HANDLE_SYNC_ALARM_WAKE_LOCK = "SyncManagerHandleSyncAlarm";
    private static final String SYNC_LOOP_WAKE_LOCK = "SyncLoopWakeLock";

    private static final int MAX_SIMULTANEOUS_REGULAR_SYNCS;
    private static final int MAX_SIMULTANEOUS_INITIALIZATION_SYNCS;

    private Context mContext;
    private Context mUiContext;

    private static final AccountAndUser[] INITIAL_ACCOUNTS_ARRAY = new AccountAndUser[0];

    // TODO: add better locking around mRunningAccounts
    private volatile AccountAndUser[] mRunningAccounts = INITIAL_ACCOUNTS_ARRAY;

    volatile private PowerManager.WakeLock mHandleAlarmWakeLock;
    volatile private PowerManager.WakeLock mSyncManagerWakeLock;
    volatile private boolean mDataConnectionIsConnected = false;
    volatile private boolean mStorageIsLow = false;

    private final NotificationManager mNotificationMgr;
    private AlarmManager mAlarmService = null;

    private SyncStorageEngine mSyncStorageEngine;

    @GuardedBy("mSyncQueue")
    private final SyncQueue mSyncQueue;

    protected final ArrayList<ActiveSyncContext> mActiveSyncContexts = Lists.newArrayList();

    // set if the sync active indicator should be reported
    private boolean mNeedSyncActiveNotification = false;

    private final PendingIntent mSyncAlarmIntent;
    // Synchronized on "this". Instead of using this directly one should instead call
    // its accessor, getConnManager().
    private ConnectivityManager mConnManagerDoNotUseDirectly;

    protected SyncAdaptersCache mSyncAdapters;

    private BroadcastReceiver mStorageIntentReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "Internal storage is low.");
                        }
                        mStorageIsLow = true;
                        cancelActiveSync(null /* any account */, UserHandle.USER_ALL,
                                null /* any authority */);
                    } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "Internal storage is ok.");
                        }
                        mStorageIsLow = false;
                        sendCheckAlarmsMessage();
                    }
                }
            };

    private BroadcastReceiver mBootCompletedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mSyncHandler.onBootCompleted();
        }
    };

    private BroadcastReceiver mThemeChangeReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            mUiContext = null;
        }
    };

    private BroadcastReceiver mBackgroundDataSettingChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getConnectivityManager().getBackgroundDataSetting()) {
                scheduleSync(null /* account */, UserHandle.USER_ALL,
                        SyncOperation.REASON_BACKGROUND_DATA_SETTINGS_CHANGED,
                        null /* authority */,
                        new Bundle(), 0 /* delay */, 0 /* delay */,
                        false /* onlyThoseWithUnknownSyncableState */);
            }
        }
    };

    private BroadcastReceiver mAccountsUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateRunningAccounts();

            // Kick off sync for everyone, since this was a radical account change
            scheduleSync(null, UserHandle.USER_ALL, SyncOperation.REASON_ACCOUNTS_UPDATED, null,
                    null, 0 /* no delay */, 0/* no delay */, false);
        }
    };

    private final PowerManager mPowerManager;

    // Use this as a random offset to seed all periodic syncs.
    private int mSyncRandomOffsetMillis;

    private final UserManager mUserManager;

    private static final long SYNC_ALARM_TIMEOUT_MIN = 30 * 1000; // 30 seconds
    private static final long SYNC_ALARM_TIMEOUT_MAX = 2 * 60 * 60 * 1000; // two hours

    private List<UserInfo> getAllUsers() {
        return mUserManager.getUsers();
    }

    private boolean containsAccountAndUser(AccountAndUser[] accounts, Account account, int userId) {
        boolean found = false;
        for (int i = 0; i < accounts.length; i++) {
            if (accounts[i].userId == userId
                    && accounts[i].account.equals(account)) {
                found = true;
                break;
            }
        }
        return found;
    }

    public void updateRunningAccounts() {
        mRunningAccounts = AccountManagerService.getSingleton().getRunningAccounts();

        if (mBootCompleted) {
            doDatabaseCleanup();
        }

        for (ActiveSyncContext currentSyncContext : mActiveSyncContexts) {
            if (!containsAccountAndUser(mRunningAccounts,
                    currentSyncContext.mSyncOperation.account,
                    currentSyncContext.mSyncOperation.userId)) {
                Log.d(TAG, "canceling sync since the account is no longer running");
                sendSyncFinishedOrCanceledMessage(currentSyncContext,
                        null /* no result since this is a cancel */);
            }
        }

        // we must do this since we don't bother scheduling alarms when
        // the accounts are not set yet
        sendCheckAlarmsMessage();
    }

    private void doDatabaseCleanup() {
        for (UserInfo user : mUserManager.getUsers(true)) {
            // Skip any partially created/removed users
            if (user.partial) continue;
            Account[] accountsForUser = AccountManagerService.getSingleton().getAccounts(user.id);
            mSyncStorageEngine.doDatabaseCleanup(accountsForUser, user.id);
        }
    }

    private BroadcastReceiver mConnectivityIntentReceiver =
            new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final boolean wasConnected = mDataConnectionIsConnected;

            // don't use the intent to figure out if network is connected, just check
            // ConnectivityManager directly.
            mDataConnectionIsConnected = readDataConnectionState();
            if (mDataConnectionIsConnected) {
                if (!wasConnected) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Reconnection detected: clearing all backoffs");
                    }
                    synchronized(mSyncQueue) {
                        mSyncStorageEngine.clearAllBackoffsLocked(mSyncQueue);
                    }
                }
                sendCheckAlarmsMessage();
            }
        }
    };

    private boolean readDataConnectionState() {
        NetworkInfo networkInfo = getConnectivityManager().getActiveNetworkInfo();
        return (networkInfo != null) && networkInfo.isConnected();
    }

    private BroadcastReceiver mShutdownIntentReceiver =
            new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.w(TAG, "Writing sync state before shutdown...");
            getSyncStorageEngine().writeAllState();
        }
    };

    private BroadcastReceiver mUserIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) return;

            if (Intent.ACTION_USER_REMOVED.equals(action)) {
                onUserRemoved(userId);
            } else if (Intent.ACTION_USER_STARTING.equals(action)) {
                onUserStarting(userId);
            } else if (Intent.ACTION_USER_STOPPING.equals(action)) {
                onUserStopping(userId);
            }
        }
    };

    private static final String ACTION_SYNC_ALARM = "android.content.syncmanager.SYNC_ALARM";
    private final SyncHandler mSyncHandler;

    private volatile boolean mBootCompleted = false;

    private ConnectivityManager getConnectivityManager() {
        synchronized (this) {
            if (mConnManagerDoNotUseDirectly == null) {
                mConnManagerDoNotUseDirectly = (ConnectivityManager)mContext.getSystemService(
                        Context.CONNECTIVITY_SERVICE);
            }
            return mConnManagerDoNotUseDirectly;
        }
    }

    /**
     * Should only be created after {@link ContentService#systemReady()} so that
     * {@link PackageManager} is ready to query.
     */
    public SyncManager(Context context, boolean factoryTest) {
        // Initialize the SyncStorageEngine first, before registering observers
        // and creating threads and so on; it may fail if the disk is full.
        mContext = context;

        SyncStorageEngine.init(context);
        mSyncStorageEngine = SyncStorageEngine.getSingleton();
        mSyncStorageEngine.setOnSyncRequestListener(new OnSyncRequestListener() {
            @Override
            public void onSyncRequest(Account account, int userId, int reason, String authority,
                    Bundle extras) {
                scheduleSync(account, userId, reason, authority, extras,
                    0 /* no delay */,
                    0 /* no delay */,
                    false);
            }
        });

        mSyncAdapters = new SyncAdaptersCache(mContext);
        mSyncQueue = new SyncQueue(mContext.getPackageManager(), mSyncStorageEngine, mSyncAdapters);

        mSyncHandler = new SyncHandler(BackgroundThread.get().getLooper());

        mSyncAdapters.setListener(new RegisteredServicesCacheListener<SyncAdapterType>() {
            @Override
            public void onServiceChanged(SyncAdapterType type, int userId, boolean removed) {
                if (!removed) {
                    scheduleSync(null, UserHandle.USER_ALL,
                            SyncOperation.REASON_SERVICE_CHANGED,
                            type.authority, null, 0 /* no delay */, 0 /* no delay */,
                            false /* onlyThoseWithUnkownSyncableState */);
                }
            }
        }, mSyncHandler);

        mSyncAlarmIntent = PendingIntent.getBroadcast(
                mContext, 0 /* ignored */, new Intent(ACTION_SYNC_ALARM), 0);

        IntentFilter intentFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(mConnectivityIntentReceiver, intentFilter);

        if (!factoryTest) {
            intentFilter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
            context.registerReceiver(mBootCompletedReceiver, intentFilter);
        }

        intentFilter = new IntentFilter(ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED);
        context.registerReceiver(mBackgroundDataSettingChanged, intentFilter);

        intentFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
        context.registerReceiver(mStorageIntentReceiver, intentFilter);

        intentFilter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        intentFilter.setPriority(100);
        context.registerReceiver(mShutdownIntentReceiver, intentFilter);

        intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        intentFilter.addAction(Intent.ACTION_USER_STARTING);
        intentFilter.addAction(Intent.ACTION_USER_STOPPING);
        mContext.registerReceiverAsUser(
                mUserIntentReceiver, UserHandle.ALL, intentFilter, null, null);

        ThemeUtils.registerThemeChangeReceiver(mContext, mThemeChangeReceiver);

        if (!factoryTest) {
            mNotificationMgr = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            context.registerReceiver(new SyncAlarmIntentReceiver(),
                    new IntentFilter(ACTION_SYNC_ALARM));
        } else {
            mNotificationMgr = null;
        }
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);

        // This WakeLock is used to ensure that we stay awake between the time that we receive
        // a sync alarm notification and when we finish processing it. We need to do this
        // because we don't do the work in the alarm handler, rather we do it in a message
        // handler.
        mHandleAlarmWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                HANDLE_SYNC_ALARM_WAKE_LOCK);
        mHandleAlarmWakeLock.setReferenceCounted(false);

        // This WakeLock is used to ensure that we stay awake while running the sync loop
        // message handler. Normally we will hold a sync adapter wake lock while it is being
        // synced but during the execution of the sync loop it might finish a sync for
        // one sync adapter before starting the sync for the other sync adapter and we
        // don't want the device to go to sleep during that window.
        mSyncManagerWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                SYNC_LOOP_WAKE_LOCK);
        mSyncManagerWakeLock.setReferenceCounted(false);

        mSyncStorageEngine.addStatusChangeListener(
                ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, new ISyncStatusObserver.Stub() {
            @Override
            public void onStatusChanged(int which) {
                // force the sync loop to run if the settings change
                sendCheckAlarmsMessage();
            }
        });

        if (!factoryTest) {
            // Register for account list updates for all users
            mContext.registerReceiverAsUser(mAccountsUpdatedReceiver,
                    UserHandle.ALL,
                    new IntentFilter(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION),
                    null, null);
        }

        // Pick a random second in a day to seed all periodic syncs
        mSyncRandomOffsetMillis = mSyncStorageEngine.getSyncRandomOffset() * 1000;
    }

    /**
     * Return a random value v that satisfies minValue <= v < maxValue. The difference between
     * maxValue and minValue must be less than Integer.MAX_VALUE.
     */
    private long jitterize(long minValue, long maxValue) {
        Random random = new Random(SystemClock.elapsedRealtime());
        long spread = maxValue - minValue;
        if (spread > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("the difference between the maxValue and the "
                    + "minValue must be less than " + Integer.MAX_VALUE);
        }
        return minValue + random.nextInt((int)spread);
    }

    public SyncStorageEngine getSyncStorageEngine() {
        return mSyncStorageEngine;
    }

    public int getIsSyncable(Account account, int userId, String providerName) {
        int isSyncable = mSyncStorageEngine.getIsSyncable(account, userId, providerName);
        UserInfo userInfo = UserManager.get(mContext).getUserInfo(userId);

        // If it's not a restricted user, return isSyncable
        if (userInfo == null || !userInfo.isRestricted()) return isSyncable;

        // Else check if the sync adapter has opted-in or not
        RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo =
                mSyncAdapters.getServiceInfo(
                SyncAdapterType.newKey(providerName, account.type), userId);
        if (syncAdapterInfo == null) return isSyncable;

        PackageInfo pInfo = null;
        try {
            pInfo = AppGlobals.getPackageManager().getPackageInfo(
                syncAdapterInfo.componentName.getPackageName(), 0, userId);
            if (pInfo == null) return isSyncable;
        } catch (RemoteException re) {
            // Shouldn't happen
            return isSyncable;
        }
        if (pInfo.restrictedAccountType != null
                && pInfo.restrictedAccountType.equals(account.type)) {
            return isSyncable;
        } else {
            return 0;
        }
    }

    private void ensureAlarmService() {
        if (mAlarmService == null) {
            mAlarmService = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        }
    }

    /**
     * Initiate a sync. This can start a sync for all providers
     * (pass null to url, set onlyTicklable to false), only those
     * providers that are marked as ticklable (pass null to url,
     * set onlyTicklable to true), or a specific provider (set url
     * to the content url of the provider).
     *
     * <p>If the ContentResolver.SYNC_EXTRAS_UPLOAD boolean in extras is
     * true then initiate a sync that just checks for local changes to send
     * to the server, otherwise initiate a sync that first gets any
     * changes from the server before sending local changes back to
     * the server.
     *
     * <p>If a specific provider is being synced (the url is non-null)
     * then the extras can contain SyncAdapter-specific information
     * to control what gets synced (e.g. which specific feed to sync).
     *
     * <p>You'll start getting callbacks after this.
     *
     * @param requestedAccount the account to sync, may be null to signify all accounts
     * @param userId the id of the user whose accounts are to be synced. If userId is USER_ALL,
     *          then all users' accounts are considered.
     * @param reason for sync request. If this is a positive integer, it is the Linux uid
     * assigned to the process that requested the sync. If it's negative, the sync was requested by
     * the SyncManager itself and could be one of the following:
     *      {@link SyncOperation#REASON_BACKGROUND_DATA_SETTINGS_CHANGED}
     *      {@link SyncOperation#REASON_ACCOUNTS_UPDATED}
     *      {@link SyncOperation#REASON_SERVICE_CHANGED}
     *      {@link SyncOperation#REASON_PERIODIC}
     *      {@link SyncOperation#REASON_IS_SYNCABLE}
     *      {@link SyncOperation#REASON_SYNC_AUTO}
     *      {@link SyncOperation#REASON_MASTER_SYNC_AUTO}
     *      {@link SyncOperation#REASON_USER_START}
     * @param requestedAuthority the authority to sync, may be null to indicate all authorities
     * @param extras a Map of SyncAdapter-specific information to control
     *          syncs of a specific provider. Can be null. Is ignored
     *          if the url is null.
     * @param beforeRuntimeMillis milliseconds before runtimeMillis that this sync can run.
     * @param runtimeMillis maximum milliseconds in the future to wait before performing sync.
     * @param onlyThoseWithUnkownSyncableState Only sync authorities that have unknown state.
     */
    public void scheduleSync(Account requestedAccount, int userId, int reason,
            String requestedAuthority, Bundle extras, long beforeRuntimeMillis,
            long runtimeMillis, boolean onlyThoseWithUnkownSyncableState) {
        boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);

        final boolean backgroundDataUsageAllowed = !mBootCompleted ||
                getConnectivityManager().getBackgroundDataSetting();

        if (extras == null) {
            extras = new Bundle();
        }
        if (isLoggable) {
            Log.d(TAG, "one-time sync for: " + requestedAccount + " " + extras.toString() + " "
                    + requestedAuthority);
        }
        Boolean expedited = extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false);
        if (expedited) {
            runtimeMillis = -1; // this means schedule at the front of the queue
        }

        AccountAndUser[] accounts;
        if (requestedAccount != null && userId != UserHandle.USER_ALL) {
            accounts = new AccountAndUser[] { new AccountAndUser(requestedAccount, userId) };
        } else {
            // if the accounts aren't configured yet then we can't support an account-less
            // sync request
            accounts = mRunningAccounts;
            if (accounts.length == 0) {
                if (isLoggable) {
                    Log.v(TAG, "scheduleSync: no accounts configured, dropping");
                }
                return;
            }
        }

        final boolean uploadOnly = extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false);
        final boolean manualSync = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);
        if (manualSync) {
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true);
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true);
        }
        final boolean ignoreSettings =
                extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, false);

        int source;
        if (uploadOnly) {
            source = SyncStorageEngine.SOURCE_LOCAL;
        } else if (manualSync) {
            source = SyncStorageEngine.SOURCE_USER;
        } else if (requestedAuthority == null) {
            source = SyncStorageEngine.SOURCE_POLL;
        } else {
            // this isn't strictly server, since arbitrary callers can (and do) request
            // a non-forced two-way sync on a specific url
            source = SyncStorageEngine.SOURCE_SERVER;
        }

        for (AccountAndUser account : accounts) {
            // Compile a list of authorities that have sync adapters.
            // For each authority sync each account that matches a sync adapter.
            final HashSet<String> syncableAuthorities = new HashSet<String>();
            for (RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapter :
                    mSyncAdapters.getAllServices(account.userId)) {
                syncableAuthorities.add(syncAdapter.type.authority);
            }

            // if the url was specified then replace the list of authorities
            // with just this authority or clear it if this authority isn't
            // syncable
            if (requestedAuthority != null) {
                final boolean hasSyncAdapter = syncableAuthorities.contains(requestedAuthority);
                syncableAuthorities.clear();
                if (hasSyncAdapter) syncableAuthorities.add(requestedAuthority);
            }

            for (String authority : syncableAuthorities) {
                int isSyncable = getIsSyncable(account.account, account.userId,
                        authority);
                if (isSyncable == 0) {
                    continue;
                }
                final RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo;
                syncAdapterInfo = mSyncAdapters.getServiceInfo(
                        SyncAdapterType.newKey(authority, account.account.type), account.userId);
                if (syncAdapterInfo == null) {
                    continue;
                }
                final boolean allowParallelSyncs = syncAdapterInfo.type.allowParallelSyncs();
                final boolean isAlwaysSyncable = syncAdapterInfo.type.isAlwaysSyncable();
                if (isSyncable < 0 && isAlwaysSyncable) {
                    mSyncStorageEngine.setIsSyncable(account.account, account.userId, authority, 1);
                    isSyncable = 1;
                }
                if (onlyThoseWithUnkownSyncableState && isSyncable >= 0) {
                    continue;
                }
                if (!syncAdapterInfo.type.supportsUploading() && uploadOnly) {
                    continue;
                }

                // always allow if the isSyncable state is unknown
                boolean syncAllowed =
                        (isSyncable < 0)
                        || ignoreSettings
                        || (backgroundDataUsageAllowed
                                && mSyncStorageEngine.getMasterSyncAutomatically(account.userId)
                                && mSyncStorageEngine.getSyncAutomatically(account.account,
                                        account.userId, authority));
                if (!syncAllowed) {
                    if (isLoggable) {
                        Log.d(TAG, "scheduleSync: sync of " + account + ", " + authority
                                + " is not allowed, dropping request");
                    }
                    continue;
                }

                Pair<Long, Long> backoff = mSyncStorageEngine
                        .getBackoff(account.account, account.userId, authority);
                long delayUntil = mSyncStorageEngine.getDelayUntilTime(account.account,
                        account.userId, authority);
                final long backoffTime = backoff != null ? backoff.first : 0;
                if (isSyncable < 0) {
                    // Initialisation sync.
                    Bundle newExtras = new Bundle();
                    newExtras.putBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, true);
                    if (isLoggable) {
                        Log.v(TAG, "schedule initialisation Sync:"
                                + ", delay until " + delayUntil
                                + ", run by " + 0
                                + ", source " + source
                                + ", account " + account
                                + ", authority " + authority
                                + ", extras " + newExtras);
                    }
                    scheduleSyncOperation(
                            new SyncOperation(account.account, account.userId, reason, source,
                                    authority, newExtras, 0 /* immediate */, 0 /* No flex time*/,
                                    backoffTime, delayUntil, allowParallelSyncs));
                }
                if (!onlyThoseWithUnkownSyncableState) {
                    if (isLoggable) {
                        Log.v(TAG, "scheduleSync:"
                                + " delay until " + delayUntil
                                + " run by " + runtimeMillis
                                + " flex " + beforeRuntimeMillis
                                + ", source " + source
                                + ", account " + account
                                + ", authority " + authority
                                + ", extras " + extras);
                    }
                    scheduleSyncOperation(
                            new SyncOperation(account.account, account.userId, reason, source,
                                    authority, extras, runtimeMillis, beforeRuntimeMillis,
                                    backoffTime, delayUntil, allowParallelSyncs));
                }
            }
        }
    }

    /**
     * Schedule sync based on local changes to a provider. Occurs within interval
     * [LOCAL_SYNC_DELAY, 2*LOCAL_SYNC_DELAY].
     */
    public void scheduleLocalSync(Account account, int userId, int reason, String authority) {
        final Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true);
        scheduleSync(account, userId, reason, authority, extras,
                LOCAL_SYNC_DELAY /* earliest run time */,
                2 * LOCAL_SYNC_DELAY /* latest sync time. */,
                false /* onlyThoseWithUnkownSyncableState */);
    }

    public SyncAdapterType[] getSyncAdapterTypes(int userId) {
        final Collection<RegisteredServicesCache.ServiceInfo<SyncAdapterType>> serviceInfos;
        serviceInfos = mSyncAdapters.getAllServices(userId);
        SyncAdapterType[] types = new SyncAdapterType[serviceInfos.size()];
        int i = 0;
        for (RegisteredServicesCache.ServiceInfo<SyncAdapterType> serviceInfo : serviceInfos) {
            types[i] = serviceInfo.type;
            ++i;
        }
        return types;
    }

    private void sendSyncAlarmMessage() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "sending MESSAGE_SYNC_ALARM");
        mSyncHandler.sendEmptyMessage(SyncHandler.MESSAGE_SYNC_ALARM);
    }

    private void sendCheckAlarmsMessage() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "sending MESSAGE_CHECK_ALARMS");
        mSyncHandler.removeMessages(SyncHandler.MESSAGE_CHECK_ALARMS);
        mSyncHandler.sendEmptyMessage(SyncHandler.MESSAGE_CHECK_ALARMS);
    }

    private void sendSyncFinishedOrCanceledMessage(ActiveSyncContext syncContext,
            SyncResult syncResult) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "sending MESSAGE_SYNC_FINISHED");
        Message msg = mSyncHandler.obtainMessage();
        msg.what = SyncHandler.MESSAGE_SYNC_FINISHED;
        msg.obj = new SyncHandlerMessagePayload(syncContext, syncResult);
        mSyncHandler.sendMessage(msg);
    }

    private void sendCancelSyncsMessage(final Account account, final int userId,
            final String authority) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "sending MESSAGE_CANCEL");
        Message msg = mSyncHandler.obtainMessage();
        msg.what = SyncHandler.MESSAGE_CANCEL;
        msg.obj = Pair.create(account, authority);
        msg.arg1 = userId;
        mSyncHandler.sendMessage(msg);
    }

    class SyncHandlerMessagePayload {
        public final ActiveSyncContext activeSyncContext;
        public final SyncResult syncResult;

        SyncHandlerMessagePayload(ActiveSyncContext syncContext, SyncResult syncResult) {
            this.activeSyncContext = syncContext;
            this.syncResult = syncResult;
        }
    }

    class SyncAlarmIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            mHandleAlarmWakeLock.acquire();
            sendSyncAlarmMessage();
        }
    }

    private void clearBackoffSetting(SyncOperation op) {
        mSyncStorageEngine.setBackoff(op.account, op.userId, op.authority,
                SyncStorageEngine.NOT_IN_BACKOFF_MODE, SyncStorageEngine.NOT_IN_BACKOFF_MODE);
        synchronized (mSyncQueue) {
            mSyncQueue.onBackoffChanged(op.account, op.userId, op.authority, 0);
        }
    }

    private void increaseBackoffSetting(SyncOperation op) {
        // TODO: Use this function to align it to an already scheduled sync
        //       operation in the specified window
        final long now = SystemClock.elapsedRealtime();

        final Pair<Long, Long> previousSettings =
                mSyncStorageEngine.getBackoff(op.account, op.userId, op.authority);
        long newDelayInMs = -1;
        if (previousSettings != null) {
            // don't increase backoff before current backoff is expired. This will happen for op's
            // with ignoreBackoff set.
            if (now < previousSettings.first) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Still in backoff, do not increase it. "
                        + "Remaining: " + ((previousSettings.first - now) / 1000) + " seconds.");
                }
                return;
            }
            // Subsequent delays are the double of the previous delay
            newDelayInMs = previousSettings.second * 2;
        }
        if (newDelayInMs <= 0) {
            // The initial delay is the jitterized INITIAL_SYNC_RETRY_TIME_IN_MS
            newDelayInMs = jitterize(INITIAL_SYNC_RETRY_TIME_IN_MS,
                    (long)(INITIAL_SYNC_RETRY_TIME_IN_MS * 1.1));
        }

        // Cap the delay
        long maxSyncRetryTimeInSeconds = Settings.Global.getLong(mContext.getContentResolver(),
                Settings.Global.SYNC_MAX_RETRY_DELAY_IN_SECONDS,
                DEFAULT_MAX_SYNC_RETRY_TIME_IN_SECONDS);
        if (newDelayInMs > maxSyncRetryTimeInSeconds * 1000) {
            newDelayInMs = maxSyncRetryTimeInSeconds * 1000;
        }

        final long backoff = now + newDelayInMs;

        mSyncStorageEngine.setBackoff(op.account, op.userId, op.authority,
                backoff, newDelayInMs);

        op.backoff = backoff;
        op.updateEffectiveRunTime();

        synchronized (mSyncQueue) {
            mSyncQueue.onBackoffChanged(op.account, op.userId, op.authority, backoff);
        }
    }

    private void setDelayUntilTime(SyncOperation op, long delayUntilSeconds) {
        final long delayUntil = delayUntilSeconds * 1000;
        final long absoluteNow = System.currentTimeMillis();
        long newDelayUntilTime;
        if (delayUntil > absoluteNow) {
            newDelayUntilTime = SystemClock.elapsedRealtime() + (delayUntil - absoluteNow);
        } else {
            newDelayUntilTime = 0;
        }
        mSyncStorageEngine
                .setDelayUntilTime(op.account, op.userId, op.authority, newDelayUntilTime);
        synchronized (mSyncQueue) {
            mSyncQueue.onDelayUntilTimeChanged(op.account, op.authority, newDelayUntilTime);
        }
    }

    /**
     * Cancel the active sync if it matches the authority and account.
     * @param account limit the cancelations to syncs with this account, if non-null
     * @param authority limit the cancelations to syncs with this authority, if non-null
     */
    public void cancelActiveSync(Account account, int userId, String authority) {
        sendCancelSyncsMessage(account, userId, authority);
    }

    /**
     * Create and schedule a SyncOperation.
     *
     * @param syncOperation the SyncOperation to schedule
     */
    public void scheduleSyncOperation(SyncOperation syncOperation) {
        boolean queueChanged;
        synchronized (mSyncQueue) {
            queueChanged = mSyncQueue.add(syncOperation);
        }

        if (queueChanged) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "scheduleSyncOperation: enqueued " + syncOperation);
            }
            sendCheckAlarmsMessage();
        } else {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "scheduleSyncOperation: dropping duplicate sync operation "
                        + syncOperation);
            }
        }
    }

    /**
     * Remove scheduled sync operations.
     * @param account limit the removals to operations with this account, if non-null
     * @param authority limit the removals to operations with this authority, if non-null
     */
    public void clearScheduledSyncOperations(Account account, int userId, String authority) {
        synchronized (mSyncQueue) {
            mSyncQueue.remove(account, userId, authority);
        }
        mSyncStorageEngine.setBackoff(account, userId, authority,
                SyncStorageEngine.NOT_IN_BACKOFF_MODE, SyncStorageEngine.NOT_IN_BACKOFF_MODE);
    }

    void maybeRescheduleSync(SyncResult syncResult, SyncOperation operation) {
        boolean isLoggable = Log.isLoggable(TAG, Log.DEBUG);
        if (isLoggable) {
            Log.d(TAG, "encountered error(s) during the sync: " + syncResult + ", " + operation);
        }

        operation = new SyncOperation(operation);

        // The SYNC_EXTRAS_IGNORE_BACKOFF only applies to the first attempt to sync a given
        // request. Retries of the request will always honor the backoff, so clear the
        // flag in case we retry this request.
        if (operation.extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, false)) {
            operation.extras.remove(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF);
        }

        // If this sync aborted because the internal sync loop retried too many times then
        //   don't reschedule. Otherwise we risk getting into a retry loop.
        // If the operation succeeded to some extent then retry immediately.
        // If this was a two-way sync then retry soft errors with an exponential backoff.
        // If this was an upward sync then schedule a two-way sync immediately.
        // Otherwise do not reschedule.
        if (operation.extras.getBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, false)) {
            Log.d(TAG, "not retrying sync operation because SYNC_EXTRAS_DO_NOT_RETRY was specified "
                    + operation);
        } else if (operation.extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false)
                && !syncResult.syncAlreadyInProgress) {
            operation.extras.remove(ContentResolver.SYNC_EXTRAS_UPLOAD);
            Log.d(TAG, "retrying sync operation as a two-way sync because an upload-only sync "
                    + "encountered an error: " + operation);
            scheduleSyncOperation(operation);
        } else if (syncResult.tooManyRetries) {
            Log.d(TAG, "not retrying sync operation because it retried too many times: "
                    + operation);
        } else if (syncResult.madeSomeProgress()) {
            if (isLoggable) {
                Log.d(TAG, "retrying sync operation because even though it had an error "
                        + "it achieved some success");
            }
            scheduleSyncOperation(operation);
        } else if (syncResult.syncAlreadyInProgress) {
            if (isLoggable) {
                Log.d(TAG, "retrying sync operation that failed because there was already a "
                        + "sync in progress: " + operation);
            }
            scheduleSyncOperation(
                new SyncOperation(
                    operation.account, operation.userId,
                    operation.reason,
                    operation.syncSource,
                    operation.authority, operation.extras,
                    DELAY_RETRY_SYNC_IN_PROGRESS_IN_SECONDS * 1000, operation.flexTime,
                    operation.backoff, operation.delayUntil, operation.allowParallelSyncs));
        } else if (syncResult.hasSoftError()) {
            if (isLoggable) {
                Log.d(TAG, "retrying sync operation because it encountered a soft error: "
                        + operation);
            }
            scheduleSyncOperation(operation);
        } else {
            Log.d(TAG, "not retrying sync operation because the error is a hard error: "
                    + operation);
        }
    }

    private void onUserStarting(int userId) {
        // Make sure that accounts we're about to use are valid
        AccountManagerService.getSingleton().validateAccounts(userId);

        mSyncAdapters.invalidateCache(userId);

        updateRunningAccounts();

        synchronized (mSyncQueue) {
            mSyncQueue.addPendingOperations(userId);
        }

        // Schedule sync for any accounts under started user
        final Account[] accounts = AccountManagerService.getSingleton().getAccounts(userId);
        for (Account account : accounts) {
            scheduleSync(account, userId, SyncOperation.REASON_USER_START, null, null,
                    0 /* no delay */, 0 /* No flex */,
                    true /* onlyThoseWithUnknownSyncableState */);
        }

        sendCheckAlarmsMessage();
    }

    private void onUserStopping(int userId) {
        updateRunningAccounts();

        cancelActiveSync(
                null /* any account */,
                userId,
                null /* any authority */);
    }

    private void onUserRemoved(int userId) {
        updateRunningAccounts();

        // Clean up the storage engine database
        mSyncStorageEngine.doDatabaseCleanup(new Account[0], userId);
        synchronized (mSyncQueue) {
            mSyncQueue.removeUser(userId);
        }
    }

    private Context getUiContext() {
        if (mUiContext == null) {
            mUiContext = ThemeUtils.createUiContext(mContext);
        }
        return mUiContext != null ? mUiContext : mContext;
    }

    /**
     * @hide
     */
    class ActiveSyncContext extends ISyncContext.Stub
            implements ServiceConnection, IBinder.DeathRecipient {
        final SyncOperation mSyncOperation;
        final long mHistoryRowId;
        ISyncAdapter mSyncAdapter;
        final long mStartTime;
        long mTimeoutStartTime;
        boolean mBound;
        final PowerManager.WakeLock mSyncWakeLock;
        final int mSyncAdapterUid;
        SyncInfo mSyncInfo;
        boolean mIsLinkedToDeath = false;

        /**
         * Create an ActiveSyncContext for an impending sync and grab the wakelock for that
         * sync adapter. Since this grabs the wakelock you need to be sure to call
         * close() when you are done with this ActiveSyncContext, whether the sync succeeded
         * or not.
         * @param syncOperation the SyncOperation we are about to sync
         * @param historyRowId the row in which to record the history info for this sync
         * @param syncAdapterUid the UID of the application that contains the sync adapter
         * for this sync. This is used to attribute the wakelock hold to that application.
         */
        public ActiveSyncContext(SyncOperation syncOperation, long historyRowId,
                int syncAdapterUid) {
            super();
            mSyncAdapterUid = syncAdapterUid;
            mSyncOperation = syncOperation;
            mHistoryRowId = historyRowId;
            mSyncAdapter = null;
            mStartTime = SystemClock.elapsedRealtime();
            mTimeoutStartTime = mStartTime;
            mSyncWakeLock = mSyncHandler.getSyncWakeLock(
                    mSyncOperation.account, mSyncOperation.authority);
            mSyncWakeLock.setWorkSource(new WorkSource(syncAdapterUid));
            mSyncWakeLock.acquire();
        }

        public void sendHeartbeat() {
            // heartbeats are no longer used
        }

        public void onFinished(SyncResult result) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "onFinished: " + this);
            // include "this" in the message so that the handler can ignore it if this
            // ActiveSyncContext is no longer the mActiveSyncContext at message handling
            // time
            sendSyncFinishedOrCanceledMessage(this, result);
        }

        public void toString(StringBuilder sb) {
            sb.append("startTime ").append(mStartTime)
                    .append(", mTimeoutStartTime ").append(mTimeoutStartTime)
                    .append(", mHistoryRowId ").append(mHistoryRowId)
                    .append(", syncOperation ").append(mSyncOperation);
        }

        public void onServiceConnected(ComponentName name, IBinder service) {
            Message msg = mSyncHandler.obtainMessage();
            msg.what = SyncHandler.MESSAGE_SERVICE_CONNECTED;
            msg.obj = new ServiceConnectionData(this, ISyncAdapter.Stub.asInterface(service));
            mSyncHandler.sendMessage(msg);
        }

        public void onServiceDisconnected(ComponentName name) {
            Message msg = mSyncHandler.obtainMessage();
            msg.what = SyncHandler.MESSAGE_SERVICE_DISCONNECTED;
            msg.obj = new ServiceConnectionData(this, null);
            mSyncHandler.sendMessage(msg);
        }

        boolean bindToSyncAdapter(RegisteredServicesCache.ServiceInfo info, int userId) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "bindToSyncAdapter: " + info.componentName + ", connection " + this);
            }
            Intent intent = new Intent();
            intent.setAction("android.content.SyncAdapter");
            intent.setComponent(info.componentName);
            intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                    com.android.internal.R.string.sync_binding_label);
            intent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivityAsUser(
                    mContext, 0, new Intent(Settings.ACTION_SYNC_SETTINGS), 0,
                    null, new UserHandle(userId)));
            mBound = true;
            final boolean bindResult = mContext.bindServiceAsUser(intent, this,
                    Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND
                    | Context.BIND_ALLOW_OOM_MANAGEMENT,
                    new UserHandle(mSyncOperation.userId));
            if (!bindResult) {
                mBound = false;
            }
            return bindResult;
        }

        /**
         * Performs the required cleanup, which is the releasing of the wakelock and
         * unbinding from the sync adapter (if actually bound).
         */
        protected void close() {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "unBindFromSyncAdapter: connection " + this);
            }
            if (mBound) {
                mBound = false;
                mContext.unbindService(this);
            }
            mSyncWakeLock.release();
            mSyncWakeLock.setWorkSource(null);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            toString(sb);
            return sb.toString();
        }

        @Override
        public void binderDied() {
            sendSyncFinishedOrCanceledMessage(this, null);
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        dumpSyncState(ipw);
        dumpSyncHistory(ipw);
        dumpSyncAdapters(ipw);
    }

    static String formatTime(long time) {
        Time tobj = new Time();
        tobj.set(time);
        return tobj.format("%Y-%m-%d %H:%M:%S");
    }

    protected void dumpSyncState(PrintWriter pw) {
        pw.print("data connected: "); pw.println(mDataConnectionIsConnected);
        pw.print("auto sync: ");
        List<UserInfo> users = getAllUsers();
        if (users != null) {
            for (UserInfo user : users) {
                pw.print("u" + user.id + "="
                        + mSyncStorageEngine.getMasterSyncAutomatically(user.id) + " ");
            }
            pw.println();
        }
        pw.print("memory low: "); pw.println(mStorageIsLow);

        final AccountAndUser[] accounts = AccountManagerService.getSingleton().getAllAccounts();

        pw.print("accounts: ");
        if (accounts != INITIAL_ACCOUNTS_ARRAY) {
            pw.println(accounts.length);
        } else {
            pw.println("not known yet");
        }
        final long now = SystemClock.elapsedRealtime();
        pw.print("now: "); pw.print(now);
        pw.println(" (" + formatTime(System.currentTimeMillis()) + ")");
        pw.print("offset: "); pw.print(DateUtils.formatElapsedTime(mSyncRandomOffsetMillis/1000));
        pw.println(" (HH:MM:SS)");
        pw.print("uptime: "); pw.print(DateUtils.formatElapsedTime(now/1000));
                pw.println(" (HH:MM:SS)");
        pw.print("time spent syncing: ");
                pw.print(DateUtils.formatElapsedTime(
                        mSyncHandler.mSyncTimeTracker.timeSpentSyncing() / 1000));
                pw.print(" (HH:MM:SS), sync ");
                pw.print(mSyncHandler.mSyncTimeTracker.mLastWasSyncing ? "" : "not ");
                pw.println("in progress");
        if (mSyncHandler.mAlarmScheduleTime != null) {
            pw.print("next alarm time: "); pw.print(mSyncHandler.mAlarmScheduleTime);
                    pw.print(" (");
                    pw.print(DateUtils.formatElapsedTime((mSyncHandler.mAlarmScheduleTime-now)/1000));
                    pw.println(" (HH:MM:SS) from now)");
        } else {
            pw.println("no alarm is scheduled (there had better not be any pending syncs)");
        }

        pw.print("notification info: ");
        final StringBuilder sb = new StringBuilder();
        mSyncHandler.mSyncNotificationInfo.toString(sb);
        pw.println(sb.toString());

        pw.println();
        pw.println("Active Syncs: " + mActiveSyncContexts.size());
        final PackageManager pm = mContext.getPackageManager();
        for (SyncManager.ActiveSyncContext activeSyncContext : mActiveSyncContexts) {
            final long durationInSeconds = (now - activeSyncContext.mStartTime) / 1000;
            pw.print("  ");
            pw.print(DateUtils.formatElapsedTime(durationInSeconds));
            pw.print(" - ");
            pw.print(activeSyncContext.mSyncOperation.dump(pm, false));
            pw.println();
        }

        synchronized (mSyncQueue) {
            sb.setLength(0);
            mSyncQueue.dump(sb);
            // Dump Pending Operations.
            getSyncStorageEngine().dumpPendingOperations(sb);
        }

        pw.println();
        pw.print(sb.toString());

        // join the installed sync adapter with the accounts list and emit for everything
        pw.println();
        pw.println("Sync Status");
        for (AccountAndUser account : accounts) {
            pw.printf("Account %s u%d %s\n",
                    account.account.name, account.userId, account.account.type);

            pw.println("=======================================================================");
            final PrintTable table = new PrintTable(13);
            table.set(0, 0,
                    "Authority", // 0
                    "Syncable",  // 1
                    "Enabled",   // 2
                    "Delay",     // 3
                    "Loc",       // 4
                    "Poll",      // 5
                    "Per",       // 6
                    "Serv",      // 7
                    "User",      // 8
                    "Tot",       // 9
                    "Time",      // 10
                    "Last Sync", // 11
                    "Periodic"   // 12
            );

            final List<RegisteredServicesCache.ServiceInfo<SyncAdapterType>> sorted =
                    Lists.newArrayList();
            sorted.addAll(mSyncAdapters.getAllServices(account.userId));
            Collections.sort(sorted,
                    new Comparator<RegisteredServicesCache.ServiceInfo<SyncAdapterType>>() {
                @Override
                public int compare(RegisteredServicesCache.ServiceInfo<SyncAdapterType> lhs,
                        RegisteredServicesCache.ServiceInfo<SyncAdapterType> rhs) {
                    return lhs.type.authority.compareTo(rhs.type.authority);
                }
            });
            for (RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterType : sorted) {
                if (!syncAdapterType.type.accountType.equals(account.account.type)) {
                    continue;
                }
                int row = table.getNumRows();
                Pair<AuthorityInfo, SyncStatusInfo> syncAuthoritySyncStatus = 
                        mSyncStorageEngine.getCopyOfAuthorityWithSyncStatus(
                                account.account, account.userId, syncAdapterType.type.authority);
                SyncStorageEngine.AuthorityInfo settings = syncAuthoritySyncStatus.first;
                SyncStatusInfo status = syncAuthoritySyncStatus.second;

                String authority = settings.authority;
                if (authority.length() > 50) {
                    authority = authority.substring(authority.length() - 50);
                }
                table.set(row, 0, authority, settings.syncable, settings.enabled);
                table.set(row, 4,
                        status.numSourceLocal,
                        status.numSourcePoll,
                        status.numSourcePeriodic,
                        status.numSourceServer,
                        status.numSourceUser,
                        status.numSyncs,
                        DateUtils.formatElapsedTime(status.totalElapsedTime / 1000));


                for (int i = 0; i < settings.periodicSyncs.size(); i++) {
                    final PeriodicSync sync = settings.periodicSyncs.get(i);
                    final String period =
                            String.format("[p:%d s, f: %d s]", sync.period, sync.flexTime);
                    final String extras =
                            sync.extras.size() > 0 ?
                                    sync.extras.toString() : "Bundle[]";
                    final String next = "Next sync: " + formatTime(status.getPeriodicSyncTime(i)
                            + sync.period * 1000);
                    table.set(row + i * 2, 12, period + " " + extras);
                    table.set(row + i * 2 + 1, 12, next);
                }

                int row1 = row;
                if (settings.delayUntil > now) {
                    table.set(row1++, 12, "D: " + (settings.delayUntil - now) / 1000);
                    if (settings.backoffTime > now) {
                        table.set(row1++, 12, "B: " + (settings.backoffTime - now) / 1000);
                        table.set(row1++, 12, settings.backoffDelay / 1000);
                    }
                }

                if (status.lastSuccessTime != 0) {
                    table.set(row1++, 11, SyncStorageEngine.SOURCES[status.lastSuccessSource]
                            + " " + "SUCCESS");
                    table.set(row1++, 11, formatTime(status.lastSuccessTime));
                }
                if (status.lastFailureTime != 0) {
                    table.set(row1++, 11, SyncStorageEngine.SOURCES[status.lastFailureSource]
                            + " " + "FAILURE");
                    table.set(row1++, 11, formatTime(status.lastFailureTime));
                    //noinspection UnusedAssignment
                    table.set(row1++, 11, status.lastFailureMesg);
                }
            }
            table.writeTo(pw);
        }
    }

    private String getLastFailureMessage(int code) {
        switch (code) {
            case ContentResolver.SYNC_ERROR_SYNC_ALREADY_IN_PROGRESS:
                return "sync already in progress";

            case ContentResolver.SYNC_ERROR_AUTHENTICATION:
                return "authentication error";

            case ContentResolver.SYNC_ERROR_IO:
                return "I/O error";

            case ContentResolver.SYNC_ERROR_PARSE:
                return "parse error";

            case ContentResolver.SYNC_ERROR_CONFLICT:
                return "conflict error";

            case ContentResolver.SYNC_ERROR_TOO_MANY_DELETIONS:
                return "too many deletions error";

            case ContentResolver.SYNC_ERROR_TOO_MANY_RETRIES:
                return "too many retries error";

            case ContentResolver.SYNC_ERROR_INTERNAL:
                return "internal error";

            default:
                return "unknown";
        }
    }

    private void dumpTimeSec(PrintWriter pw, long time) {
        pw.print(time/1000); pw.print('.'); pw.print((time/100)%10);
        pw.print('s');
    }

    private void dumpDayStatistic(PrintWriter pw, SyncStorageEngine.DayStats ds) {
        pw.print("Success ("); pw.print(ds.successCount);
        if (ds.successCount > 0) {
            pw.print(" for "); dumpTimeSec(pw, ds.successTime);
            pw.print(" avg="); dumpTimeSec(pw, ds.successTime/ds.successCount);
        }
        pw.print(") Failure ("); pw.print(ds.failureCount);
        if (ds.failureCount > 0) {
            pw.print(" for "); dumpTimeSec(pw, ds.failureTime);
            pw.print(" avg="); dumpTimeSec(pw, ds.failureTime/ds.failureCount);
        }
        pw.println(")");
    }

    protected void dumpSyncHistory(PrintWriter pw) {
        dumpRecentHistory(pw);
        dumpDayStatistics(pw);
    }

    private void dumpRecentHistory(PrintWriter pw) {
        final ArrayList<SyncStorageEngine.SyncHistoryItem> items
                = mSyncStorageEngine.getSyncHistory();
        if (items != null && items.size() > 0) {
            final Map<String, AuthoritySyncStats> authorityMap = Maps.newHashMap();
            long totalElapsedTime = 0;
            long totalTimes = 0;
            final int N = items.size();

            int maxAuthority = 0;
            int maxAccount = 0;
            for (SyncStorageEngine.SyncHistoryItem item : items) {
                SyncStorageEngine.AuthorityInfo authority
                        = mSyncStorageEngine.getAuthority(item.authorityId);
                final String authorityName;
                final String accountKey;
                if (authority != null) {
                    authorityName = authority.authority;
                    accountKey = authority.account.name + "/" + authority.account.type
                            + " u" + authority.userId;
                } else {
                    authorityName = "Unknown";
                    accountKey = "Unknown";
                }

                int length = authorityName.length();
                if (length > maxAuthority) {
                    maxAuthority = length;
                }
                length = accountKey.length();
                if (length > maxAccount) {
                    maxAccount = length;
                }

                final long elapsedTime = item.elapsedTime;
                totalElapsedTime += elapsedTime;
                totalTimes++;
                AuthoritySyncStats authoritySyncStats = authorityMap.get(authorityName);
                if (authoritySyncStats == null) {
                    authoritySyncStats = new AuthoritySyncStats(authorityName);
                    authorityMap.put(authorityName, authoritySyncStats);
                }
                authoritySyncStats.elapsedTime += elapsedTime;
                authoritySyncStats.times++;
                final Map<String, AccountSyncStats> accountMap = authoritySyncStats.accountMap;
                AccountSyncStats accountSyncStats = accountMap.get(accountKey);
                if (accountSyncStats == null) {
                    accountSyncStats = new AccountSyncStats(accountKey);
                    accountMap.put(accountKey, accountSyncStats);
                }
                accountSyncStats.elapsedTime += elapsedTime;
                accountSyncStats.times++;

            }

            if (totalElapsedTime > 0) {
                pw.println();
                pw.printf("Detailed Statistics (Recent history):  "
                        + "%d (# of times) %ds (sync time)\n",
                        totalTimes, totalElapsedTime / 1000);

                final List<AuthoritySyncStats> sortedAuthorities =
                        new ArrayList<AuthoritySyncStats>(authorityMap.values());
                Collections.sort(sortedAuthorities, new Comparator<AuthoritySyncStats>() {
                    @Override
                    public int compare(AuthoritySyncStats lhs, AuthoritySyncStats rhs) {
                        // reverse order
                        int compare = Integer.compare(rhs.times, lhs.times);
                        if (compare == 0) {
                            compare = Long.compare(rhs.elapsedTime, lhs.elapsedTime);
                        }
                        return compare;
                    }
                });

                final int maxLength = Math.max(maxAuthority, maxAccount + 3);
                final int padLength = 2 + 2 + maxLength + 2 + 10 + 11;
                final char chars[] = new char[padLength];
                Arrays.fill(chars, '-');
                final String separator = new String(chars);

                final String authorityFormat =
                        String.format("  %%-%ds: %%-9s  %%-11s\n", maxLength + 2);
                final String accountFormat =
                        String.format("    %%-%ds:   %%-9s  %%-11s\n", maxLength);

                pw.println(separator);
                for (AuthoritySyncStats authoritySyncStats : sortedAuthorities) {
                    String name = authoritySyncStats.name;
                    long elapsedTime;
                    int times;
                    String timeStr;
                    String timesStr;

                    elapsedTime = authoritySyncStats.elapsedTime;
                    times = authoritySyncStats.times;
                    timeStr = String.format("%ds/%d%%",
                            elapsedTime / 1000,
                            elapsedTime * 100 / totalElapsedTime);
                    timesStr = String.format("%d/%d%%",
                            times,
                            times * 100 / totalTimes);
                    pw.printf(authorityFormat, name, timesStr, timeStr);

                    final List<AccountSyncStats> sortedAccounts =
                            new ArrayList<AccountSyncStats>(
                                    authoritySyncStats.accountMap.values());
                    Collections.sort(sortedAccounts, new Comparator<AccountSyncStats>() {
                        @Override
                        public int compare(AccountSyncStats lhs, AccountSyncStats rhs) {
                            // reverse order
                            int compare = Integer.compare(rhs.times, lhs.times);
                            if (compare == 0) {
                                compare = Long.compare(rhs.elapsedTime, lhs.elapsedTime);
                            }
                            return compare;
                        }
                    });
                    for (AccountSyncStats stats: sortedAccounts) {
                        elapsedTime = stats.elapsedTime;
                        times = stats.times;
                        timeStr = String.format("%ds/%d%%",
                                elapsedTime / 1000,
                                elapsedTime * 100 / totalElapsedTime);
                        timesStr = String.format("%d/%d%%",
                                times,
                                times * 100 / totalTimes);
                        pw.printf(accountFormat, stats.name, timesStr, timeStr);
                    }
                    pw.println(separator);
                }
            }

            pw.println();
            pw.println("Recent Sync History");
            final String format = "  %-" + maxAccount + "s  %-" + maxAuthority + "s %s\n";
            final Map<String, Long> lastTimeMap = Maps.newHashMap();
            final PackageManager pm = mContext.getPackageManager();
            for (int i = 0; i < N; i++) {
                SyncStorageEngine.SyncHistoryItem item = items.get(i);
                SyncStorageEngine.AuthorityInfo authority
                        = mSyncStorageEngine.getAuthority(item.authorityId);
                final String authorityName;
                final String accountKey;
                if (authority != null) {
                    authorityName = authority.authority;
                    accountKey = authority.account.name + "/" + authority.account.type
                            + " u" + authority.userId;
                } else {
                    authorityName = "Unknown";
                    accountKey = "Unknown";
                }
                final long elapsedTime = item.elapsedTime;
                final Time time = new Time();
                final long eventTime = item.eventTime;
                time.set(eventTime);

                final String key = authorityName + "/" + accountKey;
                final Long lastEventTime = lastTimeMap.get(key);
                final String diffString;
                if (lastEventTime == null) {
                    diffString = "";
                } else {
                    final long diff = (lastEventTime - eventTime) / 1000;
                    if (diff < 60) {
                        diffString = String.valueOf(diff);
                    } else if (diff < 3600) {
                        diffString = String.format("%02d:%02d", diff / 60, diff % 60);
                    } else {
                        final long sec = diff % 3600;
                        diffString = String.format("%02d:%02d:%02d",
                                diff / 3600, sec / 60, sec % 60);
                    }
                }
                lastTimeMap.put(key, eventTime);

                pw.printf("  #%-3d: %s %8s  %5.1fs  %8s",
                        i + 1,
                        formatTime(eventTime),
                        SyncStorageEngine.SOURCES[item.source],
                        ((float) elapsedTime) / 1000,
                        diffString);
                pw.printf(format, accountKey, authorityName,
                        SyncOperation.reasonToString(pm, item.reason));

                if (item.event != SyncStorageEngine.EVENT_STOP
                        || item.upstreamActivity != 0
                        || item.downstreamActivity != 0) {
                    pw.printf("    event=%d upstreamActivity=%d downstreamActivity=%d\n",
                            item.event,
                            item.upstreamActivity,
                            item.downstreamActivity);
                }
                if (item.mesg != null
                        && !SyncStorageEngine.MESG_SUCCESS.equals(item.mesg)) {
                    pw.printf("    mesg=%s\n", item.mesg);
                }
            }
            pw.println();
            pw.println("Recent Sync History Extras");
            for (int i = 0; i < N; i++) {
                final SyncStorageEngine.SyncHistoryItem item = items.get(i);
                final Bundle extras = item.extras;
                if (extras == null || extras.size() == 0) {
                    continue;
                }
                final SyncStorageEngine.AuthorityInfo authority
                        = mSyncStorageEngine.getAuthority(item.authorityId);
                final String authorityName;
                final String accountKey;
                if (authority != null) {
                    authorityName = authority.authority;
                    accountKey = authority.account.name + "/" + authority.account.type
                            + " u" + authority.userId;
                } else {
                    authorityName = "Unknown";
                    accountKey = "Unknown";
                }
                final Time time = new Time();
                final long eventTime = item.eventTime;
                time.set(eventTime);

                pw.printf("  #%-3d: %s %8s ",
                        i + 1,
                        formatTime(eventTime),
                        SyncStorageEngine.SOURCES[item.source]);

                pw.printf(format, accountKey, authorityName, extras);
            }
        }
    }

    private void dumpDayStatistics(PrintWriter pw) {
        SyncStorageEngine.DayStats dses[] = mSyncStorageEngine.getDayStatistics();
        if (dses != null && dses[0] != null) {
            pw.println();
            pw.println("Sync Statistics");
            pw.print("  Today:  "); dumpDayStatistic(pw, dses[0]);
            int today = dses[0].day;
            int i;
            SyncStorageEngine.DayStats ds;

            // Print each day in the current week.
            for (i=1; i<=6 && i < dses.length; i++) {
                ds = dses[i];
                if (ds == null) break;
                int delta = today-ds.day;
                if (delta > 6) break;

                pw.print("  Day-"); pw.print(delta); pw.print(":  ");
                dumpDayStatistic(pw, ds);
            }

            // Aggregate all following days into weeks and print totals.
            int weekDay = today;
            while (i < dses.length) {
                SyncStorageEngine.DayStats aggr = null;
                weekDay -= 7;
                while (i < dses.length) {
                    ds = dses[i];
                    if (ds == null) {
                        i = dses.length;
                        break;
                    }
                    int delta = weekDay-ds.day;
                    if (delta > 6) break;
                    i++;

                    if (aggr == null) {
                        aggr = new SyncStorageEngine.DayStats(weekDay);
                    }
                    aggr.successCount += ds.successCount;
                    aggr.successTime += ds.successTime;
                    aggr.failureCount += ds.failureCount;
                    aggr.failureTime += ds.failureTime;
                }
                if (aggr != null) {
                    pw.print("  Week-"); pw.print((today-weekDay)/7); pw.print(": ");
                    dumpDayStatistic(pw, aggr);
                }
            }
        }
    }

    private void dumpSyncAdapters(IndentingPrintWriter pw) {
        pw.println();
        final List<UserInfo> users = getAllUsers();
        if (users != null) {
            for (UserInfo user : users) {
                pw.println("Sync adapters for " + user + ":");
                pw.increaseIndent();
                for (RegisteredServicesCache.ServiceInfo<?> info :
                        mSyncAdapters.getAllServices(user.id)) {
                    pw.println(info);
                }
                pw.decreaseIndent();
                pw.println();
            }
        }
    }

    private static class AuthoritySyncStats {
        String name;
        long elapsedTime;
        int times;
        Map<String, AccountSyncStats> accountMap = Maps.newHashMap();

        private AuthoritySyncStats(String name) {
            this.name = name;
        }
    }

    private static class AccountSyncStats {
        String name;
        long elapsedTime;
        int times;

        private AccountSyncStats(String name) {
            this.name = name;
        }
    }

    /**
     * A helper object to keep track of the time we have spent syncing since the last boot
     */
    private class SyncTimeTracker {
        /** True if a sync was in progress on the most recent call to update() */
        boolean mLastWasSyncing = false;
        /** Used to track when lastWasSyncing was last set */
        long mWhenSyncStarted = 0;
        /** The cumulative time we have spent syncing */
        private long mTimeSpentSyncing;

        /** Call to let the tracker know that the sync state may have changed */
        public synchronized void update() {
            final boolean isSyncInProgress = !mActiveSyncContexts.isEmpty();
            if (isSyncInProgress == mLastWasSyncing) return;
            final long now = SystemClock.elapsedRealtime();
            if (isSyncInProgress) {
                mWhenSyncStarted = now;
            } else {
                mTimeSpentSyncing += now - mWhenSyncStarted;
            }
            mLastWasSyncing = isSyncInProgress;
        }

        /** Get how long we have been syncing, in ms */
        public synchronized long timeSpentSyncing() {
            if (!mLastWasSyncing) return mTimeSpentSyncing;

            final long now = SystemClock.elapsedRealtime();
            return mTimeSpentSyncing + (now - mWhenSyncStarted);
        }
    }

    class ServiceConnectionData {
        public final ActiveSyncContext activeSyncContext;
        public final ISyncAdapter syncAdapter;
        ServiceConnectionData(ActiveSyncContext activeSyncContext, ISyncAdapter syncAdapter) {
            this.activeSyncContext = activeSyncContext;
            this.syncAdapter = syncAdapter;
        }
    }

    /**
     * Handles SyncOperation Messages that are posted to the associated
     * HandlerThread.
     */
    class SyncHandler extends Handler {
        // Messages that can be sent on mHandler
        private static final int MESSAGE_SYNC_FINISHED = 1;
        private static final int MESSAGE_SYNC_ALARM = 2;
        private static final int MESSAGE_CHECK_ALARMS = 3;
        private static final int MESSAGE_SERVICE_CONNECTED = 4;
        private static final int MESSAGE_SERVICE_DISCONNECTED = 5;
        private static final int MESSAGE_CANCEL = 6;

        public final SyncNotificationInfo mSyncNotificationInfo = new SyncNotificationInfo();
        private Long mAlarmScheduleTime = null;
        public final SyncTimeTracker mSyncTimeTracker = new SyncTimeTracker();
        private final HashMap<Pair<Account, String>, PowerManager.WakeLock> mWakeLocks =
                Maps.newHashMap();
        private List<Message> mBootQueue = new ArrayList<Message>();

        public void onBootCompleted() {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Boot completed, clearing boot queue.");
            }
            doDatabaseCleanup();
            synchronized(this) {
                // Dispatch any stashed messages.
                for (Message message : mBootQueue) {
                    sendMessage(message);
                }
                mBootQueue = null;
                mBootCompleted = true;
            }
        }

        private PowerManager.WakeLock getSyncWakeLock(Account account, String authority) {
            final Pair<Account, String> wakeLockKey = Pair.create(account, authority);
            PowerManager.WakeLock wakeLock = mWakeLocks.get(wakeLockKey);
            if (wakeLock == null) {
                final String name = SYNC_WAKE_LOCK_PREFIX + "/" + authority + "/" + account.type
                        + "/" + account.name;
                wakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, name);
                wakeLock.setReferenceCounted(false);
                mWakeLocks.put(wakeLockKey, wakeLock);
            }
            return wakeLock;
        }

        /**
         * Stash any messages that come to the handler before boot is complete.
         * {@link #onBootCompleted()} will disable this and dispatch all the messages collected.
         * @param msg Message to dispatch at a later point.
         * @return true if a message was enqueued, false otherwise. This is to avoid losing the
         * message if we manage to acquire the lock but by the time we do boot has completed.
         */
        private boolean tryEnqueueMessageUntilReadyToRun(Message msg) {
            synchronized (this) {
                if (!mBootCompleted) {
                    // Need to copy the message bc looper will recycle it.
                    mBootQueue.add(Message.obtain(msg));
                    return true;
                }
                return false;
            }
        }

        /**
         * Used to keep track of whether a sync notification is active and who it is for.
         */
        class SyncNotificationInfo {
            // true iff the notification manager has been asked to send the notification
            public boolean isActive = false;

            // Set when we transition from not running a sync to running a sync, and cleared on
            // the opposite transition.
            public Long startTime = null;

            public void toString(StringBuilder sb) {
                sb.append("isActive ").append(isActive).append(", startTime ").append(startTime);
            }

            @Override
            public String toString() {
                StringBuilder sb = new StringBuilder();
                toString(sb);
                return sb.toString();
            }
        }

        public SyncHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (tryEnqueueMessageUntilReadyToRun(msg)) {
                return;
            }

            long earliestFuturePollTime = Long.MAX_VALUE;
            long nextPendingSyncTime = Long.MAX_VALUE;
            // Setting the value here instead of a method because we want the dumpsys logs
            // to have the most recent value used.
            try {
                mDataConnectionIsConnected = readDataConnectionState();
                mSyncManagerWakeLock.acquire();
                // Always do this first so that we be sure that any periodic syncs that
                // are ready to run have been converted into pending syncs. This allows the
                // logic that considers the next steps to take based on the set of pending syncs
                // to also take into account the periodic syncs.
                earliestFuturePollTime = scheduleReadyPeriodicSyncs();
                switch (msg.what) {
                    case SyncHandler.MESSAGE_CANCEL: {
                        Pair<Account, String> payload = (Pair<Account, String>) msg.obj;
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.d(TAG, "handleSyncHandlerMessage: MESSAGE_SERVICE_CANCEL: "
                                    + payload.first + ", " + payload.second);
                        }
                        cancelActiveSyncLocked(payload.first, msg.arg1, payload.second);
                        nextPendingSyncTime = maybeStartNextSyncLocked();
                        break;
                    }

                    case SyncHandler.MESSAGE_SYNC_FINISHED:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "handleSyncHandlerMessage: MESSAGE_SYNC_FINISHED");
                        }
                        SyncHandlerMessagePayload payload = (SyncHandlerMessagePayload)msg.obj;
                        if (!isSyncStillActive(payload.activeSyncContext)) {
                            Log.d(TAG, "handleSyncHandlerMessage: dropping since the "
                                    + "sync is no longer active: "
                                    + payload.activeSyncContext);
                            break;
                        }
                        runSyncFinishedOrCanceledLocked(payload.syncResult, payload.activeSyncContext);

                        // since a sync just finished check if it is time to start a new sync
                        nextPendingSyncTime = maybeStartNextSyncLocked();
                        break;

                    case SyncHandler.MESSAGE_SERVICE_CONNECTED: {
                        ServiceConnectionData msgData = (ServiceConnectionData)msg.obj;
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.d(TAG, "handleSyncHandlerMessage: MESSAGE_SERVICE_CONNECTED: "
                                    + msgData.activeSyncContext);
                        }
                        // check that this isn't an old message
                        if (isSyncStillActive(msgData.activeSyncContext)) {
                            runBoundToSyncAdapter(msgData.activeSyncContext, msgData.syncAdapter);
                        }
                        break;
                    }

                    case SyncHandler.MESSAGE_SERVICE_DISCONNECTED: {
                        final ActiveSyncContext currentSyncContext =
                                ((ServiceConnectionData)msg.obj).activeSyncContext;
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.d(TAG, "handleSyncHandlerMessage: MESSAGE_SERVICE_DISCONNECTED: "
                                    + currentSyncContext);
                        }
                        // check that this isn't an old message
                        if (isSyncStillActive(currentSyncContext)) {
                            // cancel the sync if we have a syncadapter, which means one is
                            // outstanding
                            if (currentSyncContext.mSyncAdapter != null) {
                                try {
                                    currentSyncContext.mSyncAdapter.cancelSync(currentSyncContext);
                                } catch (RemoteException e) {
                                    // we don't need to retry this in this case
                                }
                            }

                            // pretend that the sync failed with an IOException,
                            // which is a soft error
                            SyncResult syncResult = new SyncResult();
                            syncResult.stats.numIoExceptions++;
                            runSyncFinishedOrCanceledLocked(syncResult, currentSyncContext);

                            // since a sync just finished check if it is time to start a new sync
                            nextPendingSyncTime = maybeStartNextSyncLocked();
                        }

                        break;
                    }

                    case SyncHandler.MESSAGE_SYNC_ALARM: {
                        boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
                        if (isLoggable) {
                            Log.v(TAG, "handleSyncHandlerMessage: MESSAGE_SYNC_ALARM");
                        }
                        mAlarmScheduleTime = null;
                        try {
                            nextPendingSyncTime = maybeStartNextSyncLocked();
                        } finally {
                            mHandleAlarmWakeLock.release();
                        }
                        break;
                    }

                    case SyncHandler.MESSAGE_CHECK_ALARMS:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "handleSyncHandlerMessage: MESSAGE_CHECK_ALARMS");
                        }
                        nextPendingSyncTime = maybeStartNextSyncLocked();
                        break;
                }
            } finally {
                manageSyncNotificationLocked();
                manageSyncAlarmLocked(earliestFuturePollTime, nextPendingSyncTime);
                mSyncTimeTracker.update();
                mSyncManagerWakeLock.release();
            }
        }

        /**
         * Turn any periodic sync operations that are ready to run into pending sync operations.
         * @return the desired start time of the earliest future  periodic sync operation,
         * in milliseconds since boot
         */
        private long scheduleReadyPeriodicSyncs() {
            final boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
            if (isLoggable) {
                Log.v(TAG, "scheduleReadyPeriodicSyncs");
            }
            final boolean backgroundDataUsageAllowed =
                    getConnectivityManager().getBackgroundDataSetting();
            long earliestFuturePollTime = Long.MAX_VALUE;
            if (!backgroundDataUsageAllowed) {
                return earliestFuturePollTime;
            }

            AccountAndUser[] accounts = mRunningAccounts;

            final long nowAbsolute = System.currentTimeMillis();
            final long shiftedNowAbsolute = (0 < nowAbsolute - mSyncRandomOffsetMillis)
                    ? (nowAbsolute - mSyncRandomOffsetMillis) : 0;

            ArrayList<Pair<AuthorityInfo, SyncStatusInfo>> infos = mSyncStorageEngine
                    .getCopyOfAllAuthoritiesWithSyncStatus();
            for (Pair<AuthorityInfo, SyncStatusInfo> info : infos) {
                final AuthorityInfo authorityInfo = info.first;
                final SyncStatusInfo status = info.second;
                if (TextUtils.isEmpty(authorityInfo.authority)) {
                    Log.e(TAG, "Got an empty provider string. Skipping: " + authorityInfo);
                    continue;
                }
                // skip the sync if the account of this operation no longer exists
                if (!containsAccountAndUser(
                        accounts, authorityInfo.account, authorityInfo.userId)) {
                    continue;
                }

                if (!mSyncStorageEngine.getMasterSyncAutomatically(authorityInfo.userId)
                        || !mSyncStorageEngine.getSyncAutomatically(
                                authorityInfo.account, authorityInfo.userId,
                                authorityInfo.authority)) {
                    continue;
                }

                if (getIsSyncable(
                        authorityInfo.account, authorityInfo.userId, authorityInfo.authority)
                        == 0) {
                    continue;
                }

                for (int i = 0, N = authorityInfo.periodicSyncs.size(); i < N; i++) {
                    final PeriodicSync sync = authorityInfo.periodicSyncs.get(i);
                    final Bundle extras = sync.extras;
                    final long periodInMillis = sync.period * 1000;
                    final long flexInMillis = sync.flexTime * 1000;
                    // Skip if the period is invalid.
                    if (periodInMillis <= 0) {
                        continue;
                    }
                    // Find when this periodic sync was last scheduled to run.
                    final long lastPollTimeAbsolute = status.getPeriodicSyncTime(i);
                    long remainingMillis
                        = periodInMillis - (shiftedNowAbsolute % periodInMillis);
                    long timeSinceLastRunMillis
                        = (nowAbsolute - lastPollTimeAbsolute);
                    // Schedule this periodic sync to run early if it's close enough to its next
                    // runtime, and far enough from its last run time.
                    // If we are early, there will still be time remaining in this period.
                    boolean runEarly = remainingMillis <= flexInMillis
                            && timeSinceLastRunMillis > periodInMillis - flexInMillis;
                    if (isLoggable) {
                        Log.v(TAG, "sync: " + i + " for " + authorityInfo.authority + "."
                        + " period: " + (periodInMillis)
                        + " flex: " + (flexInMillis)
                        + " remaining: " + (remainingMillis)
                        + " time_since_last: " + timeSinceLastRunMillis
                        + " last poll absol: " + lastPollTimeAbsolute
                        + " shifted now: " + shiftedNowAbsolute
                        + " run_early: " + runEarly);
                    }
                    /*
                     * Sync scheduling strategy: Set the next periodic sync
                     * based on a random offset (in seconds). Also sync right
                     * now if any of the following cases hold and mark it as
                     * having been scheduled
                     * Case 1: This sync is ready to run now.
                     * Case 2: If the lastPollTimeAbsolute is in the
                     * future, sync now and reinitialize. This can happen for
                     * example if the user changed the time, synced and changed
                     * back.
                     * Case 3: If we failed to sync at the last scheduled
                     * time.
                     * Case 4: This sync is close enough to the time that we can schedule it.
                     */
                    if (runEarly // Case 4
                            || remainingMillis == periodInMillis // Case 1
                            || lastPollTimeAbsolute > nowAbsolute // Case 2
                            || timeSinceLastRunMillis >= periodInMillis) { // Case 3
                        // Sync now
                        
                        final Pair<Long, Long> backoff = mSyncStorageEngine.getBackoff(
                                authorityInfo.account, authorityInfo.userId,
                                authorityInfo.authority);
                        final RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo;
                        syncAdapterInfo = mSyncAdapters.getServiceInfo(
                                SyncAdapterType.newKey(
                                        authorityInfo.authority, authorityInfo.account.type),
                                authorityInfo.userId);
                        if (syncAdapterInfo == null) {
                            continue;
                        }
                        mSyncStorageEngine.setPeriodicSyncTime(authorityInfo.ident,
                                authorityInfo.periodicSyncs.get(i), nowAbsolute);
                        scheduleSyncOperation(
                                new SyncOperation(authorityInfo.account, authorityInfo.userId,
                                        SyncOperation.REASON_PERIODIC,
                                        SyncStorageEngine.SOURCE_PERIODIC,
                                        authorityInfo.authority, extras,
                                        0 /* runtime */, 0 /* flex */,
                                                backoff != null ? backoff.first : 0,
                                        mSyncStorageEngine.getDelayUntilTime(
                                                authorityInfo.account, authorityInfo.userId,
                                                authorityInfo.authority),
                                        syncAdapterInfo.type.allowParallelSyncs()));
                        
                    }
                    // Compute when this periodic sync should next run.
                    long nextPollTimeAbsolute;
                    if (runEarly) {
                        // Add the time remaining so we don't get out of phase.
                        nextPollTimeAbsolute = nowAbsolute + periodInMillis + remainingMillis;
                    } else {
                        nextPollTimeAbsolute = nowAbsolute + remainingMillis;
                    }
                    if (nextPollTimeAbsolute < earliestFuturePollTime) {
                        earliestFuturePollTime = nextPollTimeAbsolute;
                    }
                }
            }

            if (earliestFuturePollTime == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }

            // convert absolute time to elapsed time
            return SystemClock.elapsedRealtime() +
                ((earliestFuturePollTime < nowAbsolute) ?
                    0 : (earliestFuturePollTime - nowAbsolute));
        }

        private long maybeStartNextSyncLocked() {
            final boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
            if (isLoggable) Log.v(TAG, "maybeStartNextSync");

            // If we aren't ready to run (e.g. the data connection is down), get out.
            if (!mDataConnectionIsConnected) {
                if (isLoggable) {
                    Log.v(TAG, "maybeStartNextSync: no data connection, skipping");
                }
                return Long.MAX_VALUE;
            }

            if (mStorageIsLow) {
                if (isLoggable) {
                    Log.v(TAG, "maybeStartNextSync: memory low, skipping");
                }
                return Long.MAX_VALUE;
            }

            // If the accounts aren't known yet then we aren't ready to run. We will be kicked
            // when the account lookup request does complete.
            AccountAndUser[] accounts = mRunningAccounts;
            if (accounts == INITIAL_ACCOUNTS_ARRAY) {
                if (isLoggable) {
                    Log.v(TAG, "maybeStartNextSync: accounts not known, skipping");
                }
                return Long.MAX_VALUE;
            }

            // Otherwise consume SyncOperations from the head of the SyncQueue until one is
            // found that is runnable (not disabled, etc). If that one is ready to run then
            // start it, otherwise just get out.
            final boolean backgroundDataUsageAllowed =
                    getConnectivityManager().getBackgroundDataSetting();

            final long now = SystemClock.elapsedRealtime();

            // will be set to the next time that a sync should be considered for running
            long nextReadyToRunTime = Long.MAX_VALUE;

            // order the sync queue, dropping syncs that are not allowed
            ArrayList<SyncOperation> operations = new ArrayList<SyncOperation>();
            synchronized (mSyncQueue) {
                if (isLoggable) {
                    Log.v(TAG, "build the operation array, syncQueue size is "
                        + mSyncQueue.getOperations().size());
                }
                final Iterator<SyncOperation> operationIterator =
                        mSyncQueue.getOperations().iterator();

                final ActivityManager activityManager
                        = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
                final Set<Integer> removedUsers = Sets.newHashSet();
                while (operationIterator.hasNext()) {
                    final SyncOperation op = operationIterator.next();

                    // Drop the sync if the account of this operation no longer exists.
                    if (!containsAccountAndUser(accounts, op.account, op.userId)) {
                        operationIterator.remove();
                        mSyncStorageEngine.deleteFromPending(op.pendingOperation);
                        if (isLoggable) {
                            Log.v(TAG, "    Dropping sync operation: account doesn't exist.");
                        }
                        continue;
                    }

                    // Drop this sync request if it isn't syncable.
                    int syncableState = getIsSyncable(
                            op.account, op.userId, op.authority);
                    if (syncableState == 0) {
                        operationIterator.remove();
                        mSyncStorageEngine.deleteFromPending(op.pendingOperation);
                        if (isLoggable) {
                            Log.v(TAG, "    Dropping sync operation: isSyncable == 0.");
                        }
                        continue;
                    }

                    // If the user is not running, drop the request.
                    if (!activityManager.isUserRunning(op.userId)) {
                        final UserInfo userInfo = mUserManager.getUserInfo(op.userId);
                        if (userInfo == null) {
                            removedUsers.add(op.userId);
                        }
                        if (isLoggable) {
                            Log.v(TAG, "    Dropping sync operation: user not running.");
                        }
                        continue;
                    }

                    // If the next run time is in the future, even given the flexible scheduling,
                    // return the time.
                    if (op.effectiveRunTime - op.flexTime > now) {
                        if (nextReadyToRunTime > op.effectiveRunTime) {
                            nextReadyToRunTime = op.effectiveRunTime;
                        }
                        if (isLoggable) {
                            Log.v(TAG, "    Dropping sync operation: Sync too far in future.");
                        }
                        continue;
                    }

                    // If the op isn't allowed on metered networks and we're on one, drop it.
                    if (getConnectivityManager().isActiveNetworkMetered()
                            && op.isMeteredDisallowed()) {
                        operationIterator.remove();
                        mSyncStorageEngine.deleteFromPending(op.pendingOperation);
                        continue;
                    }

                    // TODO: change this behaviour for non-registered syncs.
                    final RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo;
                    syncAdapterInfo = mSyncAdapters.getServiceInfo(
                            SyncAdapterType.newKey(op.authority, op.account.type), op.userId);

                    // only proceed if network is connected for requesting UID
                    final boolean uidNetworkConnected;
                    if (syncAdapterInfo != null) {
                        final NetworkInfo networkInfo = getConnectivityManager()
                                .getActiveNetworkInfoForUid(syncAdapterInfo.uid);
                        uidNetworkConnected = networkInfo != null && networkInfo.isConnected();
                    } else {
                        uidNetworkConnected = false;
                    }

                    // skip the sync if it isn't manual, and auto sync or
                    // background data usage is disabled or network is
                    // disconnected for the target UID.
                    if (!op.extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, false)
                            && (syncableState > 0)
                            && (!mSyncStorageEngine.getMasterSyncAutomatically(op.userId)
                                || !backgroundDataUsageAllowed
                                || !uidNetworkConnected
                                || !mSyncStorageEngine.getSyncAutomatically(
                                       op.account, op.userId, op.authority))) {
                        operationIterator.remove();
                        mSyncStorageEngine.deleteFromPending(op.pendingOperation);
                        continue;
                    }

                    operations.add(op);
                }
                for (Integer user : removedUsers) {
                    // if it's still removed
                    if (mUserManager.getUserInfo(user) == null) {
                        onUserRemoved(user);
                    }
                }
            }

            // find the next operation to dispatch, if one is ready
            // iterate from the top, keep issuing (while potentially canceling existing syncs)
            // until the quotas are filled.
            // once the quotas are filled iterate once more to find when the next one would be
            // (also considering pre-emption reasons).
            if (isLoggable) Log.v(TAG, "sort the candidate operations, size " + operations.size());
            Collections.sort(operations);
            if (isLoggable) Log.v(TAG, "dispatch all ready sync operations");
            for (int i = 0, N = operations.size(); i < N; i++) {
                final SyncOperation candidate = operations.get(i);
                final boolean candidateIsInitialization = candidate.isInitialization();

                int numInit = 0;
                int numRegular = 0;
                ActiveSyncContext conflict = null;
                ActiveSyncContext longRunning = null;
                ActiveSyncContext toReschedule = null;
                ActiveSyncContext oldestNonExpeditedRegular = null;

                for (ActiveSyncContext activeSyncContext : mActiveSyncContexts) {
                    final SyncOperation activeOp = activeSyncContext.mSyncOperation;
                    if (activeOp.isInitialization()) {
                        numInit++;
                    } else {
                        numRegular++;
                        if (!activeOp.isExpedited()) {
                            if (oldestNonExpeditedRegular == null
                                || (oldestNonExpeditedRegular.mStartTime
                                    > activeSyncContext.mStartTime)) {
                                oldestNonExpeditedRegular = activeSyncContext;
                            }
                        }
                    }
                    if (activeOp.account.type.equals(candidate.account.type)
                            && activeOp.authority.equals(candidate.authority)
                            && activeOp.userId == candidate.userId
                            && (!activeOp.allowParallelSyncs
                                || activeOp.account.name.equals(candidate.account.name))) {
                        conflict = activeSyncContext;
                        // don't break out since we want to do a full count of the varieties
                    } else {
                        if (candidateIsInitialization == activeOp.isInitialization()
                                && activeSyncContext.mStartTime + MAX_TIME_PER_SYNC < now) {
                            longRunning = activeSyncContext;
                            // don't break out since we want to do a full count of the varieties
                        }
                    }
                }

                if (isLoggable) {
                    Log.v(TAG, "candidate " + (i + 1) + " of " + N + ": " + candidate);
                    Log.v(TAG, "  numActiveInit=" + numInit + ", numActiveRegular=" + numRegular);
                    Log.v(TAG, "  longRunning: " + longRunning);
                    Log.v(TAG, "  conflict: " + conflict);
                    Log.v(TAG, "  oldestNonExpeditedRegular: " + oldestNonExpeditedRegular);
                }

                final boolean roomAvailable = candidateIsInitialization
                        ? numInit < MAX_SIMULTANEOUS_INITIALIZATION_SYNCS
                        : numRegular < MAX_SIMULTANEOUS_REGULAR_SYNCS;

                if (conflict != null) {
                    if (candidateIsInitialization && !conflict.mSyncOperation.isInitialization()
                            && numInit < MAX_SIMULTANEOUS_INITIALIZATION_SYNCS) {
                        toReschedule = conflict;
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "canceling and rescheduling sync since an initialization "
                                    + "takes higher priority, " + conflict);
                        }
                    } else if (candidate.expedited && !conflict.mSyncOperation.expedited
                            && (candidateIsInitialization
                                == conflict.mSyncOperation.isInitialization())) {
                        toReschedule = conflict;
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "canceling and rescheduling sync since an expedited "
                                    + "takes higher priority, " + conflict);
                        }
                    } else {
                        continue;
                    }
                } else if (roomAvailable) {
                    // dispatch candidate
                } else if (candidate.isExpedited() && oldestNonExpeditedRegular != null
                           && !candidateIsInitialization) {
                    // We found an active, non-expedited regular sync. We also know that the
                    // candidate doesn't conflict with this active sync since conflict
                    // is null. Reschedule the active sync and start the candidate.
                    toReschedule = oldestNonExpeditedRegular;
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "canceling and rescheduling sync since an expedited is ready to run, "
                                + oldestNonExpeditedRegular);
                    }
                } else if (longRunning != null
                        && (candidateIsInitialization
                            == longRunning.mSyncOperation.isInitialization())) {
                    // We found an active, long-running sync. Reschedule the active
                    // sync and start the candidate.
                    toReschedule = longRunning;
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "canceling and rescheduling sync since it ran roo long, "
                              + longRunning);
                    }
                } else {
                    // we were unable to find or make space to run this candidate, go on to
                    // the next one
                    continue;
                }

                if (toReschedule != null) {
                    runSyncFinishedOrCanceledLocked(null, toReschedule);
                    scheduleSyncOperation(toReschedule.mSyncOperation);
                }
                synchronized (mSyncQueue) {
                    mSyncQueue.remove(candidate);
                }
                dispatchSyncOperation(candidate);
            }

            return nextReadyToRunTime;
     }

        private boolean dispatchSyncOperation(SyncOperation op) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "dispatchSyncOperation: we are going to sync " + op);
                Log.v(TAG, "num active syncs: " + mActiveSyncContexts.size());
                for (ActiveSyncContext syncContext : mActiveSyncContexts) {
                    Log.v(TAG, syncContext.toString());
                }
            }

            // connect to the sync adapter
            SyncAdapterType syncAdapterType = SyncAdapterType.newKey(op.authority, op.account.type);
            final RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo;
            syncAdapterInfo = mSyncAdapters.getServiceInfo(syncAdapterType, op.userId);
            if (syncAdapterInfo == null) {
                Log.d(TAG, "can't find a sync adapter for " + syncAdapterType
                        + ", removing settings for it");
                mSyncStorageEngine.removeAuthority(op.account, op.userId, op.authority);
                return false;
            }

            ActiveSyncContext activeSyncContext =
                    new ActiveSyncContext(op, insertStartSyncEvent(op), syncAdapterInfo.uid);
            activeSyncContext.mSyncInfo = mSyncStorageEngine.addActiveSync(activeSyncContext);
            mActiveSyncContexts.add(activeSyncContext);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "dispatchSyncOperation: starting " + activeSyncContext);
            }
            if (!activeSyncContext.bindToSyncAdapter(syncAdapterInfo, op.userId)) {
                Log.e(TAG, "Bind attempt failed to " + syncAdapterInfo);
                closeActiveSyncContext(activeSyncContext);
                return false;
            }

            return true;
        }

        private void runBoundToSyncAdapter(final ActiveSyncContext activeSyncContext,
              ISyncAdapter syncAdapter) {
            activeSyncContext.mSyncAdapter = syncAdapter;
            final SyncOperation syncOperation = activeSyncContext.mSyncOperation;
            try {
                activeSyncContext.mIsLinkedToDeath = true;
                syncAdapter.asBinder().linkToDeath(activeSyncContext, 0);

                syncAdapter.startSync(activeSyncContext, syncOperation.authority,
                        syncOperation.account, syncOperation.extras);
            } catch (RemoteException remoteExc) {
                Log.d(TAG, "maybeStartNextSync: caught a RemoteException, rescheduling", remoteExc);
                closeActiveSyncContext(activeSyncContext);
                increaseBackoffSetting(syncOperation);
                scheduleSyncOperation(new SyncOperation(syncOperation));
            } catch (RuntimeException exc) {
                closeActiveSyncContext(activeSyncContext);
                Log.e(TAG, "Caught RuntimeException while starting the sync " + syncOperation, exc);
            }
        }

        private void cancelActiveSyncLocked(Account account, int userId, String authority) {
            ArrayList<ActiveSyncContext> activeSyncs =
                    new ArrayList<ActiveSyncContext>(mActiveSyncContexts);
            for (ActiveSyncContext activeSyncContext : activeSyncs) {
                if (activeSyncContext != null) {
                    // if an account was specified then only cancel the sync if it matches
                    if (account != null) {
                        if (!account.equals(activeSyncContext.mSyncOperation.account)) {
                            continue;
                        }
                    }
                    // if an authority was specified then only cancel the sync if it matches
                    if (authority != null) {
                        if (!authority.equals(activeSyncContext.mSyncOperation.authority)) {
                            continue;
                        }
                    }
                    // check if the userid matches
                    if (userId != UserHandle.USER_ALL
                            && userId != activeSyncContext.mSyncOperation.userId) {
                        continue;
                    }
                    runSyncFinishedOrCanceledLocked(null /* no result since this is a cancel */,
                            activeSyncContext);
                }
            }
        }

        private void runSyncFinishedOrCanceledLocked(SyncResult syncResult,
                ActiveSyncContext activeSyncContext) {
            boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);

            if (activeSyncContext.mIsLinkedToDeath) {
                activeSyncContext.mSyncAdapter.asBinder().unlinkToDeath(activeSyncContext, 0);
                activeSyncContext.mIsLinkedToDeath = false;
            }
            closeActiveSyncContext(activeSyncContext);

            final SyncOperation syncOperation = activeSyncContext.mSyncOperation;

            final long elapsedTime = SystemClock.elapsedRealtime() - activeSyncContext.mStartTime;

            String historyMessage;
            int downstreamActivity;
            int upstreamActivity;
            if (syncResult != null) {
                if (isLoggable) {
                    Log.v(TAG, "runSyncFinishedOrCanceled [finished]: "
                            + syncOperation + ", result " + syncResult);
                }

                if (!syncResult.hasError()) {
                    historyMessage = SyncStorageEngine.MESG_SUCCESS;
                    // TODO: set these correctly when the SyncResult is extended to include it
                    downstreamActivity = 0;
                    upstreamActivity = 0;
                    clearBackoffSetting(syncOperation);
                } else {
                    Log.d(TAG, "failed sync operation " + syncOperation + ", " + syncResult);
                    // the operation failed so increase the backoff time
                    if (!syncResult.syncAlreadyInProgress) {
                        increaseBackoffSetting(syncOperation);
                    }
                    // reschedule the sync if so indicated by the syncResult
                    maybeRescheduleSync(syncResult, syncOperation);
                    historyMessage = ContentResolver.syncErrorToString(
                            syncResultToErrorNumber(syncResult));
                    // TODO: set these correctly when the SyncResult is extended to include it
                    downstreamActivity = 0;
                    upstreamActivity = 0;
                }

                setDelayUntilTime(syncOperation, syncResult.delayUntil);
            } else {
                if (isLoggable) {
                    Log.v(TAG, "runSyncFinishedOrCanceled [canceled]: " + syncOperation);
                }
                if (activeSyncContext.mSyncAdapter != null) {
                    try {
                        activeSyncContext.mSyncAdapter.cancelSync(activeSyncContext);
                    } catch (RemoteException e) {
                        // we don't need to retry this in this case
                    }
                }
                historyMessage = SyncStorageEngine.MESG_CANCELED;
                downstreamActivity = 0;
                upstreamActivity = 0;
            }

            stopSyncEvent(activeSyncContext.mHistoryRowId, syncOperation, historyMessage,
                    upstreamActivity, downstreamActivity, elapsedTime);

            if (syncResult != null && syncResult.tooManyDeletions) {
                installHandleTooManyDeletesNotification(syncOperation.account,
                        syncOperation.authority, syncResult.stats.numDeletes,
                        syncOperation.userId);
            } else {
                mNotificationMgr.cancelAsUser(null,
                        syncOperation.account.hashCode() ^ syncOperation.authority.hashCode(),
                        new UserHandle(syncOperation.userId));
            }

            if (syncResult != null && syncResult.fullSyncRequested) {
                scheduleSyncOperation(
                        new SyncOperation(syncOperation.account, syncOperation.userId,
                            syncOperation.reason,
                            syncOperation.syncSource, syncOperation.authority, new Bundle(),
                            0 /* delay */, 0 /* flex */,
                            syncOperation.backoff, syncOperation.delayUntil,
                            syncOperation.allowParallelSyncs));
            }
            // no need to schedule an alarm, as that will be done by our caller.
        }

        private void closeActiveSyncContext(ActiveSyncContext activeSyncContext) {
            activeSyncContext.close();
            mActiveSyncContexts.remove(activeSyncContext);
            mSyncStorageEngine.removeActiveSync(activeSyncContext.mSyncInfo,
                    activeSyncContext.mSyncOperation.userId);
        }

        /**
         * Convert the error-containing SyncResult into the Sync.History error number. Since
         * the SyncResult may indicate multiple errors at once, this method just returns the
         * most "serious" error.
         * @param syncResult the SyncResult from which to read
         * @return the most "serious" error set in the SyncResult
         * @throws IllegalStateException if the SyncResult does not indicate any errors.
         *   If SyncResult.error() is true then it is safe to call this.
         */
        private int syncResultToErrorNumber(SyncResult syncResult) {
            if (syncResult.syncAlreadyInProgress)
                return ContentResolver.SYNC_ERROR_SYNC_ALREADY_IN_PROGRESS;
            if (syncResult.stats.numAuthExceptions > 0)
                return ContentResolver.SYNC_ERROR_AUTHENTICATION;
            if (syncResult.stats.numIoExceptions > 0)
                return ContentResolver.SYNC_ERROR_IO;
            if (syncResult.stats.numParseExceptions > 0)
                return ContentResolver.SYNC_ERROR_PARSE;
            if (syncResult.stats.numConflictDetectedExceptions > 0)
                return ContentResolver.SYNC_ERROR_CONFLICT;
            if (syncResult.tooManyDeletions)
                return ContentResolver.SYNC_ERROR_TOO_MANY_DELETIONS;
            if (syncResult.tooManyRetries)
                return ContentResolver.SYNC_ERROR_TOO_MANY_RETRIES;
            if (syncResult.databaseError)
                return ContentResolver.SYNC_ERROR_INTERNAL;
            throw new IllegalStateException("we are not in an error state, " + syncResult);
        }

        private void manageSyncNotificationLocked() {
            boolean shouldCancel;
            boolean shouldInstall;

            if (mActiveSyncContexts.isEmpty()) {
                mSyncNotificationInfo.startTime = null;

                // we aren't syncing. if the notification is active then remember that we need
                // to cancel it and then clear out the info
                shouldCancel = mSyncNotificationInfo.isActive;
                shouldInstall = false;
            } else {
                // we are syncing
                final long now = SystemClock.elapsedRealtime();
                if (mSyncNotificationInfo.startTime == null) {
                    mSyncNotificationInfo.startTime = now;
                }

                // there are three cases:
                // - the notification is up: do nothing
                // - the notification is not up but it isn't time yet: don't install
                // - the notification is not up and it is time: need to install

                if (mSyncNotificationInfo.isActive) {
                    shouldInstall = shouldCancel = false;
                } else {
                    // it isn't currently up, so there is nothing to cancel
                    shouldCancel = false;

                    final boolean timeToShowNotification =
                            now > mSyncNotificationInfo.startTime + SYNC_NOTIFICATION_DELAY;
                    if (timeToShowNotification) {
                        shouldInstall = true;
                    } else {
                        // show the notification immediately if this is a manual sync
                        shouldInstall = false;
                        for (ActiveSyncContext activeSyncContext : mActiveSyncContexts) {
                            final boolean manualSync = activeSyncContext.mSyncOperation.extras
                                    .getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);
                            if (manualSync) {
                                shouldInstall = true;
                                break;
                            }
                        }
                    }
                }
            }

            if (shouldCancel && !shouldInstall) {
                mNeedSyncActiveNotification = false;
                sendSyncStateIntent();
                mSyncNotificationInfo.isActive = false;
            }

            if (shouldInstall) {
                mNeedSyncActiveNotification = true;
                sendSyncStateIntent();
                mSyncNotificationInfo.isActive = true;
            }
        }

        private void manageSyncAlarmLocked(long nextPeriodicEventElapsedTime,
                long nextPendingEventElapsedTime) {
            // in each of these cases the sync loop will be kicked, which will cause this
            // method to be called again
            if (!mDataConnectionIsConnected) return;
            if (mStorageIsLow) return;

            // When the status bar notification should be raised
            final long notificationTime =
                    (!mSyncHandler.mSyncNotificationInfo.isActive
                            && mSyncHandler.mSyncNotificationInfo.startTime != null)
                            ? mSyncHandler.mSyncNotificationInfo.startTime + SYNC_NOTIFICATION_DELAY
                            : Long.MAX_VALUE;

            // When we should consider canceling an active sync
            long earliestTimeoutTime = Long.MAX_VALUE;
            for (ActiveSyncContext currentSyncContext : mActiveSyncContexts) {
                final long currentSyncTimeoutTime =
                        currentSyncContext.mTimeoutStartTime + MAX_TIME_PER_SYNC;
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "manageSyncAlarm: active sync, mTimeoutStartTime + MAX is "
                            + currentSyncTimeoutTime);
                }
                if (earliestTimeoutTime > currentSyncTimeoutTime) {
                    earliestTimeoutTime = currentSyncTimeoutTime;
                }
            }

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "manageSyncAlarm: notificationTime is " + notificationTime);
            }

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "manageSyncAlarm: earliestTimeoutTime is " + earliestTimeoutTime);
            }

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "manageSyncAlarm: nextPeriodicEventElapsedTime is "
                        + nextPeriodicEventElapsedTime);
            }
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "manageSyncAlarm: nextPendingEventElapsedTime is "
                        + nextPendingEventElapsedTime);
            }

            long alarmTime = Math.min(notificationTime, earliestTimeoutTime);
            alarmTime = Math.min(alarmTime, nextPeriodicEventElapsedTime);
            alarmTime = Math.min(alarmTime, nextPendingEventElapsedTime);

            // Bound the alarm time.
            final long now = SystemClock.elapsedRealtime();
            if (alarmTime < now + SYNC_ALARM_TIMEOUT_MIN) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "manageSyncAlarm: the alarmTime is too small, "
                            + alarmTime + ", setting to " + (now + SYNC_ALARM_TIMEOUT_MIN));
                }
                alarmTime = now + SYNC_ALARM_TIMEOUT_MIN;
            } else if (alarmTime > now + SYNC_ALARM_TIMEOUT_MAX) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "manageSyncAlarm: the alarmTime is too large, "
                            + alarmTime + ", setting to " + (now + SYNC_ALARM_TIMEOUT_MIN));
                }
                alarmTime = now + SYNC_ALARM_TIMEOUT_MAX;
            }

            // determine if we need to set or cancel the alarm
            boolean shouldSet = false;
            boolean shouldCancel = false;
            final boolean alarmIsActive = (mAlarmScheduleTime != null) && (now < mAlarmScheduleTime);
            final boolean needAlarm = alarmTime != Long.MAX_VALUE;
            if (needAlarm) {
                // Need the alarm if
                //  - it's currently not set
                //  - if the alarm is set in the past.
                if (!alarmIsActive || alarmTime < mAlarmScheduleTime) {
                    shouldSet = true;
                }
            } else {
                shouldCancel = alarmIsActive;
            }

            // Set or cancel the alarm as directed.
            ensureAlarmService();
            if (shouldSet) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "requesting that the alarm manager wake us up at elapsed time "
                            + alarmTime + ", now is " + now + ", " + ((alarmTime - now) / 1000)
                            + " secs from now");
                }
                mAlarmScheduleTime = alarmTime;
                mAlarmService.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmTime,
                        mSyncAlarmIntent);
            } else if (shouldCancel) {
                mAlarmScheduleTime = null;
                mAlarmService.cancel(mSyncAlarmIntent);
            }
        }

        private void sendSyncStateIntent() {
            Intent syncStateIntent = new Intent(Intent.ACTION_SYNC_STATE_CHANGED);
            syncStateIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            syncStateIntent.putExtra("active", mNeedSyncActiveNotification);
            syncStateIntent.putExtra("failing", false);
            mContext.sendBroadcastAsUser(syncStateIntent, UserHandle.OWNER);
        }

        private void installHandleTooManyDeletesNotification(Account account, String authority,
                long numDeletes, int userId) {
            if (mNotificationMgr == null) return;

            final ProviderInfo providerInfo = mContext.getPackageManager().resolveContentProvider(
                    authority, 0 /* flags */);
            if (providerInfo == null) {
                return;
            }
            CharSequence authorityName = providerInfo.loadLabel(mContext.getPackageManager());

            Intent clickIntent = new Intent(mContext, SyncActivityTooManyDeletes.class);
            clickIntent.putExtra("account", account);
            clickIntent.putExtra("authority", authority);
            clickIntent.putExtra("provider", authorityName.toString());
            clickIntent.putExtra("numDeletes", numDeletes);

            if (!isActivityAvailable(clickIntent)) {
                Log.w(TAG, "No activity found to handle too many deletes.");
                return;
            }

            final PendingIntent pendingIntent = PendingIntent
                    .getActivityAsUser(mContext, 0, clickIntent,
                            PendingIntent.FLAG_CANCEL_CURRENT, null, new UserHandle(userId));

            CharSequence tooManyDeletesDescFormat = mContext.getResources().getText(
                    R.string.contentServiceTooManyDeletesNotificationDesc);

            Notification notification =
                new Notification(R.drawable.stat_notify_sync_error,
                        mContext.getString(R.string.contentServiceSync),
                        System.currentTimeMillis());
            notification.setLatestEventInfo(getUiContext(),
                    mContext.getString(R.string.contentServiceSyncNotificationTitle),
                    String.format(tooManyDeletesDescFormat.toString(), authorityName),
                    pendingIntent);
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            mNotificationMgr.notifyAsUser(null, account.hashCode() ^ authority.hashCode(),
                    notification, new UserHandle(userId));
        }

        /**
         * Checks whether an activity exists on the system image for the given intent.
         *
         * @param intent The intent for an activity.
         * @return Whether or not an activity exists.
         */
        private boolean isActivityAvailable(Intent intent) {
            PackageManager pm = mContext.getPackageManager();
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            int listSize = list.size();
            for (int i = 0; i < listSize; i++) {
                ResolveInfo resolveInfo = list.get(i);
                if ((resolveInfo.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM)
                        != 0) {
                    return true;
                }
            }

            return false;
        }

        public long insertStartSyncEvent(SyncOperation syncOperation) {
            final int source = syncOperation.syncSource;
            final long now = System.currentTimeMillis();

            EventLog.writeEvent(2720, syncOperation.authority,
                                SyncStorageEngine.EVENT_START, source,
                                syncOperation.account.name.hashCode());

            return mSyncStorageEngine.insertStartSyncEvent(
                    syncOperation.account, syncOperation.userId, syncOperation.reason,
                    syncOperation.authority,
                    now, source, syncOperation.isInitialization(), syncOperation.extras
            );
        }

        public void stopSyncEvent(long rowId, SyncOperation syncOperation, String resultMessage,
                int upstreamActivity, int downstreamActivity, long elapsedTime) {
            EventLog.writeEvent(2720, syncOperation.authority,
                                SyncStorageEngine.EVENT_STOP, syncOperation.syncSource,
                                syncOperation.account.name.hashCode());

            mSyncStorageEngine.stopSyncEvent(rowId, elapsedTime,
                    resultMessage, downstreamActivity, upstreamActivity);
        }
    }

    private boolean isSyncStillActive(ActiveSyncContext activeSyncContext) {
        for (ActiveSyncContext sync : mActiveSyncContexts) {
            if (sync == activeSyncContext) {
                return true;
            }
        }
        return false;
    }

    static class PrintTable {
        private ArrayList<Object[]> mTable = Lists.newArrayList();
        private final int mCols;

        PrintTable(int cols) {
            mCols = cols;
        }

        void set(int row, int col, Object... values) {
            if (col + values.length > mCols) {
                throw new IndexOutOfBoundsException("Table only has " + mCols +
                        " columns. can't set " + values.length + " at column " + col);
            }
            for (int i = mTable.size(); i <= row; i++) {
                final Object[] list = new Object[mCols];
                mTable.add(list);
                for (int j = 0; j < mCols; j++) {
                    list[j] = "";
                }
            }
            System.arraycopy(values, 0, mTable.get(row), col, values.length);
        }

        void writeTo(PrintWriter out) {
            final String[] formats = new String[mCols];
            int totalLength = 0;
            for (int col = 0; col < mCols; ++col) {
                int maxLength = 0;
                for (Object[] row : mTable) {
                    final int length = row[col].toString().length();
                    if (length > maxLength) {
                        maxLength = length;
                    }
                }
                totalLength += maxLength;
                formats[col] = String.format("%%-%ds", maxLength);
            }
            printRow(out, formats, mTable.get(0));
            totalLength += (mCols - 1) * 2;
            for (int i = 0; i < totalLength; ++i) {
                out.print("-");
            }
            out.println();
            for (int i = 1, mTableSize = mTable.size(); i < mTableSize; i++) {
                Object[] row = mTable.get(i);
                printRow(out, formats, row);
            }
        }

        private void printRow(PrintWriter out, String[] formats, Object[] row) {
            for (int j = 0, rowLength = row.length; j < rowLength; j++) {
                out.printf(String.format(formats[j], row[j].toString()));
                out.print("  ");
            }
            out.println();
        }

        public int getNumRows() {
            return mTable.size();
        }
    }
}
