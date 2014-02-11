
package ustc.ssqstone.xueba;

import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

/**
 * 发现一个bug，studyEn的值会在我看不到的地方发生翻转。
 */


/**
 * 开机或在设置程序中按确定按钮均会开启此Service, 该service一直在后台执行所要进行的监视. 
 * 设置Activity在一个pref文件中储存当前的设置, 此Service只读取设置, 不会改变设置. 
 * **注:因为要加入强制晚睡等规则, 所以在最终的实现上service是会改变pref文件的. 
 * **原则是先写好基本功能再加强制机制. 只要在service的onBeginCommand函数最前方插入强制机制就可以了. 
 * 
 * 监视频率是可变的, 如果出现违规, 就唤醒RestrictedModeActivity. 
 * 
 * 该Activity会按照intent附加的信息启动相应的界面. (附加描述是睡觉还是学习)
 * 监视的方式是查看最上层的activity, 发现不在允许的包内, 就调用punish函数, 调出RestrictedModeActivity. 
 * 到任务时间后就要求退出RestrictedModeActivity. 
 * 
 * @author ssqstone
 */

/**
* 在同一个应用任何地方调用 startService() 方法就能启动 Service 了，然后系统会回调 Service 类的 onCreate() 以及 onBegin() 方法。
* 这样启动的 Service 会一直运行在后台，直到 Context.stopService() 或者 selfStop() 方法被调用。
* 如果一个 Service 已经被启动，其他代码再试图调用 startService() 方法，是不会执行 onCreate() 的，但会重新执行一次 onBegin() 。
* 
* 为了方便通过放弃任务而修改任务时间, 数据应在onBeginCommand方法里载入. 
*/
public class MonitorService extends Service
{
	private static final String	LAST_SURF_DATE	= "last surf date";
	private static final String	SURF_TIME_OF_S	= "surf time of ";
	private static final String SURF_TIME_OF_TODAY_S = "surf time of today: ";
	private boolean screenLocked=false;
	private static final int	SMS	= 2;
	private static final int	TOAST	= 3;
//	private static final int PUNISH=1;
	private static final int	TO_RESTRICT	= 4;
	private static final String	SURF_TIME_LOG	= "surf_time_log";
//	private static final int	INT_ACC	= 5;
//	protected static final int	INT_ZERO	= 6;
	/**
	 * 单位是毫秒. 
	 */
	private int checkInterval;
	private boolean informed;
	static private Handler handler;
	
	protected enum Status
	{
		sleeping_noon("午觉"),sleeping_night("睡觉"), studying("学习"), halting("等待"), error("错误");
		
		private String chineseString;
		
		private Status(String chinese)
		{
			this.chineseString = chinese;
		}
		
		public String getLocalString()
		{
			return chineseString;
		}
	}
	
	@Override
	public void onCreate()
	{
		super.onCreate();

		screenOffBroadcastReceiver = new BroadcastReceiver()
		{
			@Override
			public void onReceive(Context context, Intent intent)
			{
//				screenLocked=true;
				stopCurrentMonitorThread();
			}
		};
		screenOnBroadcastReceiver = new BroadcastReceiver()
		{
			@Override
			public void onReceive(Context context, Intent intent)
			{
//				screenLocked=false;
				startMonitorThread();
			}
		};
		
		handler = new Handler()
		{
			@Override
			public void handleMessage(Message msg)
			{
				switch (msg.what)
				{
					case SMS:
						SharedPreferences values = getSharedPreferences(XueBaYH.VALUES, MODE_PRIVATE);
						XueBaYH.getApp().sendSMS( (String)msg.obj, values.getString(XueBaYH.PHONE_NUM, XueBaYH.myself?XueBaYH.我的监督人s:XueBaYH.我s));
						break;
					case TOAST:
						XueBaYH.getApp().showToast((String)msg.obj);
						break;
					case TO_RESTRICT:
						Intent intent=new Intent(MonitorService.this,RestrictedModeActivity.class);
						intent.putExtra("ustc.ssqstone.xueba.status", status.getLocalString());
						intent.putExtra("ustc.ssqstone.xueba.start", startTime);
						intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						startActivity(intent);
						break;
					default:
						break;
				}
				super.handleMessage(msg);
			}
		};
	}

	/**
	 * 用在任务开启, 屏幕解锁, 或检查结束后调用. 
	 * 更新当前状态和监视时间步长. 
	 * 
	 * *注意: 状态完全由是否锁屏，是否开启三类任务决定。
	 */
	private void refreshStatus()
	{
		Calendar calendar=Calendar.getInstance();
		long now = calendar.getTimeInMillis();
		
		if (screenLocked)
		{
			status=Status.halting;
		}
		else if((nightEn)&&(nightBegin<now)&&(now<=nightEnd))
		{
			if ((status==Status.halting)||(status==null))
			{
				startTime = nightBegin;
			}
			informed = false;
			status=Status.sleeping_night;
//			setOffLine();		//TODO
		}
		else if((noonEn)&&(noonBegin<now)&&(now<=noonEnd))
		{
			if ((status==Status.halting)||(status==null))
			{
				startTime = noonBegin;
			}
			status=Status.sleeping_noon;
			informed = false;
//			setOffLine();		//TODO
		}
		else if ((studyEn)&&(now<=studyEnd))
		{
			if ((status==Status.halting)||(status==null))
			{
				startTime = studyBegin;
			}
			status=Status.studying;
			informed = false;
		}
		else
		{
			status=Status.halting;
		}
		
		//预告即将进入睡眠
		if ((!screenLocked)&&(!informed))
		{
			Status status = Status.halting;
			boolean inform = false;
			if((nightEn)&&(nightBegin - 30000 <=now)&&(now<nightBegin))
			{
				informed = true;
				inform = true;
				status = Status.sleeping_night;
			}
			else if((noonEn)&&(noonBegin - 30000 <=now)&&(now<noonBegin))
			{
				informed = true;
				inform = true;
				status = Status.sleeping_noon;
			}
			
			if (inform)
			{
				XueBaYH.getApp().showToast("请注意:\n距离开始"+status.getLocalString()+"还有不到30秒! ");
				XueBaYH.getApp().vibrateOh();
			}
		}
	}

	private long nightBegin,nightEnd,noonBegin,noonEnd,studyEnd,studyBegin;
	private boolean nightEn,noonEn,studyEn;
	/**
	 * 应该把信息独立出来, 打开Service时只读取一次. 否则频繁读取不变的文件真是脑抽了.
	 */
	private void loadStatus()
	{
		SharedPreferences sharedPreferences = getSharedPreferences("values",MODE_PRIVATE);
		
		noonEn=sharedPreferences.getBoolean("noon_en", false);
		nightEn=sharedPreferences.getBoolean("night_en", false);
		studyEn=sharedPreferences.getBoolean("study_en", false);

		studyBegin=sharedPreferences.getLong("study_begin", 0);
		studyEnd=sharedPreferences.getLong("study_end", 0);
		noonBegin=sharedPreferences.getLong("noon_begin", 0);
		noonEnd=sharedPreferences.getLong("noon_end", 0);
		nightBegin=sharedPreferences.getLong("night_begin", 0);
		nightEnd=sharedPreferences.getLong("night_end", 0);

		Calendar calendar=Calendar.getInstance();
		Editor editor = getSharedPreferences(XueBaYH.VALUES, MODE_PRIVATE).edit();
		boolean removeRestriction = false;
		
		if(studyEn && (calendar.getTimeInMillis()>studyEnd))
		{
			editor.putBoolean(XueBaYH.STUDY_EN, false);
			studyEn = false;
			removeRestriction = true;
		}
		if(noonEn && (calendar.getTimeInMillis()>noonEnd))
		{
			editor.putBoolean(XueBaYH.NOON_EN, false);
			noonEn=false;
			removeRestriction = true;
//			setOnLine();		//TODO
		}
		if(nightEn && (calendar.getTimeInMillis()>nightEnd))
		{
			editor.putBoolean(XueBaYH.NIGHT_EN, false);
			nightEn = false;
			removeRestriction = true;
//			setOnLine();		//TODO
		}
		
		if (removeRestriction)
		{
			editor.commit();
			editor.putLong(XueBaYH.PARITY, XueBaYH.getApp().getParity());
			editor.commit();
			
			Intent intent = new Intent(MonitorService.this,RestrictedModeActivity.class);
			intent.putExtra("ustc.ssqstone.xueba.destroy", true);
			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(intent);
		}
		
		refreshCheckInterval();
	}

	private Status status;
	private BroadcastReceiver screenOnBroadcastReceiver;
	private BroadcastReceiver screenOffBroadcastReceiver;
	private InformTimeOutTask monitorTask;
	private Thread monitorThread;
//	private final int NOTIFICATION_ID=1;
//	private final int UPDATE = 2; 
	
	@Override public int onStartCommand(Intent intent, int flags, int startId)
	{
		super.onStartCommand(intent,flags,startId);
		
		loadStatus();
		
		IntentFilter intentFilter;
		intentFilter = new IntentFilter();
		intentFilter.addAction(Intent.ACTION_SCREEN_ON);
		registerReceiver(screenOnBroadcastReceiver, intentFilter);
		
		intentFilter = new IntentFilter();
		intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
		registerReceiver(screenOffBroadcastReceiver, intentFilter);

//		startForeground(NOTIFICATION_ID, updateNotification());
		
		startMonitorThread();
		return START_STICKY;
	}
	
	/**
	 * 释放监听器;
	 * 释放监视线程;
	 * 保存各种记录. 
	 * 通知监督人应用被关闭了. 
	 * 
	 * 调试发现卸载app或者关机的时候不会调用onDestroy. 
	 */
	@Override
	public void onDestroy()
	{
//		Message message = new Message();
//		message.what = SMS;
//		message.obj = "我很可能偷偷关闭了学霸银魂, 这是极端恶劣的行为. ";
//		handler.sendMessage(message);
		
		unregisterReceiver(screenOffBroadcastReceiver);
		unregisterReceiver(screenOnBroadcastReceiver);
		stopCurrentMonitorThread();
		
		super.onDestroy();
		
		XueBaYH.getApp().restartMonitorService();
	}

	/**
	 * 随意弄一个校验
	 * 
	 * 注意: 在log文件里校验的方法是加入条目"last time", 然后对last time 和last time 指向的数据进行校验. 
	 * @return 校验码
	 */
	private long surfTimeParity()
	{
		SharedPreferences log= getSharedPreferences(SURF_TIME_LOG, MODE_PRIVATE);
		String lastSurfDateString = log.getString(LAST_SURF_DATE, "");
		String surfTimeIndexString = SURF_TIME_OF_S+lastSurfDateString;
		
		return (long) log.getFloat(surfTimeIndexString, 12)*(Math.abs(lastSurfDateString.hashCode())%2354667);
	}
	
	/**
	 * 监视线程. 检查活动activity是不是被允许的. 
	 * 时间步长根据当前需要监视的状态确定. 
	 * 每次锁屏后第一次运行主循环, 会停止. 开屏后, 又开启新的实例. 
	 * 
	 * @author ssqstone
	 */
	private class InformTimeOutTask implements Runnable
	{
		private ConditionVariable mConditionVariable = new ConditionVariable(false);
		
		/**
		 * 主循环内检查是否锁屏在最先, 等待延时结束在最后, 最节省运行时间. 
		 */
		@Override
		public void run()
		{
//			mConditionVariable.block(2000);
			
			while (true)//(!screenLocked)
			{
				if (notPermitted())
				{
					Message message=new Message();
					message.what=TO_RESTRICT;
					handler.sendMessage(message);
//					toRestrict();
				}
				loadStatus();
//				mConditionVariable.close();
//				handler.sendEmptyMessage(UPDATE);
				if (mConditionVariable.block(checkInterval))
				{
					return;
				}
			}
		}
	}

	/**
	 * 根据是否在监视时间段, 判断检查时间: 锁屏时几乎关闭(随便给一个很长的值); 不在监视时间段,1分钟; 在监视时间段, 0.1秒钟. 
	 * 虽然0.1秒钟的检查频率比较高, 但锁屏的时候监视线程被释放, 不会浪费运行资源. 设检查频率为0.1秒是为了防止有时间调到主屏幕删除该应用. 
	 */
	private void refreshCheckInterval()
	{
		refreshStatus();
		switch (status)
		{
			case halting:
				checkInterval=XueBaYH.debug?300:10000;
				break;

			default:
				checkInterval = 300;
				break;
		}
//		monitorTask.mConditionVariable.open();
	}
	
	private long startTime;
	
//	private float surfTimeValue;
//	private long lastWrite;
	/**
	 * 
	 * @return 当前活动界面不应该出现 (sleep 模式下不是restrictedActivity, study模式下不是电话, 联系人, 短信或者restrictedActivity, 空闲模式下上网时间不能超过1小时)
	 */
	private boolean notPermitted()
	{
		ActivityManager activityManager = (ActivityManager)XueBaYH.getApp().getSystemService(ACTIVITY_SERVICE);
		List<RunningTaskInfo> runningTaskInfos = activityManager.getRunningTasks(1);
		RunningTaskInfo runningTaskInfo = runningTaskInfos.get(0);
		runningTaskInfo = (ActivityManager.RunningTaskInfo)(runningTaskInfo);
		ComponentName localComponentName = runningTaskInfo.topActivity;
		
	    String packageName = localComponentName.getPackageName();
	    
		if (status==Status.halting)
		{
			SharedPreferences log= getSharedPreferences(XueBaYH.VALUES, MODE_PRIVATE);
			if ("com.UCMobile com.uc.browser com.android.chrome com.android.browser com.dolphin.browser.xf com.tencent.mtt sogou.mobile.explorer com.baidu.browser.apps com.oupeng.mini.android ".contains(packageName))
			{
				//应该给浏览器计时了. 先看是不是到了新的一天. 
				SharedPreferences log= getSharedPreferences(SURF_TIME_LOG, MODE_PRIVATE);
				if (log.getLong(XueBaYH.PARITY, 0)!=surfTimeParity())
				{
					Message message = new Message();
					message.what = SMS;
					message.obj = "我已经开启了节制上网功能, 每天一个小时. 如果您多次收到本条短信, 说明我修改甚至清空了数据, 这是不好的行为. ";
					handler.sendMessage(message);
				}

				Editor logEditor = log.edit();
				String surfTimeIndexString = SURF_TIME_OF_S+XueBaYH.getSimpleDate(Calendar.getInstance().getTimeInMillis());
				float surfTimeValue = log.getFloat(surfTimeIndexString, 0) + ((float)checkInterval/1000);
				
				logEditor.putString(LAST_SURF_DATE, XueBaYH.getSimpleDate(Calendar.getInstance().getTimeInMillis()));
				logEditor.putFloat(surfTimeIndexString, surfTimeValue);
				logEditor.commit();
				logEditor.putLong(XueBaYH.PARITY, surfTimeParity());
				logEditor.commit();
				
				if ( (surfTimeValue>=1800) && (surfTimeValue< 3600 ) && ((int)surfTimeValue%180==0))
				{
					Message message = new Message();
					message.what = TOAST;
					message.obj = "请注意, 你已开启上网限制, 今天上网时间还有"+(int) ((3600-surfTimeValue)/60)+"分";
					handler.sendMessage(message);
				}
				else if (surfTimeValue>=3600)
				{
					Message message = new Message();
					message.what = TOAST;
					message.obj = "你不觉得今天上网时间太长了么? \n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n这个不能发短信, 无解. ";
					handler.sendMessage(message);
//					return true;						在上网超时的时候只生成Toast, 不开启限制界面. 
				}
			}
			else
			{
				handler.removeMessages(TOAST);
			}
			return false;
		}
		else
		{
		    String permitted = "ustc.ssqstone.xueba GSW.AddinTimer com.zdworks.android.zdclock com.dianxinos.clock com.android.phone com.android.contacts com.android.mms com.jb.gosms-1 org.dayup.gnotes "+((status==Status.studying)?("com.snda.youni cn.ssdl.bluedict com.ghisler.android.TotalCommander udk.android.reader jp.ne.kutu.Panecal com.diotek.diodict3.phone.samsung.chn com.docin.zlibrary.ui.android com.towords com.youdao.note com.duokan.reader com.baidu.wenku com.nd.android.pandareader com.qq.reader com.lectek.android.sfreader bubei.tingshu de.softxperience.android.noteeverything "):""); //, com.launcher.air
		    
			return !permitted.contains(packageName);
		}
	}
	
	@Override
	public IBinder onBind(Intent arg0)
	{
		return null;
	}
	
	/**
	 * 开始监视进程
	 */
	private void startMonitorThread()
	{
		if (monitorThread !=null)
		{
			stopCurrentMonitorThread();
		}
//		screenLocked = false;
		monitorTask = new InformTimeOutTask();
		monitorThread = new Thread(null, monitorTask, "Monitoring");
		monitorThread.start();
	}
	
	/**
	 * 完全释放监视进程
	 */
	private void stopCurrentMonitorThread()
	{
//		screenLocked = true;
		monitorTask.mConditionVariable.open();
	}
	
//	private Notification updateNotification()
//	{
//		Notification notification;
//		NotificationManager mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
//		RemoteViews contentView;
//		Intent intent;
//		PendingIntent contentIntent;
//		String string;
//		
//		string = "学霸银魂正在挽救新一代青年! ";
//		notification = new Notification(R.drawable.ic_launcher, string, System.currentTimeMillis());
//		notification.flags = Notification.FLAG_NO_CLEAR;
//		contentView = new RemoteViews(this.getPackageName(), R.layout.notification);
//		
//		if ((status == Status.studying) || (status == Status.sleeping_night)|| (status == Status.sleeping_noon))
//		{
//			contentView.setTextViewText(R.id.text, "按理说应该在" + status.getLocalString() + "的. \n法网恢恢, 请君自重. ");
//
//			/*intent = new Intent("android.intent.action.DIAL");
//			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
//			contentIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
//			contentView.setOnClickPendingIntent(R.id.dial_b, contentIntent);
//			*/
////		TODO	contentView.setOnClickFillInIntent(viewId, fillInIntent)
//			
//			
////			intent = new Intent("android.intent.action.SENDTO");
////			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
////			contentIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
////			contentView.setOnClickPendingIntent(R.id.dial_b, contentIntent);
//			/*Bundle bundle=new Bundle();
//			bundle.putString("intent", "android.intent.action.DIAL");
//			contentView.setBundle(R.id.dial_b, "setOnClickListenerByBundle", bundle);
//
//			bundle.putString("intent", "android.intent.action.SENDTO");
//			contentView.setBundle(R.id.sms_b, "setOnClickListenerByBundle", bundle);*/
//			
//		}
//		else
//		{
//			contentView.setTextViewText(R.id.text, "法网恢恢, 请君自重. ");
//		}
//
//		contentView.setImageViewBitmap(R.id.icon, ((BitmapDrawable)getResources().getDrawable(R.drawable.ic_launcher)).getBitmap());
//		notification.contentView = contentView;
//		intent = new Intent(this, MainActivity.class);
//		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
//		contentIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
//		notification.contentIntent = contentIntent;
//		mNotificationManager.notify(NOTIFICATION_ID, notification);
//		
//		return notification;
//	}
}

///**
// * 
// * @author zhangyg
// * 
// */
//class ScreenObserver
//{
//	private static String TAG = "ScreenObserver";
//	protected Context mContext;
//	private ScreenBroadcastReceiver mScreenReceiver;
//	private ScreenStateListener mScreenStateListener;
//	private static Method mReflectScreenState;
//
//	public ScreenObserver(Context context)
//	{
//		mContext = context;
//		mScreenReceiver = new ScreenBroadcastReceiver();
//		try
//		{
//			mReflectScreenState = PowerManager.class.getMethod("isScreenOn",
//					new Class[] {});
//		} catch (NoSuchMethodException nsme)
//		{
//			Log.d(TAG, "API < 7," + nsme);
//		}
//	}
//
//	/**
//	 * screen状态广播接收者
//	 * 
//	 * @author zhangyg
//	 * 
//	 */
//	private class ScreenBroadcastReceiver extends BroadcastReceiver
//	{
//		private String action = null;
//
//		@Override
//		public void onReceive(Context context, Intent intent)
//		{
//			action = intent.getAction();
//			if (Intent.ACTION_SCREEN_ON.equals(action))
//			{
//				mScreenStateListener.onScreenOn();
//			} else if (Intent.ACTION_SCREEN_OFF.equals(action))
//			{
//				mScreenStateListener.onScreenOff();
//			}
//		}
//	}
//
//	/**
//	 * 请求screen状态更新
//	 * 
//	 * @param listener
//	 */
//	public void requestScreenStateUpdate(ScreenStateListener listener)
//	{
//		mScreenStateListener = listener;
//		startScreenBroadcastReceiver();
//
//		firstGetScreenState();
//	}
//
//	/**
//	 * 第一次请求screen状态
//	 */
//	private void firstGetScreenState()
//	{
//		PowerManager manager = (PowerManager) mContext
//				.getSystemService(Activity.POWER_SERVICE);
//		if (isScreenOn(manager))
//		{
//			if (mScreenStateListener != null)
//			{
//				mScreenStateListener.onScreenOn();
//			}
//		} else
//		{
//			if (mScreenStateListener != null)
//			{
//				mScreenStateListener.onScreenOff();
//			}
//		}
//	}
//
//	/**
//	 * 停止screen状态更新
//	 */
//	public void stopScreenStateUpdate()
//	{
//		mContext.unregisterReceiver(mScreenReceiver);
//	}
//
//	/**
//	 * 启动screen状态广播接收器
//	 */
//	private void startScreenBroadcastReceiver()
//	{
//		IntentFilter filter = new IntentFilter();
//		filter.addAction(Intent.ACTION_SCREEN_ON);
//		filter.addAction(Intent.ACTION_SCREEN_OFF);
//		mContext.registerReceiver(mScreenReceiver, filter);
//	}
//
//	/**
//	 * screen是否打开状态
//	 * 
//	 * @param pm
//	 * @return
//	 */
//	protected static boolean isScreenOn(PowerManager pm)
//	{
//		boolean screenState;
//		try
//		{
//			screenState = (Boolean) mReflectScreenState.invoke(pm);
//		} catch (Exception e)
//		{
//			screenState = false;
//		}
//		return screenState;
//	}
//
//	public interface ScreenStateListener
//	{
//		public void onScreenOn();
//
//		public void onScreenOff();
//	}
//}