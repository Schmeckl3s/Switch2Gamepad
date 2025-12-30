package com.android.commands.hid;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

public class Device {
    private static final String TAG = "HidDevice";

    private static final int MSG_OPEN_DEVICE = 1;
    private static final int MSG_SEND_REPORT = 2;
    private static final int MSG_CLOSE_DEVICE = 3;

    static {
        System.loadLibrary("hidcommand_jni");
    }

    private final int mId;
    private final String mUniq;  // New for Android 15
    private final HandlerThread mThread;
    private final Handler mHandler;

    private native long nativeOpenDevice(String name, String uniq, int vid, int pid, int bus, byte[] descriptor);
    private native void nativeSendReport(long ptr, byte[] data);
    private native void nativeCloseDevice(long ptr);

    public Device(int id, String name, String uniq, int vid, int pid, int bus, byte[] descriptor) {
        mId = id;
        mUniq = uniq;  // Use empty or unique string
        mThread = new HandlerThread("HidDeviceHandler");
        mThread.start();
        mHandler = new Handler(mThread.getLooper()) {
            private long mPtr;

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_OPEN_DEVICE:
                        mPtr = nativeOpenDevice(name, mUniq, vid, pid, bus, descriptor);
                        break;
                    case MSG_SEND_REPORT:
                        if (mPtr != 0) {
                            nativeSendReport(mPtr, (byte[]) msg.obj);
                        }
                        break;
                    case MSG_CLOSE_DEVICE:
                        if (mPtr != 0) {
                            nativeCloseDevice(mPtr);
                        }
                        mThread.quitSafely();
                        break;
                }
            }
        };
        mHandler.sendEmptyMessage(MSG_OPEN_DEVICE);
    }

    public void sendReport(byte[] report) {
        mHandler.obtainMessage(MSG_SEND_REPORT, report).sendToTarget();
    }

    public void close() {
        mHandler.sendEmptyMessage(MSG_CLOSE_DEVICE);
    }
}