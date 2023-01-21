/*
 * Copyright (C) 2021 Dr.NooB
 *
 * This file is a part of Data Monitor <https://github.com/itsdrnoob/DataMonitor>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.drnoob.datamonitor.utils;

import static com.drnoob.datamonitor.core.Values.NETWORK_SIGNAL_CHANNEL_ID;
import static com.drnoob.datamonitor.core.Values.NETWORK_SIGNAL_NOTIFICATION_GROUP;
import static com.drnoob.datamonitor.core.Values.NETWORK_SIGNAL_NOTIFICATION_ID;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.preference.PreferenceManager;

import com.drnoob.datamonitor.R;
import com.drnoob.datamonitor.ui.activities.MainActivity;

import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

public class LiveNetworkMonitor extends Service {
    private static final String TAG = LiveNetworkMonitor.class.getSimpleName();

    private static Timer mTimer;
    private static TimerTask mTimerTask;
    public static Long previousTotalBytes, previousUpBytes, previousDownBytes;
    private Intent mActivityIntent;
    private static PendingIntent mActivityPendingIntent;
    private static NotificationCompat.Builder mBuilder;
    private static LiveNetworkReceiver liveNetworkReceiver = new LiveNetworkReceiver();
    private static boolean isNetworkConnected;
    private NetworkChangeMonitor mNetworkChangeMonitor;
    private static boolean isTimerCancelled = true;
    private static boolean isTaskPaused = false;
    private static boolean isLiveNetworkReceiverRegistered = false;
    private boolean isServiceRunning;
    private static LiveNetworkMonitor mLiveNetworkMonitor;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public LiveNetworkMonitor() {
        // Empty constructor
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Service is started here
        mLiveNetworkMonitor = this;

        previousDownBytes = TrafficStats.getTotalRxBytes();
        previousUpBytes = TrafficStats.getTotalTxBytes();
        previousTotalBytes = previousDownBytes + previousUpBytes;

        mActivityIntent = new Intent(Intent.ACTION_MAIN);
        mActivityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        mActivityIntent.setComponent(new ComponentName(getPackageName(), MainActivity.class.getName()));
        mActivityPendingIntent = PendingIntent.getActivity(this, 0, mActivityIntent,
                PendingIntent.FLAG_IMMUTABLE);
        mBuilder = new NotificationCompat.Builder(this,
                NETWORK_SIGNAL_CHANNEL_ID);
        mNetworkChangeMonitor = new NetworkChangeMonitor(this);
        mNetworkChangeMonitor.startMonitor();

        boolean showOnLockscreen = PreferenceManager.getDefaultSharedPreferences(LiveNetworkMonitor.this)
                .getBoolean("lockscreen_notification", false);

        mBuilder.setSmallIcon(R.drawable.ic_signal_kb_0);
        mBuilder.setOngoing(true);
        mBuilder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        mBuilder.setContentTitle(getString(R.string.network_speed_title, "0 KB/s"));
        mBuilder.setStyle(new NotificationCompat.InboxStyle()
                .addLine(getString(R.string.network_speed_download, "0 KB/s"))
                .addLine(getString(R.string.network_speed_upload, "0 KB/s")));
        mBuilder.setShowWhen(false);
        if (showOnLockscreen) {
            mBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        }
        else {
            mBuilder.setVisibility(NotificationCompat.VISIBILITY_SECRET);
        }
        mBuilder.setContentIntent(mActivityPendingIntent);
        mBuilder.setAutoCancel(false);
        mBuilder.setGroup(NETWORK_SIGNAL_NOTIFICATION_GROUP);

        if (isServiceRunning) {
            return;
        }
        startForeground(NETWORK_SIGNAL_NOTIFICATION_ID, mBuilder.build());
        isServiceRunning = true;

        if (isTimerCancelled) {
            mTimer = new Timer();
        }
        mTimerTask = new TimerTask() {
            @Override
            public void run() {
                boolean isEnabled = PreferenceManager.getDefaultSharedPreferences(LiveNetworkMonitor.this).
                        getBoolean("network_signal_notification", false);
                boolean isCombined = PreferenceManager.getDefaultSharedPreferences(LiveNetworkMonitor.this)
                        .getBoolean("combine_notifications", false);
                if (isEnabled && !isCombined) {
                    updateNotification(LiveNetworkMonitor.this);
                }
                else {
                    Log.d(TAG, "run: aborted");
                    try {
                        mTimerTask.cancel();
                        mTimer.cancel();
                        isTimerCancelled = true;
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                    LiveNetworkMonitor.this.stopForeground(true);
                    LiveNetworkMonitor.this.stopSelf();
                    stopService(new Intent(LiveNetworkMonitor.this, this.getClass()));
                }
            }
        };
        mTimer.scheduleAtFixedRate(mTimerTask, 0, 1000);
        if (!isLiveNetworkReceiverRegistered && !isTaskPaused) {
            registerNetworkReceiver(this);
        }

//        liveData.getIsScreenOn().observe(this, new Observer<Boolean>() {
//            @Override
//            public void onChanged(Boolean aBoolean) {
//                Log.e(TAG, "onChanged: " + aBoolean );
//                restartService(LiveNetworkMonitor.this, true);
//            }
//        });

    }

    @Override
    public void onDestroy() {
        // Service is stopped here
        boolean isEnabled = PreferenceManager.getDefaultSharedPreferences(LiveNetworkMonitor.this).
                getBoolean("network_signal_notification", false);
        boolean isCombined = PreferenceManager.getDefaultSharedPreferences(LiveNetworkMonitor.this)
                .getBoolean("combine_notifications", false);
        if (!isEnabled || isCombined) {
            Log.d(TAG, "onDestroy: stopped");
            mNetworkChangeMonitor.stopMonitor();
            unregisterNetworkReceiver();
            isServiceRunning = false;
            try {
                mTimerTask.cancel();
                mTimer.cancel();
                isTimerCancelled = true;
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
        super.onDestroy();
    }

    private static void registerNetworkReceiver(Context context) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.setPriority(100);
        if (!isLiveNetworkReceiverRegistered) {
            context.registerReceiver(liveNetworkReceiver, intentFilter);
            isLiveNetworkReceiverRegistered = true;
            Log.d(TAG, "registerNetworkReceiver: registered" );
        }
    }

    private static void unregisterNetworkReceiver() {
        try {
            mLiveNetworkMonitor.unregisterReceiver(liveNetworkReceiver);
            isLiveNetworkReceiverRegistered = false;
            Log.d(TAG, "unregisterNetworkReceive: stopped" );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void updateNotification(Context context) {
        // Update notification text here
        String[] speeds;
        NotificationManagerCompat managerCompat = NotificationManagerCompat.from(context);
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        boolean showOnLockscreen = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("lockscreen_notification", false);

        if (isNetworkConnected) {
            if (!isLiveNetworkReceiverRegistered) {
                registerNetworkReceiver(context);
            }
            Long currentUpBytes = TrafficStats.getTotalTxBytes();
            Long currentDownBytes = TrafficStats.getTotalRxBytes();
            Long currentTotalBytes = currentDownBytes + currentUpBytes;

            Long upSpeed = currentUpBytes - previousUpBytes;
            Long downSpeed = currentDownBytes - previousDownBytes;
            Long totalSpeed = upSpeed + downSpeed;

            speeds = formatNetworkSpeed(upSpeed, downSpeed, totalSpeed);

            previousUpBytes = currentUpBytes;
            previousDownBytes = currentDownBytes;
            previousTotalBytes = currentTotalBytes;
        }
        else  {
            boolean autoHide = PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean("auto_hide_network_speed", false);
            if (autoHide) {
                try {
                    mTimerTask.cancel();
                    mTimer.cancel();
                    isTimerCancelled = true;
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
                mLiveNetworkMonitor.stopForeground(true);
                if (isLiveNetworkReceiverRegistered) {
                    unregisterNetworkReceiver();
                }
                isTaskPaused = true;
                return;
            }
            else {
                speeds = new String[]{"0 KB/s", "0 KB/s", "0 KB/s"};
            }
        }

        String iconPrefix = "ic_signal_";
        String networkType;
        String totalSuffix = speeds[2].split(" ")[1];
        if (totalSuffix.equals("MB/s")) {
            networkType = "mb_";
        }
        else {
            networkType = "kb_";
        }
        String iconSuffix = speeds[2].split(" ")[0];
        if (iconSuffix.contains(".")) {
            iconSuffix = iconSuffix.replace(".", "_");
        }
        if (iconSuffix.contains(",")) {
            iconSuffix = iconSuffix.replace(",", "_");
        }
        if (iconSuffix.contains("٫")) {
            iconSuffix = iconSuffix.replace("٫", "_");
        }
        if (!iconSuffix.contains("_")) {
            if (networkType.equals("mb_") && Integer.parseInt(iconSuffix) > 200) {
                iconSuffix = "200_plus";
            }
        }
        String iconName = iconPrefix + networkType + iconSuffix;
        Log.d(TAG, "updateNotification: " + iconName + "  " + Arrays.toString(speeds));
        IconCompat icon;
        try {
            int iconResID = context.getResources().getIdentifier(iconName , "drawable", context.getPackageName());
            icon = IconCompat.createWithResource(context, iconResID);
        }
        catch (Exception e) {
            icon = IconCompat.createWithResource(context, R.drawable.ic_signal_kb_0);
        }
        if (mBuilder == null) {
            mBuilder = new NotificationCompat.Builder(context,
                    NETWORK_SIGNAL_CHANNEL_ID);
        }
        mBuilder.setSmallIcon(icon);
        mBuilder.setOngoing(true);
        mBuilder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
        mBuilder.setContentTitle(context.getString(R.string.network_speed_title,  speeds[2]));
        mBuilder.setStyle(new NotificationCompat.InboxStyle()
                .addLine(context.getString(R.string.network_speed_download, speeds[1]))
                .addLine(context.getString(R.string.network_speed_upload, speeds[0])));
        mBuilder.setContentIntent(mActivityPendingIntent);
        mBuilder.setAutoCancel(false);
        mBuilder.setShowWhen(false);
        mBuilder.setGroup(NETWORK_SIGNAL_NOTIFICATION_GROUP);
        if (showOnLockscreen) {
            mBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        }
        else {
            mBuilder.setVisibility(NotificationCompat.VISIBILITY_SECRET);
        }
        managerCompat.notify(NETWORK_SIGNAL_NOTIFICATION_ID, mBuilder.build());

    }

    private class NetworkChangeMonitor extends ConnectivityManager.NetworkCallback {
        final NetworkRequest networkRequest;

        private ConnectivityManager connectivityManager;
        private Context context;

        public NetworkChangeMonitor(Context context) {
            this.context = context;
            connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            networkRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build();
        }

        public void startMonitor() {
            if (connectivityManager != null) {
                try {
                    connectivityManager.registerNetworkCallback(networkRequest, this);
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
            else {
                connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                startMonitor();
            }
        }

        public void stopMonitor() {
            if (connectivityManager != null) {
                try {
                    connectivityManager.unregisterNetworkCallback(this);
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
            else {
                connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                stopMonitor();
            }
        }

        @Override
        public void onAvailable(@NonNull Network network) {
            super.onAvailable(network);
            isNetworkConnected = true;
            if (isTaskPaused) {
                LiveNetworkMonitor.this.startForeground(NETWORK_SIGNAL_NOTIFICATION_ID, mBuilder.build());
                restartService(context, false);
                isTaskPaused = false;
            }
        }

        @Override
        public void onLost(@NonNull Network network) {
            super.onLost(network);
            isNetworkConnected = false;
        }
    }

    public static void restartService(Context context, boolean startReceiver) {
        if (startReceiver && !isLiveNetworkReceiverRegistered) {
            registerNetworkReceiver(context);
        }
        previousDownBytes = TrafficStats.getTotalRxBytes();
        previousUpBytes = TrafficStats.getTotalTxBytes();
        previousTotalBytes = previousDownBytes + previousUpBytes;
        mTimerTask = new TimerTask() {
            @Override
            public void run() {
                if (PreferenceManager.getDefaultSharedPreferences(context).
                        getBoolean("network_signal_notification", false)) {
                    updateNotification(context);
                }
                else {
                    try {
                        mTimerTask.cancel();
                        mTimer.cancel();
                        isTimerCancelled = true;
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                    context.stopService(new Intent(context, LiveNetworkMonitor.class));
                }
            }
        };
        if (isTimerCancelled) {
            mTimer = new Timer();
        }
        mTimer.scheduleAtFixedRate(mTimerTask, 0, 1000);
    }


    private static String[] formatNetworkSpeed(Long upSpeed, Long downSpeed, Long totalSpeed) {
        String up, down, total;
        int upSpeedKB = (int) (upSpeed / 1024);
        int downSpeedKB = (int) (downSpeed / 1024);
        int totalSpeedKB = (int) (totalSpeed / 1024);
        String upData, downData, totalData;
        Float upSpeedMB, downSpeedMB, totalSpeedMB;

        if (upSpeedKB >= 1000 && upSpeedKB < 1024) {
            upData = "1.0 MB/s";
        }
        else if (upSpeedKB >= 1024) {
            upSpeedMB = upSpeedKB / 1024f;
            if (upSpeedMB < 10) {
                upData = String.format("%.1f", upSpeedMB) + " MB/s";
            }
            else {
                upData = (int) (upSpeedKB / 1024) + " MB/s";
            }
        }
        else {
            upData = upSpeedKB + " KB/s";
        }

        if (downSpeedKB >= 1000 && downSpeedKB < 1024) {
            downData = "1.0 MB/s";
        }
        else if (downSpeedKB >= 1024) {
            downSpeedMB = downSpeedKB / 1024f;
            if (downSpeedMB < 10) {
                downData = String.format("%.1f", downSpeedMB) + " MB/s";
            }
            else {
                downData = (int) (downSpeedKB / 1024) + " MB/s";
            }
        }
        else {
            downData = downSpeedKB + " KB/s";
        }

        if (totalSpeedKB >= 1000 && totalSpeedKB < 1024) {
            totalData = "1.0 MB/s";
        }
        else if (totalSpeedKB >= 1024) {
            totalSpeedMB = totalSpeedKB / 1024f;
            if (totalSpeedMB < 10) {
                totalData = String.format("%.1f", totalSpeedMB) + " MB/s";
            }
            else {
                totalData = (int) (totalSpeedKB / 1024) + " MB/s";
            }
        }
        else {
            totalData = totalSpeedKB + " KB/s";
        }
        return new String[]{upData, downData, totalData};
    }

    public static class LiveNetworkReceiver extends BroadcastReceiver {

        public LiveNetworkReceiver() {
            // Empty constructor
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                // Screen turned off. Cancel task
                try {
                    mTimerTask.cancel();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
            else {
                restartService(context, true);
            }
        }
    }
}
