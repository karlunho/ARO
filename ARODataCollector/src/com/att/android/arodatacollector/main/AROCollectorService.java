/*
 * Copyright 2012 AT&T
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

package com.att.android.arodatacollector.main;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
//import android.util.Log;

import com.att.android.arodatacollector.activities.AROCollectorCompletedActivity;
import com.att.android.arodatacollector.activities.AROCollectorMainActivity;
import com.att.android.arodatacollector.utils.AROCollectorUtils;
import com.instaops.android.AndroidMobileAgent;

/**
 * Contains methods for managing the tcpdump and video capture processes while
 * recording a trace during the application life-cycle.
 * 
 */

public class AROCollectorService extends Service {

	/** A string for logging an ARO Data Collector service. */
	public static final String TAG = "AROCollectorService";

	/** The tcpdump executable name */
	private static final String TCPDUMPFILENAME = "tcpdump";

	/**
	 * The value to check SD Card minimum space during the trace cycle. Trace
	 * will stop if any point the SD card is less than 2 MB
	 */
	private static final int AROSDCARD_MIN_SPACEKBYTES = 2048; // 2 MB
	/**
	 * The Application name file in the trace folder.
	 */
	private static final String APP_NAME_FILE = "appname";

	/**
	 * The boolean value to enable logs depending on if production build or
	 * debug build
	 */
	private static boolean mIsProduction = true;

	/**
	 * A boolean value that indicates whether or not to enable logging for this
	 * class in a debug build of the ARO Data Collector.
	 */
	public static boolean DEBUG = !mIsProduction;

	/** The AROCollectorService object to collect peripherals trace data */
	private static AROCollectorService mDataCollectorService;

	/**
	 * The Application context of the ARo-Data Collector to gets and sets the
	 * application data
	 **/
	private static ARODataCollector mApp;

	/** The Screen Timeout value in milliseconds **/
	private int mScreenTimeout;

	/** ARO Data Collector utilities class object */
	private AROCollectorUtils mAroUtils;

	/** ARO Data Collector full trace folder path */
	private String TRACE_FOLDERNAME;

	/** To holds value ARO Data Collector video recording trace ON/OFF */
	private boolean mVideoRecording;

	/** Intent to launch ARO Data Collector Completed screen */
	private Intent tcpdumpStoppedIntent;

	/**
	 * Intent to launch ARO main screen in case of tcpdump stop due to network
	 * change bearer
	 */
	private Intent tcpdumpStoppedBearerChangeIntent;

	/** Intent to launch ARO Data Collector Completed screen */
	private Intent traceCompletedIntent;

	/**
	 * Gets the valid instance of AROCollectorService.
	 * 
	 * @return An AROCollectorService object
	 */
	public static AROCollectorService getServiceObj() {
		return mDataCollectorService;
	}

	/**
	 * Handles processing when an AROCollectorService object is created.
	 * Overrides the android.app.Service#onCreate method.
	 * 
	 * @see android.app.Service#onCreate()
	 */
	@Override
	public void onCreate() {
		// Initializes the data controls and starts the Data Collector trace
		// (i.e tcpdump,VideoCapture)
		mDataCollectorService = this;
		mApp = (ARODataCollector) getApplication();
		mAroUtils = new AROCollectorUtils();
		try {
			// Record the screen timeout
			getScreenTimeOut();
			// Disable screen timeout
			setScreenTimeOut(-1);
		} catch (SettingNotFoundException e) {
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "exception in getting device settings. Failed to get screen timeout", e);
		}
		TRACE_FOLDERNAME = mApp.getDumpTraceFolderName();
		mVideoRecording = mApp.getCollectVideoOption();
		startDataCollectorVideoCapture();
		statDataCollectortcpdumpCapture();
	}

	/**
	 * Handles processing when an AROCollectorService object is destroyed.
	 * Overrides the android.app.Service#onDestroy method.
	 * 
	 * @see android.app.Service#onDestroy()
	 */
	@Override
	public void onDestroy() {
		super.onDestroy();
		// Sets the screen timeout to previous value
		setScreenTimeOut(mScreenTimeout);
		mDataCollectorService = null;
		mApp.cancleAROAlertNotification();
	}

	/**
	 * Starts the dedicated thread for tcpdump network traffic capture in the
	 * native shell
	 */
	private void statDataCollectortcpdumpCapture() {
		// Starting the tcpdump on separate thread
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					startTcpDump();
					writAppVersions();
				} catch (IOException e) {
					AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "IOException in startTcpDump ", e);
				} catch (InterruptedException e) {
					AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "InterruptedException in startTcpDump ", e);
				}
			}
		}).start();
	}

	/**
	 * Initializes the video capture flag and starts the video capture on
	 * separate thread
	 */
	private void startDataCollectorVideoCapture() {
		// Wait for the tcpdump to start
		if (mVideoRecording) {
			new Thread(new Runnable() {
				@Override
				public void run() {
					mApp.setAROVideoCaptureRunningFlag(true);
					try {
						mApp.initVideoTraceTime();
						startScreenVideoCapture();
					} catch (FileNotFoundException e) {
						AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "exception in initVideoTraceTime. Failed to start Video", e);
					}
				}
			}).start();
		}
	}

	/**
	 * This method creates a SU enabled shell Sets the execute permission for
	 * tcpdump and key.db Starts the tcpdump on Completion or abnormal
	 * termination of tcpdump Shell is destroyed
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 */

	private void startTcpDump() throws IOException, InterruptedException {
		Process sh = null;
		DataOutputStream os = null;
		try {
			sh = Runtime.getRuntime().exec("su");
			os = new DataOutputStream(sh.getOutputStream());
			String Command = "chmod 777 " + ARODataCollector.INTERNAL_DATA_PATH + TCPDUMPFILENAME
					+ "\n";
			os.writeBytes(Command);
			Command = "chmod 777 " + ARODataCollector.INTERNAL_DATA_PATH + "key.db" + "\n";
			os.writeBytes(Command);
			Command = "." + ARODataCollector.INTERNAL_DATA_PATH + TCPDUMPFILENAME + " -w "
					+ TRACE_FOLDERNAME + "\n";
			os.writeBytes(Command);
			Command = "exit\n";
			os.writeBytes(Command);
			os.flush();
			int shExitValue = sh.waitFor();
			if (DEBUG) {
				AndroidMobileAgent.getAgentInstance().getAndroidLogger().d(TAG, "tcpdump process exit value: " + shExitValue);
				AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "Coming out of startTcpDump");
			}
			// Stopping the Video capture right after tcpdump coming out of
			// shell
			new Thread(new Runnable() {
				@Override
				public void run() {
					if (mVideoRecording && mApp.getAROVideoCaptureRunningFlag()) {
						stopScreenVideoCapture();
					}
				}
			}).start();
			handleDataCollectorTraceStop();
		} finally {
			try {
				mApp.setTcpDumpStartFlag(false);
				os.close();
			} catch (IOException e) {
				AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "exception in startTcpDump DataOutputStream close", e);
			}
			sh.destroy();
		}
	}

	/**
	 * Stops the ARO Data Collector trace by stopping the tcpdump process.
	 * 
	 * @throws java.io.IOException
	 * @throws java.net.UnknownHostException
	 */
	public void requestDataCollectorStop() {
		try {
			if (DEBUG) {
				AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "stopTcpDump In....");
			}
			final Socket tcpdumpsocket = new Socket(InetAddress.getByName("localhost"), 50999);
			final OutputStream out = tcpdumpsocket.getOutputStream();
			out.write("STOP".getBytes("ASCII"));
			out.flush();
			out.close();
			tcpdumpsocket.close();
			if (DEBUG) {
				AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "stopTcpDump Out....");
			}
		} catch (UnknownHostException e) {
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "exception in stopTcpDump", e);
		} catch (IOException e) {
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "exception in stopTcpDump", e);
		}
	}

	/**
	 * Handles processing when an AROCollectorService object is binded to
	 * content. Overrides the android.app.Service#onBind method.
	 * 
	 * @see android.app.Service#onBind(android.content.Intent)
	 */
	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

	/**
	 * Handle the tcpdump stops reasons while coming out of tcpdump shell and
	 * navigate to respective screen or shows error dialog
	 */
	private void handleDataCollectorTraceStop() {
		if (DEBUG) {
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "handleDataCollectorTraceStop");
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "mApp.getDataCollectorBearerChange()=" + mApp.getDataCollectorBearerChange());
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "mApp.getDataCollectorInProgressFlag()=" + mApp.getDataCollectorInProgressFlag());
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "mApp.getARODataCollectorStopFlag()=" + mApp.getARODataCollectorStopFlag());
		}

		if (mApp.getDataCollectorBearerChange()) {
			mApp.setDataCollectorBearerChange(false);
			mApp.setTcpDumpStartFlag(false);
			mApp.cancleAROAlertNotification();
			tcpdumpStoppedBearerChangeIntent = new Intent(getBaseContext(),
					AROCollectorMainActivity.class);

			if (!mApp.isWifiLost()) {
				if (DEBUG) {
					AndroidMobileAgent.getAgentInstance().getAndroidLogger().d(TAG, "network bearer change");
				}
				tcpdumpStoppedBearerChangeIntent.putExtra(ARODataCollector.ERRODIALOGID,
						ARODataCollector.BEARERCHANGEERROR);
			} else {
				if (DEBUG) {
					AndroidMobileAgent.getAgentInstance().getAndroidLogger().d(TAG, "writing for wifi lost");
				}

				tcpdumpStoppedBearerChangeIntent.putExtra(ARODataCollector.ERRODIALOGID,
						ARODataCollector.WIFI_LOST_ERROR);
			}

			tcpdumpStoppedBearerChangeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			getApplication().startActivity(tcpdumpStoppedBearerChangeIntent);
			// Stopping the peripherals collection trace service
			stopService(new Intent(getApplicationContext(), AROCollectorTraceService.class));
			// Stopping the tcpdump/screen capture collection trace service
			stopService(new Intent(getApplicationContext(), AROCollectorService.class));
		} else if (!mApp.getDataCollectorInProgressFlag()) {
			if (DEBUG) {
				AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "Cancle Notification Call");
			}
			mApp.cancleAROAlertNotification();
			if (!mApp.getARODataCollectorStopFlag()) {
				// Stopping the peripherals collection trace service
				stopService(new Intent(getApplicationContext(), AROCollectorTraceService.class));
				// Stopping the tcpdump/screen capture collection trace service
				stopService(new Intent(getApplicationContext(), AROCollectorService.class));

				try {
					// Motorola Atrix2 -waiting to get SD card refresh state
					if (Build.MODEL.toString().equalsIgnoreCase("MB865")) {
						// thread sleep for 12 sec
						Thread.sleep(14000);
					}
				} catch (InterruptedException e) {
					AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "InterruptedException while sleep SD card mount" + e);
				}
				mApp.setTcpDumpStartFlag(false);
				tcpdumpStoppedIntent = new Intent(getBaseContext(), AROCollectorMainActivity.class);
				if (DEBUG) {
					AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "SD card space left =" + mAroUtils.checkSDCardMemoryAvailable());
				}
				if (mAroUtils.checkSDCardMemoryAvailable() == 0.0) {
					tcpdumpStoppedIntent.putExtra(ARODataCollector.ERRODIALOGID,
							ARODataCollector.SDCARDMOUNTED_MIDTRACE);
				} else if (mAroUtils.checkSDCardMemoryAvailable() < AROSDCARD_MIN_SPACEKBYTES) {
					tcpdumpStoppedIntent.putExtra(ARODataCollector.ERRODIALOGID,
							ARODataCollector.SDCARDERROR);
				} else {
					tcpdumpStoppedIntent.putExtra(ARODataCollector.ERRODIALOGID,
							ARODataCollector.TCPDUMPSTOPPED);
				}
				tcpdumpStoppedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				getApplication().startActivity(tcpdumpStoppedIntent);
			} else if (mApp.getARODataCollectorStopFlag()) {
				if (DEBUG) {
					AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "Trace Summary Screen to Start");
				}
				traceCompletedIntent = new Intent(getBaseContext(),
						AROCollectorCompletedActivity.class);
				traceCompletedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				getApplication().startActivity(traceCompletedIntent);
				mDataCollectorService = null;
				// Stopping the peripherals collection trace service
				stopService(new Intent(getApplicationContext(), AROCollectorTraceService.class));
				// Stopping the tcpdump/screen capture collection trace service
				stopService(new Intent(getApplicationContext(), AROCollectorService.class));
			}
		}
	}

	/**
	 * Sets the Screen Timeout value
	 * 
	 * @param timeout
	 *            value to be set -1 infinite
	 */
	private void setScreenTimeOut(int val) {
		Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, val);
	}

	/**
	 * Gets the screen timeout value from system.settings
	 * 
	 * @throws SettingNotFoundException
	 */
	private void getScreenTimeOut() throws SettingNotFoundException {
		mScreenTimeout = Settings.System.getInt(getContentResolver(),
				Settings.System.SCREEN_OFF_TIMEOUT);

	}

	/**
	 * Starts the video capture of the device desktop by reading frame buffer
	 * using ffmpeg command
	 */
	private void startScreenVideoCapture() {
		Process sh = null;
		DataOutputStream os = null;
		try {
			if (DEBUG) {
				AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "Starting Video Capture");
			}
			sh = Runtime.getRuntime().exec("su");
			os = new DataOutputStream(sh.getOutputStream());
			String Command = "cd " + ARODataCollector.INTERNAL_DATA_PATH + " \n";
			os.writeBytes(Command);
			Command = "chmod 777 ffmpeg \n";
			os.writeBytes(Command);
			Command = "./ffmpeg -f fbdev -vsync 2 -r 3 -i /dev/graphics/fb0 /sdcard/ARO/"
					+ TRACE_FOLDERNAME + "/video.mp4 2> /data/ffmpegout.txt \n";
			os.writeBytes(Command);
			Command = "exit\n";
			os.writeBytes(Command);
			os.flush();
			sh.waitFor();
		} catch (IOException e) {
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "exception in startScreenVideoCapture", e);
		} catch (InterruptedException e) {
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "exception in startScreenVideoCapture", e);
		} finally {
			try {
				if (DEBUG) {
					AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "Stopped Video Capture");
				}
				os.close();
				// Reading start time of Video from ffmpegout file
				mApp.readffmpegStartTimefromFile();
			} catch (IOException e) {
				AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "IOException in reading video start time", e);
			} catch (NumberFormatException e) {
				AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "NumberFormatException in reading video start time", e);
			}
			try {
				// Recording start time of video
				mApp.writeVideoTraceTime(Double.toString(mApp.getAROVideoCaptureStartTime()));
				mApp.closeVideoTraceTimeFile();
			} catch (IOException e) {
				AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "IOException in writing video start time", e);
			}
			if (mApp.getTcpDumpStartFlag() && !mApp.getARODataCollectorStopFlag()) {
				mApp.setVideoCaptureFailed(true);
			}
			mApp.setAROVideoCaptureRunningFlag(false);
			sh.destroy();
		}
	}

	/**
	 * Stops the Screen video capture
	 */
	private void stopScreenVideoCapture() {
		Process sh = null;
		DataOutputStream os = null;
		int pid = 0;
		try {
			pid = mAroUtils.getProcessID("ffmpeg");
		} catch (IOException e1) {
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "IOException in stopScreenVideoCapture", e1);
		} catch (InterruptedException e1) {
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "exception in stopScreenVideoCapture", e1);
		}
		if (DEBUG) {
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "stopScreenVideoCapture=" + pid);
		}
		if (pid != 0) {
			try {
				sh = Runtime.getRuntime().exec("su");
				os = new DataOutputStream(sh.getOutputStream());
				final String Command = "kill -15 " + pid + "\n";
				os.writeBytes(Command);
				os.flush();
				sh.waitFor();
			} catch (IOException e) {
				AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "exception in stopScreenVideoCapture", e);
			} catch (InterruptedException e) {
				AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "exception in stopScreenVideoCapture", e);
			} finally {
				try {
					mVideoRecording = false;
					os.close();
				} catch (IOException e) {
					AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "exception in stopScreenVideoCapture DataOutputStream close", e);
				}
				if (DEBUG) {
					AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "Stopped Video Capture");
				}
				sh.destroy();
			}
		}
	}

	/**
	 * Reads the appname file generated from tcpdump and appends the application
	 * version next to each application .
	 * 
	 * @throws IOException
	 */
	private void writAppVersions() throws IOException {
		BufferedReader appNamesFileReader = null;
		BufferedWriter appNmesFileWriter = null;
		try {
			String strTraceFolderName = mApp.getTcpDumpTraceFolderName();
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().i(TAG, "Trace folder name is: " + strTraceFolderName);
			File appNameFile = new File(mApp.getTcpDumpTraceFolderName() + APP_NAME_FILE);
			appNamesFileReader = new BufferedReader(new InputStreamReader(new FileInputStream(
					appNameFile)));
			String processName = null;
			List<String> appNamesWithVersions = new ArrayList<String>();
			while ((processName = appNamesFileReader.readLine()) != null) {

				String versionNum = null;
				try {
					versionNum = getPackageManager().getPackageInfo(processName, 0).versionName;
					appNamesWithVersions.add(processName + " " + versionNum);
				} catch (NameNotFoundException e) {
					appNamesWithVersions.add(processName);
					AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "Package name can not be found; unable to get version number.");
				} catch (Exception e) {
					AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "Unable to get version number ");
				}
			}
			appNmesFileWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(
					appNameFile)));
			final String eol = System.getProperty("line.separator");
			for (String appNemeVersion : appNamesWithVersions) {
				appNmesFileWriter.append(appNemeVersion + eol);

			}
		} catch (IOException e) {
			AndroidMobileAgent.getAgentInstance().getAndroidLogger().e(TAG, "Error occured while writing the version number for the applications");
		} finally {
			if (appNamesFileReader != null) {
				appNamesFileReader.close();
			}
			if (appNmesFileWriter != null) {
				appNmesFileWriter.close();
			}
		}
	}
}
