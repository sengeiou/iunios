/*
 * @author zw
 */
package com.aurora.iunivoice.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.TypedValue;

import com.aurora.iunivoice.IuniVoiceApp;

public class SystemUtils {
	private static String TAG = "SystemUtils";

	private SystemUtils() {
	}

	
	public static String getFromAssets(Context mcontent, String fileName) {
		StringBuffer sb = new StringBuffer();
		try {
			InputStreamReader inputReader = new InputStreamReader(mcontent
					.getResources().getAssets().open(fileName), "utf-8");
			BufferedReader bufReader = new BufferedReader(inputReader);
			String line = "";

			while ((line = bufReader.readLine()) != null)
				sb.append(line);
			return sb.toString();
		} catch (Exception e) {
			e.printStackTrace();
			FileLog.e(TAG, e.toString());
		}
		return sb.toString();
	}

	/**referenced from aurora launcher*/
	/**Get value from dimens list.other class can use it!*/
	public static float getFloatValueFromResourcesDimens(Context c,int id){
		TypedValue typedValue = new TypedValue();
		c.getResources().getValue(id, typedValue, true);
		return  typedValue.getFloat();
	}
	
	/**Get Integer value from dimens list.other class can use it!*/
	public static int getIntegerValueFromResourcesDimens(Context c,int id){
		return c.getResources().getInteger(id);
	}
	
	/**Get Boolean value from dimens list.other class can use it!*/
	public static boolean getBooleanValueFromResourcesDimens(Context c,int id){
		return c.getResources().getBoolean(id);
	}
	
	//Color转换为16进制显示
    public static String toHexEncoding(int red,int green,int blue) {
        String R, G, B;
        StringBuffer sb = new StringBuffer();

     /*   R = Integer.toHexString(Color.red(red));
        G = Integer.toHexString(Color.green(green));
        B = Integer.toHexString(Color.blue(blue));*/
        R = Integer.toHexString(red);
        G = Integer.toHexString(green);
        B = Integer.toHexString(blue);
        R = R.length() == 1 ? "0" + R : R;
        G = G.length() == 1 ? "0" + G : G;
        B = B.length() == 1 ? "0" + B : B;
        sb.append("#");
        sb.append(R.toUpperCase());
        sb.append(G.toUpperCase());
        sb.append(B.toUpperCase());
        return sb.toString();
    }
    

	public static boolean isSimReady(Context context) {
		TelephonyManager manager = (TelephonyManager) context
				.getSystemService(Context.TELEPHONY_SERVICE);

		int nSimState = manager.getSimState();

		return (nSimState == TelephonyManager.SIM_STATE_READY);
	}

	public static boolean isAirplaneModeOn(Context context) {
		return Settings.System.getInt(context.getContentResolver(),
				Settings.System.AIRPLANE_MODE_ON, 0) != 0;
	}

	public static boolean hasActiveNetwork(Context context) {
		ConnectivityManager connectivityManager = (ConnectivityManager) context
				.getSystemService("connectivity");
		if (connectivityManager.getActiveNetworkInfo() != null) {
			return connectivityManager.getActiveNetworkInfo().isAvailable();
		} else {
			return false;
		}
	}

	public static boolean isNetworkReady() {
		TelephonyManager manager = (TelephonyManager) IuniVoiceApp.getInstance()
				.getSystemService(Context.TELEPHONY_SERVICE);

		return (manager.getNetworkType() != TelephonyManager.NETWORK_TYPE_UNKNOWN);
	}

	public static boolean isNetworkAvailable() {
		ConnectivityManager manager = (ConnectivityManager) IuniVoiceApp
				.getInstance().getSystemService(Context.CONNECTIVITY_SERVICE);
		if (manager == null) {
			return false;
		} else {
			NetworkInfo[] info = manager.getAllNetworkInfo();
			if (info != null) {
				for (int i = 0; i < info.length; i++) {
					if (info[i].isAvailable()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public static boolean isNetworkConnected() {
		ConnectivityManager manager = (ConnectivityManager) IuniVoiceApp
				.getInstance().getSystemService(Context.CONNECTIVITY_SERVICE);
		if (manager == null) {
			return false;
		} else {
			NetworkInfo[] info = manager.getAllNetworkInfo();
			if (info != null) {
				for (int i = 0; i < info.length; i++) {
					if (info[i].isConnected()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public static boolean isWifiEnabled() {
		WifiManager manager = (WifiManager) IuniVoiceApp.getInstance()
				.getSystemService(Context.WIFI_SERVICE);

		return (manager == null ? false : manager.isWifiEnabled());
	}

	public static boolean isWifiAvailable() {
		WifiManager manager = (WifiManager) IuniVoiceApp.getInstance()
				.getSystemService(Context.WIFI_SERVICE);
		WifiInfo wifiInfo = manager.getConnectionInfo();
		String macAddress = (wifiInfo == null ? null : wifiInfo.getMacAddress());

		return (macAddress != null);
	}

	public static boolean isMobileNetworkConnected() {
		ConnectivityManager connectivityManager = (ConnectivityManager) IuniVoiceApp
				.getInstance().getSystemService("connectivity");

		NetworkInfo info = connectivityManager.getActiveNetworkInfo();

		if (info == null) {
			return false;
		}

		int netType = info.getType();

		// Check if Mobile Network is connected
		if (netType == ConnectivityManager.TYPE_MOBILE) {
			return info.isConnected();
		} else {
			return false;
		}
	}

	public static boolean isHighSpeedConnection() {
		ConnectivityManager connectivityManager = (ConnectivityManager) IuniVoiceApp
				.getInstance().getSystemService("connectivity");

		NetworkInfo info = connectivityManager.getActiveNetworkInfo();

		if (info == null) {
			return false;
		}

		int netType = info.getType();
		int netSubtype = info.getSubtype();

		// Check if WiFi or 3G is connected
		if (netType == ConnectivityManager.TYPE_WIFI) {
			return info.isConnected();
		} else if (netType == ConnectivityManager.TYPE_MOBILE
				&& netSubtype >= TelephonyManager.NETWORK_TYPE_UMTS) {
			return info.isConnected();
		} else {
			return false;
		}
	}

	/**
	 * 获取是否有网络
	 * 
	 * @return
	 */
	public static boolean hasNetwork() {
		Context context = IuniVoiceApp.getInstance();
		if (context != null) {
			ConnectivityManager cm = (ConnectivityManager) context
					.getSystemService(Context.CONNECTIVITY_SERVICE);

			State wifiState = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
					.getState();
			State mobileState = cm.getNetworkInfo(
					ConnectivityManager.TYPE_MOBILE).getState();
			if (wifiState == State.CONNECTED || mobileState == State.CONNECTED) {
				return true;
			} else {
				return false;
			}
		} else {
			return true;
		}
	}

	public static int getConnectingType() {
		final Context context = IuniVoiceApp.getInstance();

		ConnectivityManager mConnectivity = (ConnectivityManager) context
				.getSystemService(Context.CONNECTIVITY_SERVICE);
		TelephonyManager mTelephony = (TelephonyManager) context
				.getSystemService(context.TELEPHONY_SERVICE);

		NetworkInfo info = mConnectivity.getActiveNetworkInfo();

		if (info == null || !mConnectivity.getBackgroundDataSetting()) {
			return -1;
		}

		int netType = info.getType();
		int netSubtype = info.getSubtype();

		if (netType == ConnectivityManager.TYPE_WIFI) {
			return Globals.NETWORK_WIFI;
		} else {
			if ((netSubtype == TelephonyManager.NETWORK_TYPE_GPRS)
					|| (netSubtype == TelephonyManager.NETWORK_TYPE_EDGE)
					|| (netSubtype == TelephonyManager.NETWORK_TYPE_CDMA)) {
				return Globals.NETWORK_2G;
			} else {
				return Globals.NETWORK_3G;
			}
		}

	}
	
	public static String getIMEI() {
		TelephonyManager tm = (TelephonyManager) IuniVoiceApp.getInstance()
				.getSystemService(Context.TELEPHONY_SERVICE);
		String imei = tm.getDeviceId();
		if (imei == null) {
			imei = "";
		}
		return imei;
	}
	
	/**
	 * get current sdk version
	 * @return
	 */
	public static int getCurrentVersion(){
		
		return android.os.Build.VERSION.SDK_INT;
	}

	public static String getVersionName(Context context) {
		// 获取packagemanager的实例
		PackageManager packageManager = context.getPackageManager();
		// getPackageName()是你当前类的包名，0代表是获取版本信息
		PackageInfo packInfo = null;
		try {
			packInfo = packageManager.getPackageInfo(context.getPackageName(), 0);
		} catch (PackageManager.NameNotFoundException e) {
			return null;
		}
		String version = packInfo.versionName;
		return version;
	}

	public static String getMode(){
		return android.os.Build.MODEL;
	}
	
    public static boolean isWifiNetwork() {
        if (!SystemUtils.isNetworkConnected()) {
            // 当前网络获取判断，如无网络连接，直接后台日志
            Log.d(TAG, "isWifiNetwork None network");
            return false;
        }
        // 连接后判断当前WIFI
        if (SystemUtils.getConnectingType() == Globals.NETWORK_WIFI) {
            return true;
        } else {
            return false;
        }
    }
    /**
	* @Title: getTimeString
	* @Description: 获取时间戳，格式为20140702102040
	* @param @param time
	* @param @return
	* @return String
	* @throws
	 */
	public static String getTimeString(long time) {
		Date date = new Date(time);
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddhhmmss");
		return dateFormat.format(date);
	}
    /**
     * 取得mainfest中的versionCode
     */ 
    public static int getVersionCode(Context context, String packageName) {
        int versionCode = -1;
        try {
            versionCode = context.getPackageManager().getPackageInfo(
                    packageName, 0).versionCode;
        } catch (NameNotFoundException e) {
            FileLog.e(TAG, e.toString());
        }
        return versionCode;
    }

}