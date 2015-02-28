/***
  Copyright (c) 2009-14 CommonsWare, LLC
  
  Licensed under the Apache License, Version 2.0 (the "License"); you may
  not use this file except in compliance with the License. You may obtain
  a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

package com.commonsware.cwac.wakeful;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.PowerManager;
import android.util.Log;

import java.util.List;

abstract public class WakefulIntentService extends IntentService {
  abstract protected void doWakefulWork(Intent intent);

  static final String NAME=
      "com.commonsware.cwac.wakeful.WakefulIntentService";
  static final String LAST_ALARM="lastAlarm";
  private static volatile PowerManager.WakeLock lockStatic=null;

  synchronized private static PowerManager.WakeLock getLock(Context context) {
    if (lockStatic == null) {
      PowerManager mgr=
          (PowerManager)context.getSystemService(Context.POWER_SERVICE);

      lockStatic=mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, NAME);
      lockStatic.setReferenceCounted(true);
    }

    return(lockStatic);
  }

  public static void sendWakefulWork(Context ctxt, Intent i) {
    getLock(ctxt.getApplicationContext()).acquire();
    Intent explicitIntent = createExplicitFromImplicitIntent(ctxt, i);
    ctxt.startService(i);
  }

  /**
   * Android L (lollipop, API 21) introduced a new problem when trying to invoke implicit intent,
   * "java.lang.IllegalArgumentException: Service Intent must be explicit"
   * <p/>
   * If you are using an implicit intent, and know only 1 target would answer this intent,
   * This method will help you turn the implicit intent into the explicit form.
   * <p/>
   * Inspired from SO answer: http://stackoverflow.com/a/26318757/1446466
   *
   * @param implicitIntent - The original implicit intent
   * @return Explicit Intent created from the implicit original intent
   */
  public static Intent createExplicitFromImplicitIntent(Context context, Intent implicitIntent) {
      // Retrieve all services that can match the given intent
      PackageManager pm = context.getPackageManager();
      List<ResolveInfo> resolveInfo = pm.queryIntentServices(implicitIntent, 0);

      // Make sure only one match was found
      if (resolveInfo == null || resolveInfo.size() != 1) {
          Log.e("WakefulIntentService", "Couldn't find a single match for intent " + implicitIntent +
                  ". Make sure you configured the intent correctly.");
          return null;
      }

      // Get component info and create ComponentName
      ResolveInfo serviceInfo = resolveInfo.get(0);
      String packageName = serviceInfo.serviceInfo.packageName;
      String className = serviceInfo.serviceInfo.name;
      ComponentName component = new ComponentName(packageName, className);

      // Create a new intent. Use the old one for extras and such reuse
      Intent explicitIntent = new Intent(implicitIntent);

      // Set the component to be explicit
      explicitIntent.setComponent(component);

      return explicitIntent;
  }


  public static void sendWakefulWork(Context ctxt, Class<?> clsService) {
    sendWakefulWork(ctxt, new Intent(ctxt, clsService));
  }

  public static void scheduleAlarms(AlarmListener listener, Context ctxt) {
    scheduleAlarms(listener, ctxt, true);
  }

  public static void scheduleAlarms(AlarmListener listener,
                                    Context ctxt, boolean force) {
    SharedPreferences prefs=ctxt.getSharedPreferences(NAME, 0);
    long lastAlarm=prefs.getLong(LAST_ALARM, 0);

    if (lastAlarm == 0
        || force
        || (System.currentTimeMillis() > lastAlarm && System.currentTimeMillis()
            - lastAlarm > listener.getMaxAge())) {
      AlarmManager mgr=
          (AlarmManager)ctxt.getSystemService(Context.ALARM_SERVICE);
      Intent i=new Intent(ctxt, AlarmReceiver.class);
      PendingIntent pi=PendingIntent.getBroadcast(ctxt, 0, i, 0);

      listener.scheduleAlarms(mgr, pi, ctxt);
    }
  }

  public static void cancelAlarms(Context ctxt) {
    AlarmManager mgr=
        (AlarmManager)ctxt.getSystemService(Context.ALARM_SERVICE);
    Intent i=new Intent(ctxt, AlarmReceiver.class);
    PendingIntent pi=PendingIntent.getBroadcast(ctxt, 0, i, 0);

    mgr.cancel(pi);

    ctxt.getSharedPreferences(NAME, 0).edit().remove(LAST_ALARM)
        .commit();
  }

  public WakefulIntentService(String name) {
    super(name);
    setIntentRedelivery(true);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    PowerManager.WakeLock lock=getLock(this.getApplicationContext());

    if (!lock.isHeld() || (flags & START_FLAG_REDELIVERY) != 0) {
      lock.acquire();
    }

    super.onStartCommand(intent, flags, startId);

    return(START_REDELIVER_INTENT);
  }

  @Override
  final protected void onHandleIntent(Intent intent) {
    try {
      doWakefulWork(intent);
    }
    finally {
      PowerManager.WakeLock lock=getLock(this.getApplicationContext());

      if (lock.isHeld()) {
        try {
          lock.release();
        }
        catch (Exception e) {
          Log.e(getClass().getSimpleName(),
              "Exception when releasing wakelock", e);
        }
      }
    }
  }

  public interface AlarmListener {
    void scheduleAlarms(AlarmManager mgr, PendingIntent pi, Context ctxt);

    void sendWakefulWork(Context ctxt);

    long getMaxAge();
  }
}
