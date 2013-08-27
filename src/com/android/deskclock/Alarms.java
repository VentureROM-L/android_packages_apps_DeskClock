/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.deskclock;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Parcel;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Pair;

import com.android.deskclock.provider.Alarm;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Alarms provider supplies info about Alarm Clock settings
 */
public class Alarms {

    static final String PREFERENCES = "AlarmClock";

    // This action triggers the AlarmReceiver as well as the AlarmKlaxon. It
    // is a public action used in the manifest for receiving Alarm broadcasts
    // from the alarm manager.
    public static final String ALARM_ALERT_ACTION = "com.android.deskclock.ALARM_ALERT";

    // A public action sent by AlarmKlaxon when the alarm has stopped sounding
    // for any reason (e.g. because it has been dismissed from AlarmAlertFullScreen,
    // or killed due to an incoming phone call, etc).
    public static final String ALARM_DONE_ACTION = "com.android.deskclock.ALARM_DONE";

    // AlarmAlertFullScreen listens for this broadcast intent, so that other applications
    // can snooze the alarm (after ALARM_ALERT_ACTION and before ALARM_DONE_ACTION).
    public static final String ALARM_SNOOZE_ACTION = "com.android.deskclock.ALARM_SNOOZE";

    // AlarmAlertFullScreen listens for this broadcast intent, so that other applications
    // can dismiss the alarm (after ALARM_ALERT_ACTION and before ALARM_DONE_ACTION).
    public static final String ALARM_DISMISS_ACTION = "com.android.deskclock.ALARM_DISMISS";

    // A public action sent by AlarmAlertFullScreen when a snoozed alarm was dismissed due
    // to it handling ALARM_DISMISS_ACTION cancelled
    public static final String ALARM_SNOOZE_CANCELLED = "com.android.deskclock.ALARM_SNOOZE_CANCELLED";

    // A broadcast sent every time the next alarm time is set in the system
    public static final String NEXT_ALARM_TIME_SET = "com.android.deskclock.NEXT_ALARM_TIME_SET";

    // This is a private action used by the AlarmKlaxon to update the UI to
    // show the alarm has been killed.
    public static final String ALARM_KILLED = "alarm_killed";

    // Extra in the ALARM_KILLED intent to indicate to the user how long the
    // alarm played before being killed.
    public static final String ALARM_KILLED_TIMEOUT = "alarm_killed_timeout";

    // Extra in the ALARM_KILLED intent to indicate when alarm was replaced
    public static final String ALARM_REPLACED = "alarm_replaced";

    // This intent is sent from the notification when the user cancels the
    // snooze alert.
    public static final String CANCEL_SNOOZE = "cancel_snooze";

    // This string is used when passing an Alarm object through an intent.
    public static final String ALARM_INTENT_EXTRA = "intent.extra.alarm";

    // This extra is the raw Alarm object data. It is used in the
    // AlarmManagerService to avoid a ClassNotFoundException when filling in
    // the Intent extras.
    public static final String ALARM_RAW_DATA = "intent.extra.alarm_raw";

    // This extra is used when receiving an intent to create an alarm, but no alarm details
    // have been passed in, so the alarm page should start the process of creating a new alarm.
    public static final String ALARM_CREATE_NEW = "create_new";

    private static final String PREF_SNOOZE_IDS = "snooze_ids";
    private static final String PREF_SNOOZE_TIME = "snooze_time";

    private final static String DM12 = "E h:mm aa";
    private final static String DM24 = "E kk:mm";

    private final static String M12 = "h:mm aa";
    // Shared with DigitalClock
    final static String M24 = "kk:mm";

    /**
     * Creates a new Alarm and fills in the given alarm's id.
     */
    public static long addAlarm(Context context, Alarm alarm) {
        ContentValues values = Alarm.createContentValues(alarm);
        Uri uri = context.getContentResolver().insert(Alarm.CONTENT_URI, values);
        alarm.id = (int) ContentUris.parseId(uri);

        long timeInMillis = alarm.calculateAlarmTime();
        if (alarm.enabled) {
            clearSnoozeIfNeeded(context, timeInMillis);
        }
        setNextAlert(context);
        return timeInMillis;
    }

    /**
     * Removes an existing Alarm.  If this alarm is snoozing, disables
     * snooze.  Sets next alert.
     */
    public static void deleteAlarm(Context context, long alarmId) {
        if (alarmId == Alarm.INVALID_ID) return;

        ContentResolver contentResolver = context.getContentResolver();
        /* If alarm is snoozing, lose it */
        disableSnoozeAlert(context, alarmId);

        Uri uri = ContentUris.withAppendedId(Alarm.CONTENT_URI, alarmId);
        contentResolver.delete(uri, "", null);

        setNextAlert(context);
    }

    private static void clearSnoozeIfNeeded(Context context, long alarmTime) {
        // If this alarm fires before the next snooze, clear the snooze to
        // enable this alarm.
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, 0);

        // Get the list of snoozed alarms
        final Set<String> snoozedIds = prefs.getStringSet(PREF_SNOOZE_IDS, new HashSet<String>());
        for (String snoozedAlarm : snoozedIds) {
            final long snoozeTime = prefs.getLong(getAlarmPrefSnoozeTimeKey(snoozedAlarm), 0);
            if (alarmTime < snoozeTime) {
                final int alarmId = Integer.parseInt(snoozedAlarm);
                clearSnoozePreference(context, prefs, alarmId);
            }
        }
    }

    /**
     * A convenience method to set an alarm in the Alarms
     * content provider.
     * @return Time when the alarm will fire. Or < 1 if update failed.
     */
    public static long setAlarm(Context context, Alarm alarm) {
        ContentValues values = Alarm.createContentValues(alarm);
        ContentResolver resolver = context.getContentResolver();
        long rowsUpdated = resolver.update(ContentUris.withAppendedId(Alarm.CONTENT_URI, alarm.id),
                values, null, null);
        if (rowsUpdated < 1) {
            Log.e("Error updating alarm " + alarm);
            return rowsUpdated;
        }

        long timeInMillis = alarm.calculateAlarmTime();
        if (alarm.enabled) {
            // Disable the snooze if we just changed the snoozed alarm. This
            // only does work if the snoozed alarm is the same as the given
            // alarm.
            // TODO: disableSnoozeAlert should have a better name.
            disableSnoozeAlert(context, alarm.id);

            // Disable the snooze if this alarm fires before the snoozed alarm.
            // This works on every alarm since the user most likely intends to
            // have the modified alarm fire next.
            clearSnoozeIfNeeded(context, timeInMillis);
        }

        setNextAlert(context);

        return timeInMillis;
    }

    /**
     * A convenience method to enable or disable an alarm.
     *
     * @param id             corresponds to the _id column
     * @param enabled        corresponds to the ENABLED column
     */

    public static void enableAlarm(Context context, long id, boolean enabled) {
        enableAlarmInternal(context, id, enabled);
        setNextAlert(context);
    }

    private static void enableAlarmInternal(Context context, long id, boolean enabled) {
        enableAlarmInternal(context, Alarm.getAlarm(context.getContentResolver(), id), enabled);
    }

    private static void enableAlarmInternal(final Context context,
            final Alarm alarm, boolean enabled) {
        if (alarm == null) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues(2);
        values.put(Alarm.ENABLED, enabled ? 1 : 0);

        if (!enabled) {
            // Clear the snooze if the id matches.
            disableSnoozeAlert(context, alarm.id);
        }

        resolver.update(ContentUris.withAppendedId(Alarm.CONTENT_URI, alarm.id),
                values, null, null);
    }

    private static Pair<Alarm, Long> calculateNextAlert(final Context context) {
        long minTime = Long.MAX_VALUE;
        long now = System.currentTimeMillis();
        final SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, 0);

        Set<Alarm> alarms = new HashSet<Alarm>();

        // We need to to build the list of alarms from both the snoozed list and the scheduled
        // list.  For a non-repeating alarm, when it goes of, it becomes disabled.  A snoozed
        // non-repeating alarm is not in the active list in the database.

        // first go through the snoozed alarms
        final Set<String> snoozedIds = prefs.getStringSet(PREF_SNOOZE_IDS, new HashSet<String>());
        for (String snoozedAlarm : snoozedIds) {
            final int alarmId = Integer.parseInt(snoozedAlarm);
            final Alarm a = Alarm.getAlarm(context.getContentResolver(), alarmId);
            alarms.add(a);
        }
        // Now add the scheduled alarms
        alarms.addAll(Alarm.getAlarms(context.getContentResolver(), Alarm.ENABLED + "=1"));

        Pair<Alarm, Long> alarmResult = null;
        for (Alarm a : alarms) {
            // Update the alarm if it has been snoozed
            Long alarmTime = hasAlarmBeenSnoozed(prefs, a.id) ?
                    prefs.getLong(getAlarmPrefSnoozeTimeKey(a.id), -1) :
                    a.calculateAlarmTime();

            if (alarmTime < now) {
                Log.v("Disabling expired alarm set for " + Log.formatTime(alarmTime));
                // Expired alarm, disable it and move along.
                enableAlarmInternal(context, a, false);
                continue;
            }
            if (alarmTime < minTime) {
                minTime = alarmTime;
                alarmResult = Pair.create(a, minTime);
            }
        }

        return alarmResult;
    }

    /**
     * Disables non-repeating alarms that have passed.  Called at
     * boot.
     */
    public static void disableExpiredAlarms(final Context context) {
        List<Alarm> alarms = Alarm.getAlarms(context.getContentResolver(), Alarm.ENABLED + "=1");
        long now = System.currentTimeMillis();
        for (Alarm alarm : alarms) {
            // Ignore repeatable alarms
            if (alarm.daysOfWeek.isRepeating()) {
                continue;
            }

            long alarmTime = alarm.calculateAlarmTime();
            if (alarmTime < now) {
                Log.v("Disabling expired alarm set for " + Log.formatTime(alarmTime));
                enableAlarmInternal(context, alarm, false);
            }
        }
    }

    /**
     * Called at system startup, on time/timezone change, and whenever
     * the user changes alarm settings.  Activates snooze if set,
     * otherwise loads all alarms, activates next alert.
     */
    public static void setNextAlert(final Context context) {
        Pair<Alarm, Long> alarm = calculateNextAlert(context);
        if (alarm != null) {
            enableAlert(context, alarm.first, alarm.second);
        } else {
            disableAlert(context);
        }
        Intent i = new Intent(NEXT_ALARM_TIME_SET);
        context.sendBroadcast(i);
    }

    /**
     * Sets alert in AlarmManger and StatusBar.  This is what will
     * actually launch the alert when the alarm triggers.
     *
     * @param alarm Alarm.
     * @param atTimeInMillis milliseconds since epoch
     */
    private static void enableAlert(Context context, final Alarm alarm,
            final long atTimeInMillis) {
        AlarmManager am = (AlarmManager)
                context.getSystemService(Context.ALARM_SERVICE);

        // Intentionally verbose: always log the alarm time to provide useful
        // information in bug reports.
        Log.v("Alarm set for id=" + alarm.id + " " + Log.formatTime(atTimeInMillis));

        Intent intent = new Intent(ALARM_ALERT_ACTION);

        // XXX: This is a slight hack to avoid an exception in the remote
        // AlarmManagerService process. The AlarmManager adds extra data to
        // this Intent which causes it to inflate. Since the remote process
        // does not know about the Alarm class, it throws a
        // ClassNotFoundException.
        //
        // To avoid this, we marshall the data ourselves and then parcel a plain
        // byte[] array. The AlarmReceiver class knows to build the Alarm
        // object from the byte[] array.
        Parcel out = Parcel.obtain();
        alarm.writeToParcel(out, 0);
        out.setDataPosition(0);
        intent.putExtra(ALARM_RAW_DATA, out.marshall());

        PendingIntent sender = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);

        if (Utils.isKeyLimePieOrLater()) {
            am.setExact(AlarmManager.RTC_WAKEUP, atTimeInMillis, sender);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, atTimeInMillis, sender);
        }

        setStatusBarIcon(context, true);

        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(atTimeInMillis);
        String timeString = formatDayAndTime(context, c);
        saveNextAlarm(context, timeString);
    }

    /**
     * Disables alert in AlarmManager and StatusBar.
     *
     * @param context The context
     */
    static void disableAlert(Context context) {
        AlarmManager am = (AlarmManager)
                context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent sender = PendingIntent.getBroadcast(
                context, 0, new Intent(ALARM_ALERT_ACTION),
                PendingIntent.FLAG_CANCEL_CURRENT);
        am.cancel(sender);
        setStatusBarIcon(context, false);
        // Intentionally verbose: always log the lack of a next alarm to provide useful
        // information in bug reports.
        Log.v("No next alarm");
        saveNextAlarm(context, "");
    }

    // Snoozes are affected by timezone changes, but this shouldn't be a problem
    // for real use-cases.
    static void saveSnoozeAlert(Context context, long id, long time) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, 0);
        if (id == Alarm.INVALID_ID) {
            clearAllSnoozePreferences(context, prefs);
        } else {
            final Set<String> snoozedIds =
                    prefs.getStringSet(PREF_SNOOZE_IDS, new HashSet<String>());
            snoozedIds.add(Long.toString(id));
            final SharedPreferences.Editor ed = prefs.edit();
            ed.putStringSet(PREF_SNOOZE_IDS, snoozedIds);
            ed.putLong(getAlarmPrefSnoozeTimeKey(id), time);
            ed.apply();
        }
        // Set the next alert after updating the snooze.
        setNextAlert(context);
    }

    private static String getAlarmPrefSnoozeTimeKey(long id) {
        return getAlarmPrefSnoozeTimeKey(Long.toString(id));
    }

    private static String getAlarmPrefSnoozeTimeKey(String id) {
        return PREF_SNOOZE_TIME + id;
    }

    /**
     * Disable the snooze alert if the given id matches the snooze id.
     */
    static void disableSnoozeAlert(Context context, long id) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, 0);
        if (hasAlarmBeenSnoozed(prefs, id)) {
            // This is the same id so clear the shared prefs.
            clearSnoozePreference(context, prefs, id);
        }
    }

    // Helper to remove the snooze preference. Do not use clear because that
    // will erase the clock preferences. Also clear the snooze notification in
    // the window shade.
    private static void clearSnoozePreference(Context context, SharedPreferences prefs, long id) {
        final String alarmStr = Long.toString(id);
        final Set<String> snoozedIds =
                prefs.getStringSet(PREF_SNOOZE_IDS, new HashSet<String>());
        if (snoozedIds.contains(alarmStr)) {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel((int)id);
        }

        final SharedPreferences.Editor ed = prefs.edit();
        snoozedIds.remove(alarmStr);
        ed.putStringSet(PREF_SNOOZE_IDS, snoozedIds);
        ed.remove(getAlarmPrefSnoozeTimeKey(alarmStr));
        ed.apply();
    }

    private static void clearAllSnoozePreferences(final Context context,
            final SharedPreferences prefs) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        final Set<String> snoozedIds =
                prefs.getStringSet(PREF_SNOOZE_IDS, new HashSet<String>());
        final SharedPreferences.Editor ed = prefs.edit();
        for (String snoozeId : snoozedIds) {
            nm.cancel(Integer.parseInt(snoozeId));
            ed.remove(getAlarmPrefSnoozeTimeKey(snoozeId));
        }

        ed.remove(PREF_SNOOZE_IDS);
        ed.apply();
    }

    private static boolean hasAlarmBeenSnoozed(SharedPreferences prefs, long alarmId) {
        final Set<String> snoozedIds = prefs.getStringSet(PREF_SNOOZE_IDS, null);

        // Return true if there a valid snoozed alarmId was saved
        return snoozedIds != null && snoozedIds.contains(Long.toString(alarmId));
    }

    /**
     * Tells the StatusBar whether the alarm is enabled or disabled
     */
    private static void setStatusBarIcon(Context context, boolean enabled) {
        Intent alarmChanged = new Intent("android.intent.action.ALARM_CHANGED");
        alarmChanged.putExtra("alarmSet", enabled);
        context.sendBroadcast(alarmChanged);
    }

    /* used by AlarmAlert */
    static String formatTime(final Context context, Calendar c) {
        String format = get24HourMode(context) ? M24 : M12;
        return (c == null) ? "" : (String)DateFormat.format(format, c);
    }

    /**
     * Shows day and time -- used for lock screen
     */
    private static String formatDayAndTime(final Context context, Calendar c) {
        String format = get24HourMode(context) ? DM24 : DM12;
        return (c == null) ? "" : (String)DateFormat.format(format, c);
    }

    /**
     * Save time of the next alarm, as a formatted string, into the system
     * settings so those who care can make use of it.
     */
    static void saveNextAlarm(final Context context, String timeString) {
        Log.v("Setting next alarm string in system to " +
                (TextUtils.isEmpty(timeString) ? "null" : timeString));
        Settings.System.putString(context.getContentResolver(),
                                  Settings.System.NEXT_ALARM_FORMATTED,
                                  timeString);
    }

    /**
     * @return true if clock is set to 24-hour mode
     */
    public static boolean get24HourMode(final Context context) {
        return android.text.format.DateFormat.is24HourFormat(context);
    }
}
