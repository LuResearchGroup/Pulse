package tan.philip.nrf_ble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import tan.philip.nrf_ble.GraphScreen.GraphActivity;

public class NotificationHandler {

  public static final String CHANNEL_ID = "Notification Channel";
  public static final String CHANNEL_NAME = "Notification Channel";
  public static final String CHANNEL_DESCRIPTION = "Chanel for general notifications (disconnects, reconnects, etc.)";
  public static final int NOTIFICATION_DEFAULT_IMPORTANCE = NotificationManager.IMPORTANCE_DEFAULT;

  public static final int NOTIFICATION_ID = 1;                           //Used if we want to update or remove notification
  public static final int FOREGROUND_SERVICE_NOTIFICATION_ID = 2;        //Notification ID of the BLEHandlerService
  public static final int FOREGROUND_SERVICE_NOTIFICATION_ID_SICKBAY = 3;//Notification ID of the SickbayPushService

  private static NotificationManager notificationManager;



  public static void createNotificationChannel(Context context) {
    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      int importance = NOTIFICATION_DEFAULT_IMPORTANCE;
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance);

      channel.setDescription(CHANNEL_DESCRIPTION);
      channel.enableLights(true);
      channel.setLightColor(Color.RED);
      //channel.enableVibration(true);
      //channel.setVibrationPattern(new long[]{100, 200});  //Yeah idk how this works

      // Register the channel with the system; you can't change the importance
      // or other notification behaviors after this
      notificationManager = context.getSystemService(NotificationManager.class);
      notificationManager.createNotificationChannel(channel);
    }
  }

  public static void makeNotification(String title, String text, Context context) {
    NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.heartrate)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(new NotificationCompat.BigTextStyle()
                    .bigText(text));

    // notificationId is a unique int for each notification that you must define
    notificationManager.notify(NOTIFICATION_ID, builder.build());
  }

  public static void makeNotification(int id, Notification notification) {
    notificationManager.notify(id, notification);
  }
}