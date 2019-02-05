/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.car.notification;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * NotificationListenerService that fetches all notifications from system.
 */
public class CarNotificationListener extends NotificationListenerService {
    private static final String TAG = "CarNotificationListener";
    static final String ACTION_LOCAL_BINDING = "local_binding";
    static final int NOTIFY_NOTIFICATION_ADDED = 1;
    static final int NOTIFY_NOTIFICATIONS_CHANGED = 2;
    /** Temporary {@link Ranking} object that serves as a reused value holder */
    final private Ranking mTemporaryRanking = new Ranking();

    private Handler mHandler;
    private RankingMap mRankingMap;
    private CarHeadsUpNotificationManager mHeadsUpManager;
    private List<StatusBarNotification> mNotifications = new ArrayList<>();

    /**
     * Call this if to register this service as a system service and connect to HUN. This is useful
     * if the notification service is being used as a lib instead of a standalone app. The
     * standalone app version has a manifest entry that will have the same effect.
     *
     * @param context Context required for registering the service.
     * @param carUxRestrictionManagerWrapper will have the heads up manager registered with it.
     * @param clickHandlerFactory used to construct Heads up manager
     */
    public void registerAsSystemService(Context context,
            CarUxRestrictionManagerWrapper carUxRestrictionManagerWrapper,
            NotificationClickHandlerFactory clickHandlerFactory) {
        try {
            registerAsSystemService(context,
                    new ComponentName(context.getPackageName(), getClass().getCanonicalName()),
                    ActivityManager.getCurrentUser());
            // Note: The first call to CarHeadsUpNotificationManager.getInstance will build the
            // UI thus we do it here to be sure it's ready.
            mHeadsUpManager = CarHeadsUpNotificationManager.getInstance(context,
                    clickHandlerFactory);
            carUxRestrictionManagerWrapper.setCarHeadsUpNotificationManager(mHeadsUpManager);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to register notification listener", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationApplication app = (NotificationApplication) getApplication();
        mHeadsUpManager = CarHeadsUpNotificationManager.getInstance(/* context= */this,
                app.getClickHandlerFactory());
        app.getCarUxRestrictionWrapper().setCarHeadsUpNotificationManager(mHeadsUpManager);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return ACTION_LOCAL_BINDING.equals(intent.getAction())
                ? new LocalBinder() : super.onBind(intent);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        mNotifications.removeIf(notification ->
                CarNotificationDiff.sameNotificationKey(notification, sbn));
        mNotifications.add(sbn);
        mRankingMap = rankingMap;
        onNotificationAdded(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        mNotifications.removeIf(notification ->
                CarNotificationDiff.sameNotificationKey(notification, sbn));
        onNotificationChanged();
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        mRankingMap = rankingMap;
        for (int i = 0; i < mNotifications.size(); i++) {
            StatusBarNotification sbn = mNotifications.get(i);
            if (!mRankingMap.getRanking(sbn.getKey(), mTemporaryRanking)) {
                continue;
            }
            String oldOverrideGroupKey = sbn.getOverrideGroupKey();
            String newOverrideGroupKey = getOverrideGroupKey(sbn.getKey());
            if (!Objects.equals(oldOverrideGroupKey, newOverrideGroupKey)) {
                sbn.setOverrideGroupKey(newOverrideGroupKey);
            }
        }
        onNotificationChanged();
    }

    /**
     * Get the override group key of a {@link StatusBarNotification} given its key.
     */
    @Nullable
    private String getOverrideGroupKey(String key) {
        if (mRankingMap != null) {
            mRankingMap.getRanking(key, mTemporaryRanking);
            return mTemporaryRanking.getOverrideGroupKey();
        }
        return null;
    }

    /**
     * Get all active notifications.
     *
     * @return a list of all active notifications.
     */
    List<StatusBarNotification> getNotifications() {
        return mNotifications;
    }

    @Override
    public RankingMap getCurrentRanking() {
        return mRankingMap;
    }

    @Override
    public void onListenerConnected() {
        mNotifications = new ArrayList<>(Arrays.asList(getActiveNotifications()));
        mRankingMap = super.getCurrentRanking();
    }

    @Override
    public void onListenerDisconnected() {
    }

    public void setHandler(Handler handler) {
        mHandler = handler;
    }

    private void onNotificationChanged() {
        if (mHandler == null) {
            return;
        }
        Message msg = Message.obtain(mHandler);
        msg.what = NOTIFY_NOTIFICATIONS_CHANGED;
        mHandler.sendMessage(msg);
    }

    private void onNotificationAdded(StatusBarNotification sbn) {
        mHeadsUpManager.maybeShowHeadsUp(sbn, getCurrentRanking());

        if (mHandler == null) {
            return;
        }
        Message msg = Message.obtain(mHandler);
        msg.what = NOTIFY_NOTIFICATION_ADDED;
        msg.obj = sbn;
        mHandler.sendMessage(msg);
    }

    class LocalBinder extends Binder {
        public CarNotificationListener getService() {
            return CarNotificationListener.this;
        }
    }
}
