package starking.eccles.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import starking.eccles.agent.MajorActivity;
import starking.eccles.agent.R;
import starking.eccles.receivers.EcclesReceiver;

public class EcclesNotifier {

	public static final String CHANNEL_TASK= "eccles_agent_task";
	public static final String CHANNEL_CONFIRM= "eccles_agent_confirm";
	public static final int ID_TASK= 4101;
	public static final int ID_CONFIRM= 4102;
	public static final int ID_CAPTURE= 4103;

	public static void createChannels(Context c){
		if(Build.VERSION.SDK_INT < 26) return;
		NotificationManager nm= (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationChannel task= new NotificationChannel(CHANNEL_TASK,"Agent activity",NotificationManager.IMPORTANCE_LOW);
		task.setDescription("Shown while Eccles Agent is working on a task");
		task.setShowBadge(false);
		nm.createNotificationChannel(task);
		NotificationChannel confirm= new NotificationChannel(CHANNEL_CONFIRM,"Confirmations",NotificationManager.IMPORTANCE_HIGH);
		confirm.setDescription("Asks before the agent does something that cannot be undone");
		nm.createNotificationChannel(confirm);
	}

	private static PendingIntent broadcast(Context c,String action,int req){
		Intent i= new Intent(c,EcclesReceiver.class).setAction(action);
		return PendingIntent.getBroadcast(c,req,i,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	public static Notification ongoing(Context c,String text){
		PendingIntent open= PendingIntent.getActivity(c,0,new Intent(c,MajorActivity.class),PendingIntent.FLAG_IMMUTABLE);
		return new NotificationCompat.Builder(c,CHANNEL_TASK)
			.setSmallIcon(R.drawable.ic_bolt)
			.setContentTitle("Eccles Agent is working")
			.setContentText(text)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setContentIntent(open)
			.addAction(R.drawable.ic_stop,"Stop",broadcast(c,EcclesReceiver.ACTION_STOP,1))
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.build();
	}

	/** Persistent notice while screen capture is granted; Stop revokes it. */
	public static Notification capture(Context c){
		PendingIntent open= PendingIntent.getActivity(c,0,new Intent(c,MajorActivity.class),PendingIntent.FLAG_IMMUTABLE);
		return new NotificationCompat.Builder(c,CHANNEL_TASK)
			.setSmallIcon(R.drawable.ic_shield)
			.setContentTitle("Screen capture is on")
			.setContentText("Eccles can take screenshots while a task runs. Tap Stop to revoke.")
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setContentIntent(open)
			.addAction(R.drawable.ic_stop,"Stop",broadcast(c,EcclesReceiver.ACTION_CAPTURE_STOP,4))
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.build();
	}

	public static void updateOngoing(Context c,String text){
		try{
			NotificationManagerCompat.from(c).notify(ID_TASK,ongoing(c,text));
		} catch (SecurityException ignored){
			// POST_NOTIFICATIONS not granted: the task still runs, there is just nothing to update.
		}
	}

	public static void confirm(Context c,String question){
		try{
			Notification n= new NotificationCompat.Builder(c,CHANNEL_CONFIRM)
				.setSmallIcon(R.drawable.ic_shield)
				.setContentTitle("Confirm action")
				.setContentText(question)
				.setStyle(new NotificationCompat.BigTextStyle().bigText(question))
				.setPriority(NotificationCompat.PRIORITY_HIGH)
				.setCategory(NotificationCompat.CATEGORY_ALARM)
				.setAutoCancel(true)
				.addAction(R.drawable.ic_check,"Allow",broadcast(c,EcclesReceiver.ACTION_ALLOW,2))
				.addAction(R.drawable.ic_close,"Deny",broadcast(c,EcclesReceiver.ACTION_DENY,3))
				.build();
			NotificationManagerCompat.from(c).notify(ID_CONFIRM,n);
		} catch (SecurityException ignored){
		}
	}

	/** Final outcome, shown when the console is not on screen. */
	public static void result(Context c,String title,String text){
		try{
			PendingIntent open= PendingIntent.getActivity(c,0,new Intent(c,MajorActivity.class),PendingIntent.FLAG_IMMUTABLE);
			Notification n= new NotificationCompat.Builder(c,CHANNEL_TASK)
				.setSmallIcon(R.drawable.ic_bolt)
				.setContentTitle(title)
				.setContentText(text)
				.setStyle(new NotificationCompat.BigTextStyle().bigText(text))
				.setContentIntent(open)
				.setAutoCancel(true)
				.build();
			NotificationManagerCompat.from(c).notify(ID_TASK + 10,n);
		} catch (SecurityException ignored){
		}
	}

	public static void cancelConfirm(Context c){
		NotificationManagerCompat.from(c).cancel(ID_CONFIRM);
	}
}
