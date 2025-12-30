package com.android.commands.hid;

import android.util.Log;
import android.util.SparseArray;

public class Hid {
    private static final String TAG = "HID";

    private final SparseArray<Device> mDevices = new SparseArray<>();

    // This class is mostly a wrapper; the real work is in Device
    public void registerDevice(int id, String name, String uniq, int vid, int pid, int bus, byte[] descriptor) {
        Device d = new Device(id, name, uniq, vid, pid, bus, descriptor);
        mDevices.append(id, d);
    }

    public Device getDevice(int id) {
        return mDevices.get(id);
    }

    public void unregisterDevice(int id) {
        Device d = mDevices.get(id);
        if (d != null) {
            d.close();
            mDevices.remove(id);
        }
    }

    private static void error(String msg, Exception e) {
        Log.e(TAG, msg, e);
    }
}
