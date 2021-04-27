package fr.drangies.cordova.serialandroid;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
// import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.ftdi.j2xx.D2xxManager;
import com.ftdi.j2xx.FT_Device;
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.ProlificSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import android.util.Log;

import android.os.Handler;
import android.os.Message;

/**
 * Cordova plugin to communicate with the android serial port
 * 
 * @author Xavier Seignard <xavier.seignard@gmail.com>
 */
public class SerialAndroid extends CordovaPlugin {
	public Context context;
	boolean bReadTheadEnable = false;
	boolean cardReadThreadEnable = false;

	// logging tag
	private final String TAG = SerialAndroid.class.getSimpleName();
	// actions definitions
	private static final String ACTION_REQUEST_PERMISSION = "requestPermission";
	private static final String ACTION_OPEN = "openSerial";
	private static final String ACTION_READ = "readSerial";
	private static final String ACTION_WRITE = "writeSerial";
	private static final String ACTION_WRITE_HEX = "writeSerialHex";
	private static final String ACTION_CLOSE = "closeSerial";
	private static final String ACTION_READ_CALLBACK = "registerReadCallback";

	// UsbManager instance to deal with permission and opening
	private UsbManager manager;
	// The current driver that handle the serial port
	private UsbSerialDriver driver;
	// The serial port that will be used in this plugin
	// private List<UsbSerialPort> mPorts;
	private UsbSerialPort port;
	private UsbSerialPort port2;
	// Read buffer, and read params
	private static final int READ_WAIT_MILLIS = 200;
	private static final int BUFSIZ = 4096;
	private final ByteBuffer mReadBuffer = ByteBuffer.allocate(BUFSIZ);
	// Connection info
	private int baudRate;
	private int dataBits;
	private int stopBits;
	private int parity;
	private boolean setDTR;
	private boolean setRTS;
	private int productId;
	private boolean fm1;
	private boolean sleepOnPause;

	// callback that will be used to send back data to the cordova app
	private CallbackContext readCallback;
	private CallbackContext readCallbackFM1;

	// I/O manager to handle new incoming serial data
	private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
	private final ExecutorService pExecutor = Executors.newSingleThreadExecutor();
	private SerialInputOutputManager mSerialIoManager;
	private SerialInputOutputManager pSerialIoManager;
	private static D2xxManager ftD2xx = null;
	private static UsbManager mUsbManager;
	FT_Device ftDev;
	FT_Device ftDevCard;
	int iTotalBytes;
	final int MAX_NUM_BYTES = 65536;
	ReadThread readThread;
	ReadThreadCard readThreadCard;
	// UsbSerialProber prober1;

	private final SerialInputOutputManager.Listener mListener = new SerialInputOutputManager.Listener() {
		@Override
		public void onRunError(Exception e) {
			Log.d(TAG, "Runner stopped.");
		}

		@Override
		public void onNewData(final byte[] data) {
			SerialAndroid.this.updateReceivedData(data);
			// Log.d(TAG, "onNewData mListener");
		}
	};

	private final SerialInputOutputManager.Listener pListener = new SerialInputOutputManager.Listener() {
		@Override
		public void onRunError(Exception e) {
			Log.d(TAG, "Runner stopped.");
		}
		
		@Override
		public void onNewData(final byte[] data) {
			SerialAndroid.this.updateReceivedDataFM1(data);
			// Log.d(TAG, "onNewData pListener");
		}
	};

	@Override
	public void initialize(CordovaInterface cordova, CordovaWebView webView) {
		super.initialize(cordova, webView);
		// your init code here
		Log.d(TAG, "initialize success!");
		context = this.cordova.getActivity().getApplicationContext();
		try { 
			ftD2xx = D2xxManager.getInstance(context);
			// Log.d(TAG, "getInstance success! " + ftD2xx);
		} catch (D2xxManager.D2xxException e) {
			Log.e(TAG, "getInstance fail!!");
		}

	}

	/**
	 * Overridden execute method
	 * 
	 * @param action          the string representation of the action to execute
	 * @param args
	 * @param callbackContext the cordova {@link CallbackContext}
	 * @return true if the action exists, false otherwise
	 * @throws JSONException if the args parsing fails
	 */
	@Override
	public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
		Log.d(TAG, "Action: " + action);
		JSONObject arg_object = args.optJSONObject(0);
		// request permission
		if (ACTION_REQUEST_PERMISSION.equals(action)) {
			JSONObject opts = arg_object.has("opts") ? arg_object.getJSONObject("opts") : new JSONObject();
			requestPermission(opts, callbackContext);
			return true;
		}
		// open serial port
		else if (ACTION_OPEN.equals(action)) {
			JSONObject opts = arg_object.has("opts") ? arg_object.getJSONObject("opts") : new JSONObject();
			openSerial(opts, callbackContext);
			return true;
		}
		// write to the serial port
		else if (ACTION_WRITE.equals(action)) {
			String data = arg_object.getString("data");
			writeSerial(data, callbackContext);
			return true;
		}
		// write hex to the serial port
		else if (ACTION_WRITE_HEX.equals(action)) {
			String data = arg_object.getString("data");
			writeSerialHex(data, callbackContext);
			return true;
		}
		// read on the serial port
		else if (ACTION_READ.equals(action)) {
			readSerial(callbackContext);
			return true;
		}
		// close the serial port
		else if (ACTION_CLOSE.equals(action)) {
			closeSerial(callbackContext);
			return true;
		}
		// Register read callback
		else if (ACTION_READ_CALLBACK.equals(action)) {
			JSONObject opts = arg_object.has("opts") ? arg_object.getJSONObject("opts") : new JSONObject();
			registerReadCallback(opts, callbackContext);
			return true;
		}
		// the action doesn't exist
		return false;
	}

	/**
	 * Request permission the the user for the app to use the USB/serial port
	 * 
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void requestPermission(final JSONObject opts, final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				// get UsbManager from Android
				manager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
				UsbSerialProber prober;

				if (opts.has("vid") && opts.has("pid")) {
					ProbeTable customTable = new ProbeTable();
					Object o_vid = opts.opt("vid"); // can be an integer Number or a hex String
					Object o_pid = opts.opt("pid"); // can be an integer Number or a hex String
					int vid = o_vid instanceof Number ? ((Number) o_vid).intValue() : Integer.parseInt((String) o_vid, 16);
					int pid = o_pid instanceof Number ? ((Number) o_pid).intValue() : Integer.parseInt((String) o_pid, 16);
					String driver = opts.has("driver") ? (String) opts.opt("driver") : "CdcAcmSerialDriver";

					if (driver.equals("FtdiSerialDriver")) {
						customTable.addProduct(vid, pid, FtdiSerialDriver.class);
					} else if (driver.equals("CdcAcmSerialDriver")) {
						customTable.addProduct(vid, pid, CdcAcmSerialDriver.class);
					} else if (driver.equals("Cp21xxSerialDriver")) {
						customTable.addProduct(vid, pid, Cp21xxSerialDriver.class);
					} else if (driver.equals("ProlificSerialDriver")) {
						customTable.addProduct(vid, pid, ProlificSerialDriver.class);
					} else if (driver.equals("Ch34xSerialDriver")) {
						customTable.addProduct(vid, pid, Ch34xSerialDriver.class);
					} else {
						Log.d(TAG, "Unknown driver!");
						callbackContext.error("Unknown driver!");
					}

					prober = new UsbSerialProber(customTable);
					// if (pid == 24592) {
					// 	prober1 = new UsbSerialProber(customTable);
					// }

				} else {
					// find all available drivers from attached devices.
					prober = UsbSerialProber.getDefaultProber();
				}

				List<UsbSerialDriver> availableDrivers = prober.findAllDrivers(manager);

				if (!availableDrivers.isEmpty()) {
					// get the first one as there is a high chance that there is no more than one
					// usb device attached to your android
					driver = availableDrivers.get(0);
					Log.d("available ports", "available ports: " + driver.getPorts());
					UsbDevice device = driver.getDevice();
					// create the intent that will be used to get the permission
					PendingIntent pendingIntent = PendingIntent.getBroadcast(cordova.getActivity(), 0,
							new Intent(UsbBroadcastReceiverAndroid.USB_PERMISSION), 0);
					// and a filter on the permission we ask
					IntentFilter filter = new IntentFilter();
					filter.addAction(UsbBroadcastReceiverAndroid.USB_PERMISSION);
					// this broadcast receiver will handle the permission results
					UsbBroadcastReceiverAndroid usbReceiver = new UsbBroadcastReceiverAndroid(callbackContext,
							cordova.getActivity());
					cordova.getActivity().registerReceiver(usbReceiver, filter);
					// finally ask for the permission
					manager.requestPermission(device, pendingIntent);
				} else {
					// no available drivers
					Log.d(TAG, "No device found!");
					callbackContext.error("No device found!");
				}
			}
		});
	}

	final Handler handler = new Handler() {
		public void handleMessage(Message msg) {
			
		}
	};

	public void connectFunction() {
		if (ftDev != null && true == ftDev.isOpen()) {
			// Toast.makeText(global_context,"Port("+portIndex+") is already opened.",
			// Toast.LENGTH_SHORT).show();
			return;
		}

		if (true == bReadTheadEnable) {
			bReadTheadEnable = false;
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		if (null == ftDev) {
			ftDev = ftD2xx.openByIndex(context, 1);
		} else {
			ftDev = ftD2xx.openByIndex(context, 1);
		}

		if (ftDev == null) {
			Log.d(TAG, "ftDev null");
			// midToast("Open port(" + portIndex + ") NG!", Toast.LENGTH_LONG);
			return;
		}

		if (true == ftDev.isOpen()) {
			Log.d(TAG, "Openning device port 1 successful!");
			
			if (false == bReadTheadEnable) {
				readThread = new ReadThread(handler);
				readThread.start();
			}
		}
	}

	public void connectFunctionCard() {
		if (ftDevCard != null && ftDevCard.isOpen()) {
			return;
		}

		if (true == cardReadThreadEnable) {
			cardReadThreadEnable = false;
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		if (null == ftDevCard) {
			Log.d(TAG, "ftDevCard not null!");
			ftDevCard = ftD2xx.openByIndex(context, 2);
		}

		if (true == ftDevCard.isOpen()) {
			Log.d(TAG, "Openning device port 2 successful!");

			if (true == cardReadThreadEnable) {
				readThreadCard = new ReadThreadCard(handler);
				readThreadCard.start();
			}
		}

	}

	/**
	 * Open the serial port from Cordova
	 * 
	 * @param opts            a {@link JSONObject} containing the connection
	 *                        paramters
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void openSerial(final JSONObject opts, final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				String deviceName = "";
				try {
					productId = opts.has("pid") ? opts.getInt("pid") : 9999;
					deviceName = opts.has("deviceName") ? opts.getString("deviceName") : "com1";
					Log.d("deviceName", deviceName);
				} catch (JSONException e) {
					// deal with error
					Log.d(TAG, e.getMessage());
					callbackContext.error(e.getMessage());
				}

				int devCount = ftD2xx.createDeviceInfoList(context);
				mUsbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);	
				HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
				Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();
				while(deviceIterator.hasNext()) {
					UsbDevice device = deviceIterator.next();
					Log.d("Device interfaces", "USB " + device.getInterfaceCount() + " Product ID " + device.getProductId());
				}
				// Log.d("USB Device List ", deviceList.toString());
				Log.d(TAG, "tempDevCount: " + devCount);
				
				UsbSerialDriver driverTest = null;
				// UsbSerialDriver comDriver = null;
				List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);

				for (UsbSerialDriver usd : availableDrivers) {
					if (usd == null) {
						break;
					}
					UsbDevice udv = usd.getDevice();
					// Log.d(TAG, "Device " + udv);
					if (udv.getProductId() == productId) {
						driverTest = usd;
						// Log.d(TAG, "Driver Test " + driverTest);
						break;
					}
				}

				// List<UsbSerialDriver> availableDriversCustom = null;
				// if (productId == 24592) {
				// 	availableDriversCustom = prober1.findAllDrivers(manager);
				// 	if (availableDriversCustom != null) {
				// 		for (UsbSerialDriver usbdriver : availableDriversCustom) {
				// 			UsbDevice usbdev = usbdriver.getDevice();
				
				// 			if (usbdev.getProductId() == 24592) {
				// 				comDriver = usbdriver;
				// 				// driverTest = usbdriver;
				// 				Log.d(TAG, "Com driver " + comDriver);
				// 				break;
				// 			}
				// 		}
				// 	}
				// }

				UsbDeviceConnection connection = manager.openDevice(driverTest.getDevice()); // ftDev.setConnection(mUsbConnection)
				// UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
				Log.i(TAG, "driverTest.getPorts() ::::: " + driverTest.getPorts() + "Length" + driverTest.getPorts().size());
				
				if (connection != null) {

					try {
						// get connection params or the default values
						baudRate = opts.has("baudRate") ? opts.getInt("baudRate") : 9600;
						dataBits = opts.has("dataBits") ? opts.getInt("dataBits") : UsbSerialPort.DATABITS_8;
						stopBits = opts.has("stopBits") ? opts.getInt("stopBits") : UsbSerialPort.STOPBITS_1;
						parity = opts.has("parity") ? opts.getInt("parity") : UsbSerialPort.PARITY_NONE;
						setDTR = opts.has("dtr") && opts.getBoolean("dtr");
						setRTS = opts.has("rts") && opts.getBoolean("rts");

						// Sleep On Pause defaults to true
						sleepOnPause = opts.has("sleepOnPause") ? opts.getBoolean("sleepOnPause") : false;

						if (deviceName.equals("com2") ) {
							Log.d("deviceName com2", deviceName);
							port2 = driverTest.getPorts().get(0);
							port2.open(connection);
							port2.setParameters(baudRate, dataBits, stopBits, parity);
							if (setDTR)
							port2.setDTR(true);
							if (setRTS)
							port2.setRTS(true);
						} else {
							Log.d("deviceName not com2", deviceName);
							port = driverTest.getPorts().get(1);
							port.open(connection);
							port.setParameters(baudRate, dataBits, stopBits, parity);
							if (setDTR)
								port.setDTR(true);
							if (setRTS)
								port.setRTS(true);
						}
						
					} catch (IOException e) {
						// deal with error
						Log.d(TAG, e.getMessage());
						callbackContext.error(e.getMessage());
					} catch (JSONException e) {
						// deal with error
						Log.d(TAG, e.getMessage());
						callbackContext.error(e.getMessage());
					}
					callbackContext.success("Serial port opened!");
				} else {
					Log.d(TAG, "Cannot connect to the device!");
					callbackContext.error("Cannot connect to the device!");
				}
				// onDeviceStateChange(); // NOTE: Replacing this with start io manager code
				if (deviceName.equals("com1") ) {
					Log.i(TAG, "Starting io manager. FM1 :::: " + port);
					// Log.i(TAG, "port2 " + port2);
					pSerialIoManager = new SerialInputOutputManager(port, pListener);
					pExecutor.submit(pSerialIoManager);

				} else {
					Log.i(TAG, "Starting io manager. Card Reader  :::: " + port2);
					mSerialIoManager = new SerialInputOutputManager(port2, mListener);
					mExecutor.submit(mSerialIoManager);
				}
			}
		});
	}


	// private void openSerial(final JSONObject opts, final CallbackContext callbackContext) {
	// 	cordova.getThreadPool().execute(new Runnable() {
	// 		public void run() {
	// 			try {
	// 				productId = opts.has("pid") ? opts.getInt("pid") : 9999;
	// 			} catch (JSONException e) {
	// 				// deal with error
	// 				Log.d(TAG, e.getMessage());
	// 				callbackContext.error(e.getMessage());
	// 			}

	// 			UsbSerialDriver targetUsbDriver = null;
	// 			List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);

	// 			for (UsbSerialDriver usbDriver : availableDrivers) {
	// 				UsbDevice usbdevice = usbDriver.getDevice();
	// 				Log.d(TAG, "Device " + usbdevice);
	// 				if (usbdevice.getProductId() == productId) {
	// 					targetUsbDriver = usbDriver;
	// 					Log.d(TAG, "Driver Test " + targetUsbDriver);
	// 					break;
	// 				}
	// 			}

	// 			UsbDeviceConnection connection = manager.openDevice(targetUsbDriver.getDevice());
	// 			if (connection != null) {
	// 				// get first port and open it
	// 				port = targetUsbDriver.getPorts().get(0);
	// 				try {
	// 					// get connection params or the default values
	// 					baudRate = opts.has("baudRate") ? opts.getInt("baudRate") : 9600;
	// 					dataBits = opts.has("dataBits") ? opts.getInt("dataBits") : UsbSerialPort.DATABITS_8;
	// 					stopBits = opts.has("stopBits") ? opts.getInt("stopBits") : UsbSerialPort.STOPBITS_1;
	// 					parity = opts.has("parity") ? opts.getInt("parity") : UsbSerialPort.PARITY_NONE;
	// 					setDTR = opts.has("dtr") && opts.getBoolean("dtr");
	// 					setRTS = opts.has("rts") && opts.getBoolean("rts");
	// 					// Sleep On Pause defaults to true
	// 					sleepOnPause = opts.has("sleepOnPause") ? opts.getBoolean("sleepOnPause") : false;

	// 					port.open(connection);
	// 					port.setParameters(baudRate, dataBits, stopBits, parity);
	// 					if (setDTR)
	// 						port.setDTR(true);
	// 					if (setRTS)
	// 						port.setRTS(true);
	// 				} catch (IOException e) {
	// 					// deal with error
	// 					Log.d(TAG, e.getMessage());
	// 					callbackContext.error(e.getMessage());
	// 				} catch (JSONException e) {
	// 					// deal with error
	// 					Log.d(TAG, e.getMessage());
	// 					callbackContext.error(e.getMessage());
	// 				}

	// 				Log.d(TAG, "Serial port opened!");
	// 				callbackContext.success("Serial port opened!");
	// 			} else {
	// 				Log.d(TAG, "Cannot connect to the device!");
	// 				callbackContext.error("Cannot connect to the device!");
	// 			}
	// 			// onDeviceStateChange();
	// 			if (productId == 8963) {
	// 				Log.i(TAG, "Starting io manager. FM1 I am here " + productId);
	// 				Log.i(TAG, "I am here!");
	// 				pSerialIoManager = new SerialInputOutputManager(port, pListener);
	// 				pExecutor.submit(pSerialIoManager);

	// 			} else {
	// 				Log.i(TAG, "Starting io manager. Card Reader " + productId);
	// 				mSerialIoManager = new SerialInputOutputManager(port, mListener);
	// 				mExecutor.submit(mSerialIoManager);
	// 			}
	// 			// getSomethingFromftD2xx();
	// 			Log.wtf(TAG, "serialOpen thread");
	// 		}
	// 	});
	// }

	/**
	 * Write on the serial port
	 * 
	 * @param data            the {@link String} representation of the data to be
	 *                        written on the port
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void writeSerial(final String data, final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				if (port == null) {
					callbackContext.error("Writing a closed port.");
				} else {
					try {
						Log.d(TAG, data);
						byte[] buffer = data.getBytes();
						port.write(buffer, 1000);
						callbackContext.success();
					} catch (IOException e) {
						// deal with error
						Log.d(TAG, e.getMessage());
						callbackContext.error(e.getMessage());
					}
				}
			}
		});
	}

	/**
	 * Write hex on the serial port
	 * 
	 * @param data            the {@link String} representation of the data to be
	 *                        written on the port as hexadecimal string e.g.
	 *                        "ff55aaeeef000233"
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void writeSerialHex(final String data, final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				if (port == null) {
					callbackContext.error("Writing a closed port.");
				} else {
					Log.i(TAG, "writeSerialHex writing to " + port);
					try {
						Log.d(TAG, data);
						byte[] buffer = hexStringToByteArray(data);
						port.write(buffer, 1000);
						// int result = sendDataArray(buffer);
						// if (result == -1) {
						// 	throw new IOException("Something happened");
						// }
						callbackContext.success("Success!");
					} catch (IOException e) {
						// deal with error
						Log.d(TAG, e.getMessage());
						callbackContext.error(e.getMessage());
					}
				}
			}
		});
	}

	int sendDataArray(byte[] buffer) {
		int result = -1;
		if (ftDev != null && ftDev.isOpen() == false) {
			Log.e(TAG, "SendData: device not open");
			return -1;
		}

		if (buffer.length > 0) {
			if (ftDev != null) {
				result = ftDev.write(buffer, buffer.length);
			}
		}
		return result;
	}

	void sendData(byte buffer) {
		Log.e(TAG, "send buffer data");
		byte tmpBuf[] = new byte[1];
		tmpBuf[0] = buffer;
		ftDev.write(tmpBuf, 1);
	}

	/**
	 * Convert a given string of hexadecimal numbers into a byte[] array where every
	 * 2 hex chars get packed into a single byte.
	 *
	 * E.g. "ffaa55" results in a 3 byte long byte array
	 *
	 * @param s
	 * @return
	 */
	private byte[] hexStringToByteArray(String s) {
		int len = s.length();
		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
		}
		return data;
	}

	/**
	 * Read on the serial port
	 * 
	 * @param callbackContext the {@link CallbackContext}
	 */
	private void readSerial(final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				if (port == null) {
					callbackContext.error("Reading a closed port.");
				} else {
					try {
						int len = port.read(mReadBuffer.array(), READ_WAIT_MILLIS);
						// Whatever happens, we send an "OK" result, up to the
						// receiver to check that len > 0
						PluginResult.Status status = PluginResult.Status.OK;
						if (len > 0) {
							// Log.d(TAG, "Read data len=" + len);
							final byte[] data = new byte[len];
							mReadBuffer.get(data, 0, len);
							mReadBuffer.clear();
							callbackContext.sendPluginResult(new PluginResult(status, data));
						} else {
							final byte[] data = new byte[0];
							callbackContext.sendPluginResult(new PluginResult(status, data));
						}
					} catch (IOException e) {
						// deal with error
						Log.d(TAG, e.getMessage());
						callbackContext.error(e.getMessage());
					}
				}
			}
		});
	}

	/**
	 * Close the serial port
	 * 
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void closeSerial(final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				try {
					// Make sure we don't die if we try to close an non-existing port!
					if (port != null) {
						port.close();
					}
					port = null;
					callbackContext.success();
				} catch (IOException e) {
					// deal with error
					Log.d(TAG, e.getMessage());
					callbackContext.error(e.getMessage());
				}
				onDeviceStateChange();
			}
		});
	}

	/**
	 * Stop observing serial connection
	 */
	private void stopIoManager() {
		if (mSerialIoManager != null) {
			Log.i(TAG, "Stopping io manager.");
			mSerialIoManager.stop();
			mSerialIoManager = null;
		}
	}

	/**
	 * Observe serial connection
	 */
	private void startIoManager() {
		if (driver != null) {
			Log.i(TAG, "Starting io manager.");
			mSerialIoManager = new SerialInputOutputManager(port, mListener);
			mExecutor.submit(mSerialIoManager);
		}
	}

	/**
	 * Restart the observation of the serial connection
	 */
	private void onDeviceStateChange() {
		stopIoManager();
		startIoManager();
	}

	/**
	 * Dispatch read data to javascript
	 * 
	 * @param data the array of bytes to dispatch
	 */
	private void updateReceivedData(byte[] data) {
		if (readCallback != null) {
			PluginResult result = new PluginResult(PluginResult.Status.OK, data);
			result.setKeepCallback(true);
			readCallback.sendPluginResult(result);
		}
	}

	/**
	 * Dispatch read data to javascript fm1
	 * 
	 * @param data the array of bytes to dispatch
	 */
	private void updateReceivedDataFM1(byte[] data) {
		if (readCallbackFM1 != null) {
			PluginResult result = new PluginResult(PluginResult.Status.OK, data);
			result.setKeepCallback(true);
			readCallbackFM1.sendPluginResult(result);
		}
	}

	/**
	 * Register callback for read data
	 * 
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void registerReadCallback(final JSONObject opts, final CallbackContext callbackContext) {
		Log.d(TAG, "Registering callback");
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				try {
					fm1 = opts.has("fm1") && opts.getBoolean("fm1");
					// Log.d(TAG, "fm1 boolean: " + fm1);
				} catch (JSONException e) {
					// deal with error
					Log.d(TAG, e.getMessage());
					callbackContext.error(e.getMessage());
				}
				Log.d(TAG, "Registering Read Callback");
				if (fm1) {
					readCallbackFM1 = callbackContext;
				} else {
					readCallback = callbackContext;
				}
				JSONObject returnObj = new JSONObject();
				addProperty(returnObj, "registerReadCallback", "true");
				// Keep the callback
				PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, returnObj);
				pluginResult.setKeepCallback(true);
				callbackContext.sendPluginResult(pluginResult);
			}
		});
	}

	/**
	 * Paused activity handler
	 * 
	 * @see org.apache.cordova.CordovaPlugin#onPause(boolean)
	 */
	@Override
	public void onPause(boolean multitasking) {
		if (sleepOnPause) {
			stopIoManager();
			if (port != null) {
				try {
					port.close();
				} catch (IOException e) {
					// Ignore
				}
				port = null;
			}
		}
	}

	/**
	 * Resumed activity handler
	 * 
	 * @see org.apache.cordova.CordovaPlugin#onResume(boolean)
	 */
	@Override
	public void onResume(boolean multitasking) {
		// Log.d(TAG, "Resumed, driver=" + driver);
		if (sleepOnPause) {
			if (driver == null) {
				Log.d(TAG, "No serial device to resume.");
			} else {
				UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
				if (connection != null) {
					// get first port and open it
					port = driver.getPorts().get(0);
					try {
						port.open(connection);
						port.setParameters(baudRate, dataBits, stopBits, parity);
						if (setDTR)
							port.setDTR(true);
						if (setRTS)
							port.setRTS(true);
					} catch (IOException e) {
						// deal with error
						Log.d(TAG, e.getMessage());
					}
					Log.d(TAG, "Serial port opened!");
				} else {
					Log.d(TAG, "Cannot connect to the device!");
				}
				// Log.d(TAG, "Serial device: " + driver.getClass().getSimpleName());
			}

			onDeviceStateChange();
		}
	}

	/**
	 * Destroy activity handler
	 * 
	 * @see org.apache.cordova.CordovaPlugin#onDestroy()
	 */
	@Override
	public void onDestroy() {
		// Log.d(TAG, "Destroy, port=" + port);
		if (port != null) {
			try {
				port.close();
			} catch (IOException e) {
				Log.d(TAG, e.getMessage());
			}
		}
		onDeviceStateChange();
	}

	/**
	 * Utility method to add some properties to a {@link JSONObject}
	 * 
	 * @param obj   the json object where to add the new property
	 * @param key   property key
	 * @param value value of the property
	 */
	private void addProperty(JSONObject obj, String key, Object value) {
		try {
			obj.put(key, value);
		} catch (JSONException e) {
		}
	}

	/**
	 * Utility method to add some properties to a {@link JSONObject}
	 * 
	 * @param obj   the json object where to add the new property
	 * @param key   property key
	 * @param bytes the array of byte to add as value to the {@link JSONObject}
	 */
	private void addPropertyBytes(JSONObject obj, String key, byte[] bytes) {
		String string = Base64.encodeToString(bytes, Base64.NO_WRAP);
		this.addProperty(obj, key, string);
	}

	class ReadThreadCard extends Thread {
		final int CARD_DATA_BUFFER = 255;

		Handler cHandler;

		ReadThreadCard(Handler h) {
			cHandler = h;
			this.setPriority(MAX_PRIORITY);
		}

		public void run() {
			byte[] cardData = new byte[CARD_DATA_BUFFER];
			int readcount = 0;
			cardReadThreadEnable = true;

			while (true == cardReadThreadEnable) {
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				while (iTotalBytes > (MAX_NUM_BYTES - (CARD_DATA_BUFFER + 1))) {
					try {
						Thread.sleep(50);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}

				readcount = ftDevCard.getQueueStatus();
				if (readcount > 0) {
					if (readcount > CARD_DATA_BUFFER) {
						readcount = CARD_DATA_BUFFER;
					}
					ftDevCard.read(cardData, readcount);
					updateReceivedData(cardData);

				}
			}

			Log.e(TAG, "read thread terminate...");
		}
	}


	class ReadThread extends Thread {
		final int USB_DATA_BUFFER = 1023;

		Handler mHandler;

		ReadThread(Handler h) {
			mHandler = h;
			this.setPriority(MAX_PRIORITY);
		}

		public void run() {
			byte[] usbdata = new byte[USB_DATA_BUFFER];
			int readcount = 0;
			// int iWriteIndex = 0;
			bReadTheadEnable = true;

			while (true == bReadTheadEnable) {
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				while (iTotalBytes > (MAX_NUM_BYTES - (USB_DATA_BUFFER + 1))) {
					try {
						Thread.sleep(50);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}

				readcount = ftDev.getQueueStatus();

				if (readcount > 0) {
					if (readcount > USB_DATA_BUFFER) {
						readcount = USB_DATA_BUFFER;
					}
					ftDev.read(usbdata, readcount);
					updateReceivedDataFM1(usbdata);
				
				}
			}

			Log.e(TAG, "read thread terminate...");
		}
	}
}