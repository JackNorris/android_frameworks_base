/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.UEventObserver;
import android.util.Slog;
import android.media.AudioManager;
import android.util.Log;

import java.io.File;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>WiredAccessoryObserver monitors for a wired headset on the main board or dock.
 */
class WiredAccessoryObserver extends UEventObserver {
    private static final String TAG = WiredAccessoryObserver.class.getSimpleName();
    private static final boolean LOG = true;
    private static final int BIT_HEADSET = (1 << 0);
    private static final int BIT_HEADSET_NO_MIC = (1 << 1);
    private static final int BIT_USB_HEADSET_ANLG = (1 << 2);
    private static final int BIT_USB_HEADSET_DGTL = (1 << 3);
    private static final int BIT_HDMI_AUDIO = (1 << 4);
    private static final int SUPPORTED_HEADSETS = (BIT_HEADSET|BIT_HEADSET_NO_MIC|
                                                   BIT_USB_HEADSET_ANLG|BIT_USB_HEADSET_DGTL|
                                                   BIT_HDMI_AUDIO);
    private static final int HEADSETS_WITH_MIC = BIT_HEADSET;
    private static List<String> sDockNames=Arrays.asList(Resources.getSystem().getStringArray(com.android.internal.R.array.config_accessoryDockNames));

    private static class UEventInfo {
        private final String mDevName;
        private final int mState1Bits;
        private final int mState2Bits;
        private int switchState;

        public UEventInfo(String devName, int state1Bits, int state2Bits) {
            mDevName = devName;
            mState1Bits = state1Bits;
            mState2Bits = state2Bits;
        }

        public String getDevName() { return mDevName; }

        public String getDevPath() {
            return String.format("/devices/virtual/switch/%s", mDevName);
        }

        public String getSwitchStatePath() {
            return String.format("/sys/class/switch/%s/state", mDevName);
        }

        public boolean checkSwitchExists() {
            File f = new File(getSwitchStatePath());
            return ((null != f) && f.exists());
        }

        public int computeNewHeadsetState(String name, int state) {

        if (LOG) Slog.v(TAG, "updateState name: " + name + " state " + state);
        if (name.equals("usb_audio")) {
            switchState = ((mHeadsetState & (BIT_HEADSET|BIT_HEADSET_NO_MIC|BIT_HDMI_AUDIO)) |
                           ((state == 1) ? BIT_USB_HEADSET_ANLG :
                                         ((state == 2) ? BIT_USB_HEADSET_DGTL : 0)));
        } else if (sDockNames.contains(name)) {
             switchState = ((mHeadsetState & (BIT_HEADSET|BIT_HEADSET_NO_MIC|BIT_HDMI_AUDIO)) |
                           ((state == 2 || state == 1) ? BIT_USB_HEADSET_ANLG : 0));
            // This sets the switchsate to 4 (for USB HEADSET - BIT_USB_HEADSET_ANLG)
            // Looking at the other types, maybe the state that emitted should be a 1 and at
            //       /devices/virtual/switch/usb_audio
            //
            // However the we need to deal with changes at
            //       /devices/virtual/switch/dock
            // for the state of 2 - means that we have a USB ANLG headset Car Dock
            // for the state of 1 - means that we have a USB ANLG headset Desk Dock
        } else if (name.equals("hdmi")) {
            switchState = ((mHeadsetState & (BIT_HEADSET|BIT_HEADSET_NO_MIC|
                                             BIT_USB_HEADSET_DGTL|BIT_USB_HEADSET_ANLG)) |
                           ((state == 1) ? BIT_HDMI_AUDIO : 0));
        } else if (name.equals("Headset")) {
            switchState = ((mHeadsetState & (BIT_HDMI_AUDIO|BIT_USB_HEADSET_ANLG|
                                             BIT_USB_HEADSET_DGTL)) |
                                             (state & (BIT_HEADSET|BIT_HEADSET_NO_MIC)));
        } else {
            switchState = ((mHeadsetState & (BIT_HDMI_AUDIO|BIT_USB_HEADSET_ANLG|
                                             BIT_USB_HEADSET_DGTL)) |
                            ((state == 1) ? BIT_HEADSET :
                                          ((state == 2) ? BIT_HEADSET_NO_MIC : 0)));
        }
        if (LOG) Slog.v(TAG, "updateState switchState: " + switchState);
        return switchState;
       }
    }

    private static List<UEventInfo> makeObservedUEventList() {
        List<UEventInfo> retVal = new ArrayList<UEventInfo>();
        UEventInfo uei;

        // Monitor h2w
        uei = new UEventInfo("h2w", BIT_HEADSET, BIT_HEADSET_NO_MIC);
        if (uei.checkSwitchExists()) {
            retVal.add(uei);
        } else {
            Slog.w(TAG, "This kernel does not have wired headset support");
        }

        // Monitor USB
        uei = new UEventInfo("usb_audio", BIT_USB_HEADSET_ANLG, BIT_USB_HEADSET_DGTL);
        if (uei.checkSwitchExists()) {
            retVal.add(uei);
        } else {
            Slog.w(TAG, "This kernel does not have usb audio support");
        }

        // Monitor Samsung USB audio
        uei = new UEventInfo("dock", BIT_USB_HEADSET_DGTL, BIT_USB_HEADSET_ANLG);
        if (uei.checkSwitchExists()) {
            retVal.add(uei);
        } else {
            Slog.w(TAG, "This kernel does not have samsung usb dock audio support");
        }

        // Monitor HDMI
        //
        // If the kernel has support for the "hdmi_audio" switch, use that.  It will be signalled
        // only when the HDMI driver has a video mode configured, and the downstream sink indicates
        // support for audio in its EDID.
        //
        // If the kernel does not have an "hdmi_audio" switch, just fall back on the older "hdmi"
        // switch instead.
        uei = new UEventInfo("hdmi_audio", BIT_HDMI_AUDIO, 0);
        if (uei.checkSwitchExists()) {
            retVal.add(uei);
        } else {
            uei = new UEventInfo("hdmi", BIT_HDMI_AUDIO, 0);
            if (uei.checkSwitchExists()) {
                retVal.add(uei);
            } else {
                Slog.w(TAG, "This kernel does not have HDMI audio support");
            }
        }

        return retVal;
    }

    private static List<UEventInfo> uEventInfo = makeObservedUEventList();

    private static int mHeadsetState;
    private int mPrevHeadsetState;
    private String mHeadsetName;
    private boolean dockAudioEnabled = false;

    private final Context mContext;
    private final WakeLock mWakeLock;  // held while there is a pending route change

    private final AudioManager mAudioManager;

    public WiredAccessoryObserver(Context context) {
        mContext = context;
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WiredAccessoryObserver");
        mWakeLock.setReferenceCounted(false);
        mAudioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);

        File f = new File("/sys/class/switch/dock/state");
        if (f!=null && f.exists()) {
            // Listen out for changes to the Dock Audio Settings
            context.registerReceiver(new SettingsChangedReceiver(),
            new IntentFilter("com.cyanogenmod.settings.SamsungDock"), null, null);
        }
        context.registerReceiver(new BootCompletedReceiver(),
            new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, null);
        
        // Observe ALSA usb audio events
        this.mUsbAudioObserver.startObserving("MAJOR=116");
    }

    private final class SettingsChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Slog.e(TAG, "Recieved a Settings Changed Action " + action);
            if (action.equals("com.cyanogenmod.settings.SamsungDock")) {
                String data = intent.getStringExtra("data");
                Slog.e(TAG, "Recieved a Dock Audio change " + data);
                if (data != null && data.equals("1")) {
                    dockAudioEnabled = true;
                } else {
                    dockAudioEnabled = false;
                }
            }
        }
    }

    private final class BootCompletedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // At any given time accessories could be inserted
            // one on the board, one on the dock, one on the samsung dock and one on HDMI:
            // observe all UEVENTs that have a valid switch supported by the Kernel
            init();  // set initial status
            for (int i = 0; i < uEventInfo.size(); ++i) {
                UEventInfo uei = uEventInfo.get(i);
                startObserving("DEVPATH="+uei.getDevPath());
            }
        }
    }

    @Override
    public void onUEvent(UEventObserver.UEvent event) {
        if (LOG) Slog.v(TAG, "Headset UEVENT: " + event.toString());

        int state = Integer.parseInt(event.get("SWITCH_STATE"));
        try {
            String devPath = event.get("DEVPATH");
            String name = event.get("SWITCH_NAME");
            if (sDockNames.contains(name)) {
                // Samsung USB Audio Jack is non-sensing - so must be enabled manually
                // The choice is made in the GalaxyS2Settings.apk
                // device/samsung/i9100/DeviceSettings/src/com/cyanogenmod/settings/device/DockFragmentActivity.java
                // This sends an Intent to this class
                if ((!dockAudioEnabled) && (state > 0)) {
                    Slog.e(TAG, "Ignoring dock event as Audio routing disabled " + event);
                    return;
                }
            }
            updateState(devPath, name, state);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Could not parse switch state from event " + event);
        }
    }

    private synchronized final void updateState(String devPath, String name, int state) {
        for (int i = 0; i < uEventInfo.size(); ++i) {
            UEventInfo uei = uEventInfo.get(i);
            if (devPath.equals(uei.getDevPath())) {
                update(name, uei.computeNewHeadsetState(name, state));
                return;
            }
        }
    }

    private synchronized final void init() {
        char[] buffer = new char[1024];
        mPrevHeadsetState = mHeadsetState;

        if (LOG) Slog.v(TAG, "init()");

        for (int i = 0; i < uEventInfo.size(); ++i) {
            UEventInfo uei = uEventInfo.get(i);
            try {
                int curState;
                FileReader file = new FileReader(uei.getSwitchStatePath());
                int len = file.read(buffer, 0, 1024);
                file.close();
                curState = Integer.valueOf((new String(buffer, 0, len)).trim());

                if (curState > 0) {
                    updateState(uei.getDevPath(), uei.getDevName(), curState);
                }

            } catch (FileNotFoundException e) {
                Slog.w(TAG, uei.getSwitchStatePath() +
                        " not found while attempting to determine initial switch state");
            } catch (Exception e) {
                Slog.e(TAG, "" , e);
            }
        }
    }

    private synchronized final void update(String newName, int newState) {
        // Retain only relevant bits
        int headsetState = newState & SUPPORTED_HEADSETS;
        int newOrOld = headsetState | mHeadsetState;
        int delay = 0;
        int usb_headset_anlg = headsetState & BIT_USB_HEADSET_ANLG;
        int usb_headset_dgtl = headsetState & BIT_USB_HEADSET_DGTL;
        int h2w_headset = headsetState & (BIT_HEADSET | BIT_HEADSET_NO_MIC);
        boolean h2wStateChange = true;
        boolean usbStateChange = true;
        // reject all suspect transitions: only accept state changes from:
        // - a: 0 heaset to 1 headset
        // - b: 1 headset to 0 headset
        if (LOG) Slog.v(TAG, "newState = "+newState+", headsetState = "+headsetState+","
            + "mHeadsetState = "+mHeadsetState);
        if (mHeadsetState == headsetState || ((h2w_headset & (h2w_headset - 1)) != 0)) {
            Log.e(TAG, "unsetting h2w flag");
            h2wStateChange = false;
        }
        // - c: 0 usb headset to 1 usb headset
        // - d: 1 usb headset to 0 usb headset
        if ((usb_headset_anlg >> 2) == 1 && (usb_headset_dgtl >> 3) == 1) {
            Log.e(TAG, "unsetting usb flag");
            usbStateChange = false;
        }
        if (!h2wStateChange && !usbStateChange) {
            Log.e(TAG, "invalid transition, returning ...");
            return;
        }

        mHeadsetName = newName;
        mPrevHeadsetState = mHeadsetState;
        mHeadsetState = headsetState;

        mWakeLock.acquire();
        mHandler.sendMessage(mHandler.obtainMessage(0,
                                                    mHeadsetState,
                                                    mPrevHeadsetState,
                                                    mHeadsetName));
    }

    private synchronized final void setDevicesState(int headsetState,
                                                    int prevHeadsetState,
                                                    String headsetName) {
        int allHeadsets = SUPPORTED_HEADSETS;
        for (int curHeadset = 1; allHeadsets != 0; curHeadset <<= 1) {
            if ((curHeadset & allHeadsets) != 0) {
                setDeviceState(curHeadset, headsetState, prevHeadsetState, headsetName);
                allHeadsets &= ~curHeadset;
            }
        }
    }

    private final void setDeviceState(int headset,
                                      int headsetState,
                                      int prevHeadsetState,
                                      String headsetName) {
        if ((headsetState & headset) != (prevHeadsetState & headset)) {
            int device;
            int state;

            if ((headsetState & headset) != 0) {
                state = 1;
            } else {
                state = 0;
            }

            if (headset == BIT_HEADSET) {
                device = AudioManager.DEVICE_OUT_WIRED_HEADSET;
            } else if (headset == BIT_HEADSET_NO_MIC){
                device = AudioManager.DEVICE_OUT_WIRED_HEADPHONE;
            } else if (headset == BIT_USB_HEADSET_ANLG) {
                device = AudioManager.DEVICE_OUT_ANLG_DOCK_HEADSET;
            } else if (headset == BIT_USB_HEADSET_DGTL) {
                device = AudioManager.DEVICE_OUT_DGTL_DOCK_HEADSET;
            } else if (headset == BIT_HDMI_AUDIO) {
                device = AudioManager.DEVICE_OUT_AUX_DIGITAL;
            } else {
                Slog.e(TAG, "setDeviceState() invalid headset type: "+headset);
                return;
            }

            if (LOG)
                Slog.v(TAG, "device "+headsetName+((state == 1) ? " connected" : " disconnected"));

            mAudioManager.setWiredDeviceConnectionState(device, state, headsetName);
        }
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            setDevicesState(msg.arg1, msg.arg2, (String)msg.obj);
            mWakeLock.release();
        }
    };
    
	private final UEventObserver mUsbAudioObserver = new UEventObserver() {
		public void onUEvent(UEventObserver.UEvent event) {
			if(LOG) Slog.v(WiredAccessoryObserver.TAG, "USB AUDIO UEVENT: " + event.toString());
			String action = event.get("ACTION");
			String devpath = event.get("DEVPATH");
			String major = event.get("MAJOR");
			String minor = event.get("MINOR");
			String devname = event.get("DEVNAME");
			if(LOG) Slog.v(WiredAccessoryObserver.TAG, "onUEvent(device) :: action = " + action + ", MAJOR = " + major + ", MINOR = " + minor + ", DEVPATH = " + devpath);
			
			String cardNumber;
			String deviceNumber;
			String channels;
			
			if (major.equals("116")) {
				
				String devpath_lower = devpath.toLowerCase();
				
				if ((devpath_lower.contains("usb")) && (!devpath_lower.contains("gadget")) && (devname.endsWith("p"))) {
					cardNumber = Character.toString(devname.charAt(8));
					deviceNumber = Character.toString(devname.charAt(10));
					channels = String.valueOf(WiredAccessoryObserver.this.getChannels(cardNumber));
					if(LOG) Slog.v(WiredAccessoryObserver.TAG, "cardNumber="+ cardNumber + " deviceNumber=" + deviceNumber + " channels=" + channels);
					
					int state = 0;
					
					if (action.equals("add")) {
						state = 1;
					} else {
						state = 0;
					}
					
					// Update USB audio details (and then inform the system that an USB audio device has been connected)
					WiredAccessoryObserver.this.update_usbaudio(state, channels, cardNumber, deviceNumber);	
				}
			}
		}
	};
	
	private int getChannels(String cardNumber) {
		int channels = 0;
		String streamContent = "";
		String streamPath = "/proc/asound/card" + cardNumber + "/stream0";
		
		// Parse channel count from /proc/asound/card[n]/stream0
		try {
			FileReader file = new FileReader(streamPath);
			BufferedReader br = new BufferedReader(new FileReader(streamPath));
			
			String line;
			while ((line = br.readLine()) != null) {
				streamContent += line;
			}
			
			br.close();
			
			Pattern regex = Pattern.compile("Playback:.*?Channels: .*?(\\d)", Pattern.DOTALL);
			Matcher regexMatcher = regex.matcher(streamContent);
			if (regexMatcher.find()) {
				channels = Integer.parseInt(regexMatcher.group(1));
			}
			
			if(LOG) Slog.v(TAG, "parsed channels = " + channels);
		}
		catch(FileNotFoundException e) {
			Slog.e(TAG, "Unable to get channels for " + streamPath);
		}
		catch(NumberFormatException e) {
			Slog.e(TAG, "Unable to parse channels for " + streamPath);
		}
		catch(Exception e){
			Slog.e(TAG, "" , e);
		}
		
		if(channels > 0) {
			return channels;
		}
		
		return 2;
	}
	
	private final void update_usbaudio(int state, String channels, String cardNumber, String deviceNumber) {
		try {
			// Send AUDIO_BECOMING_NOISY intent to warn applications of output switch
			Intent localIntent = new Intent("android.media.AUDIO_BECOMING_NOISY");
			this.mContext.sendBroadcast(localIntent);
			
			UsbAudioData localUsbAudioData = new UsbAudioData();
			localUsbAudioData.setUsbAudioData(state, channels,cardNumber, deviceNumber);
			
			this.mWakeLock.acquire();
			
			this.mHandler_usbAudio.sendMessageDelayed(this.mHandler_usbAudio.obtainMessage(0, localUsbAudioData), 500);
			
		} finally {
		}
	}
	
	private final Handler mHandler_usbAudio = new Handler() {
		public void handleMessage(Message message) {
			
			UsbAudioData usbAudioData = (UsbAudioData)message.obj;
			
			// Send USB_AUDIO_ACCESSORY_PLUG intent to notify that an USB audio device has been connected.
			Intent localIntent = new Intent("android.intent.action.USB_AUDIO_ACCESSORY_PLUG");
			localIntent.putExtra("state", usbAudioData.getState());
			localIntent.putExtra("card", Integer.parseInt(usbAudioData.getCardNumber()));
			localIntent.putExtra("device", Integer.parseInt(usbAudioData.getDeviceNumber()));
			localIntent.putExtra("channels", Integer.parseInt(usbAudioData.getChannels()));
			WiredAccessoryObserver.this.mContext.sendStickyBroadcast(localIntent);
			
			WiredAccessoryObserver.this.mWakeLock.release();
		}
	};
	
	private final class UsbAudioData {
		private String cardNumber;
		private String channels;
		private String deviceNumber;
		private int state;

		public String getCardNumber() {
			return this.cardNumber;
		}

		public String getChannels() {
			return this.channels;
		}

		public String getDeviceNumber() {
			return this.deviceNumber;
		}

		public int getState() {
			return this.state;
		}

		public void setUsbAudioData(int state, String channels, String cardNumber, String deviceNumber) {
			this.state = state;
			this.channels = channels;
			this.cardNumber = cardNumber;
			this.deviceNumber = deviceNumber;
		}
	}
}
