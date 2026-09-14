package com.embedded.carremotecontrol;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class BleCarManager {
    public interface Listener {
        void onDevicesChanged(List<DeviceItem> devices);
        void onSlotChanged(String slot, SlotSnapshot snapshot);
        void onLog(String level, String message, String details);
    }

    public static final class DeviceItem {
        public final BluetoothDevice device;
        public final String name;
        public final String address;
        public final int rssi;

        DeviceItem(BluetoothDevice device, String name, int rssi) {
            this.device = device;
            this.name = name;
            this.address = device.getAddress();
            this.rssi = rssi;
        }
    }

    public static final class SlotSnapshot {
        public final String slot;
        public final String name;
        public final String address;
        public final boolean connecting;
        public final boolean connected;
        public final String characteristic;

        SlotSnapshot(String slot, String name, String address, boolean connecting,
                     boolean connected, String characteristic) {
            this.slot = slot;
            this.name = name;
            this.address = address;
            this.connecting = connecting;
            this.connected = connected;
            this.characteristic = characteristic;
        }
    }

    private static final class Connection {
        final String slot;
        final BluetoothDevice device;
        final String name;
        // 用 ThreadPoolExecutor 而不是 Executors.newSingleThreadExecutor()，是为了能拿到队列引用：
        // 停止类指令需要清空排队的普通指令、并软打断当前卡在回执等待上的写。
        final ThreadPoolExecutor writeQueue;
        volatile BluetoothGatt gatt;
        volatile BluetoothGattCharacteristic writable;
        volatile boolean connecting = true;
        volatile boolean connected = false;
        volatile CountDownLatch writeLatch;
        volatile int writeStatus = BluetoothGatt.GATT_FAILURE;

        Connection(String slot, BluetoothDevice device, String name) {
            this.slot = slot;
            this.device = device;
            this.name = name;
            this.writeQueue = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "ble-write-" + slot);
                    thread.setDaemon(true);
                    return thread;
                }
            });
        }

        boolean ready() {
            return connected && writable != null && gatt != null;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final Map<String, DeviceItem> discovered = new LinkedHashMap<>();
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private BluetoothLeScanner scanner;
    private boolean scanning;

    public BleCarManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
        this.adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
    }

    public BluetoothAdapter getAdapter() {
        return adapter;
    }

    public boolean isScanning() {
        return scanning;
    }

    @SuppressLint("MissingPermission")
    public synchronized void startScan() {
        if (adapter == null) {
            log("error", "此手机不支持蓝牙", "");
            return;
        }
        if (!adapter.isEnabled()) {
            log("error", "系统蓝牙未打开", "");
            return;
        }
        if (scanning) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            log("error", "BLE 扫描器不可用", "");
            return;
        }
        discovered.clear();
        emitDevices();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            ScanFilter serviceFilter = new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(CarProtocol.SERVICE_UUID))
                    .build();
            scanner.startScan(Collections.singletonList(serviceFilter), settings, scanCallback);
            scanning = true;
            log("info", "正在扫描 FFF0 小车设备", CarProtocol.SERVICE_UUID.toString());
        } catch (SecurityException error) {
            log("error", "缺少蓝牙扫描权限", error.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    public synchronized void stopScan() {
        if (!scanning) return;
        try {
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (SecurityException ignored) {
        }
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name;
            try {
                name = device.getName();
            } catch (SecurityException error) {
                name = null;
            }
            if (name == null || name.trim().isEmpty()) {
                name = "未命名设备 " + tail(device.getAddress());
            }
            synchronized (BleCarManager.this) {
                discovered.put(device.getAddress(), new DeviceItem(device, name, result.getRssi()));
            }
            emitDevices();
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            log("error", "BLE 扫描失败", "错误码 " + errorCode);
        }
    };

    private void emitDevices() {
        List<DeviceItem> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(discovered.values());
        }
        snapshot.sort(Comparator
                .comparing((DeviceItem item) -> item.name.startsWith("未命名设备"))
                .thenComparing((DeviceItem item) -> -item.rssi));
        mainHandler.post(() -> listener.onDevicesChanged(snapshot));
    }

    @SuppressLint("MissingPermission")
    public synchronized boolean connect(String slot, DeviceItem device) {
        for (Map.Entry<String, Connection> entry : connections.entrySet()) {
            if (!entry.getKey().equals(slot)
                    && entry.getValue().device.getAddress().equals(device.address)) {
                log("error", "该设备已经连接为 " + CarProtocol.slotDisplayName(entry.getKey()), device.name);
                return false;
            }
        }
        removeConnection(slot, true);
        Connection connection = new Connection(slot, device.device, device.name);
        connections.put(slot, connection);
        emitSlot(slot);
        log("info", "正在连接 " + CarProtocol.slotDisplayName(slot), device.name);
        stopScan();
        try {
            BluetoothGattCallback callback = callbackFor(connection);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connection.gatt = device.device.connectGatt(
                        context, false, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                connection.gatt = device.device.connectGatt(context, false, callback);
            }
            if (connection.gatt == null) throw new IllegalStateException("connectGatt 返回空连接");
            return true;
        } catch (Exception error) {
            log("error", CarProtocol.slotDisplayName(slot) + "连接失败", safeMessage(error));
            removeConnection(slot, true);
            emitSlot(slot);
            return false;
        }
    }

    public synchronized boolean reconnect(String slot) {
        Connection previous = connections.get(slot);
        if (previous == null) return false;
        DeviceItem device = new DeviceItem(previous.device, previous.name, -120);
        return connect(slot, device);
    }

    private BluetoothGattCallback callbackFor(Connection connection) {
        return new BluetoothGattCallback() {
            @Override
            @SuppressLint("MissingPermission")
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (status == BluetoothGatt.GATT_SUCCESS
                        && newState == BluetoothProfile.STATE_CONNECTED) {
                    connection.connected = true;
                    connection.connecting = true;
                    emitSlot(connection.slot);
                    log("info", CarProtocol.slotDisplayName(connection.slot) + "链路已建立", connection.name);
                    if (!gatt.discoverServices()) {
                        failConnection(connection, "无法启动服务发现");
                    }
                    return;
                }
                if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    connection.connected = false;
                    connection.connecting = false;
                    connection.writable = null;
                    emitSlot(connection.slot);
                    log("error", CarProtocol.slotDisplayName(connection.slot) + "连接已断开",
                            status == BluetoothGatt.GATT_SUCCESS ? connection.name : "GATT " + status);
                    try {
                        gatt.close();
                    } catch (Exception ignored) {
                    }
                }
            }

            @Override
            @SuppressLint("MissingPermission")
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection(connection, "服务发现失败：GATT " + status);
                    return;
                }
                BluetoothGattService service = gatt.getService(CarProtocol.SERVICE_UUID);
                if (service == null) {
                    failConnection(connection, "设备不包含 FFF0 控制服务");
                    return;
                }
                BluetoothGattCharacteristic writable = service.getCharacteristic(CarProtocol.PREFERRED_WRITE_UUID);
                if (!isWritable(writable)) {
                    writable = service.getCharacteristic(CarProtocol.FALLBACK_WRITE_UUID);
                }
                if (!isWritable(writable)) {
                    for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                        if (isWritable(characteristic)) {
                            writable = characteristic;
                            break;
                        }
                    }
                }
                if (!isWritable(writable)) {
                    failConnection(connection, "设备没有可写入的 BLE 特征");
                    return;
                }
                int properties = writable.getProperties();
                writable.setWriteType((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                connection.writable = writable;
                connection.connecting = false;
                connection.connected = true;
                emitSlot(connection.slot);
                log("success", CarProtocol.slotDisplayName(connection.slot) + "连接成功",
                        connection.name + " / " + writable.getUuid());
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt,
                                              BluetoothGattCharacteristic characteristic,
                                              int status) {
                connection.writeStatus = status;
                CountDownLatch latch = connection.writeLatch;
                if (latch != null) latch.countDown();
            }
        };
    }

    private static boolean isWritable(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) return false;
        int properties = characteristic.getProperties();
        return (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                || (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
    }

    @SuppressLint("MissingPermission")
    private void failConnection(Connection connection, String message) {
        connection.connected = false;
        connection.connecting = false;
        connection.writable = null;
        log("error", CarProtocol.slotDisplayName(connection.slot) + "连接失败", message);
        emitSlot(connection.slot);
        try {
            if (connection.gatt != null) connection.gatt.disconnect();
        } catch (Exception ignored) {
        }
    }

    public synchronized SlotSnapshot snapshot(String slot) {
        Connection connection = connections.get(slot);
        if (connection == null) return null;
        return new SlotSnapshot(slot, connection.name, connection.device.getAddress(),
                connection.connecting, connection.ready(),
                connection.writable == null ? "" : connection.writable.getUuid().toString());
    }

    public synchronized List<String> connectedSlots() {
        List<String> slots = new ArrayList<>();
        for (Map.Entry<String, Connection> entry : connections.entrySet()) {
            if (entry.getValue().ready()) slots.add(entry.getKey());
        }
        return slots;
    }

    public boolean send(String slot, byte[] packet, String label) {
        return enqueue(slot, packet, label, false);
    }

    /**
     * 停止类指令（急停 / 停止 / 倒车停止 / 安全停止 / 断开前停止）专用通道。
     *
     * 普通 send() 只是入队，若链路处于「僵尸」状态（connected 仍为 true 但已不回 ACK），
     * 每条写要等满 2 秒超时，无界队列会持续积压，此时急停会被排在队尾等上数十秒。
     * 本方法先清空排队中的普通指令，再尝试软打断当前阻塞中的写等待，让停止包成为下一个被执行的写。
     */
    public boolean sendUrgent(String slot, byte[] packet, String label) {
        return enqueue(slot, packet, label, true);
    }

    private boolean enqueue(String slot, byte[] packet, String label, boolean urgent) {
        final Connection connection;
        synchronized (this) {
            connection = connections.get(slot);
        }
        if (connection == null || !connection.ready()) return false;
        byte[] copy = Arrays.copyOf(packet, packet.length);
        if (urgent) {
            connection.writeQueue.getQueue().clear();
            CountDownLatch inFlight = connection.writeLatch;
            if (inFlight != null) {
                // 软打断：释放正在 await 的那条写，它会以 GATT_FAILURE 提前返回，
                // 写线程随即取下一条任务（也就是本条停止包）。不中断线程，避免影响 GATT 栈。
                inFlight.countDown();
            }
        }
        connection.writeQueue.execute(() -> write(connection, copy, label, urgent));
        return true;
    }

    @SuppressLint("MissingPermission")
    private void write(Connection connection, byte[] packet, String label, boolean urgent) {
        BluetoothGatt gatt = connection.gatt;
        BluetoothGattCharacteristic characteristic = connection.writable;
        if (gatt == null || characteristic == null || !connection.connected) return;

        int writeType = characteristic.getWriteType();
        boolean launched = launchWrite(connection, packet, writeType);
        if (!launched && writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            characteristic.setWriteType(writeType);
            launched = launchWrite(connection, packet, writeType);
        }
        if (!launched && urgent) {
            // 停止类指令不允许静默失败：上一条写刚被软打断时 GATT 可能短暂繁忙，重试几次。
            for (int attempt = 0; attempt < 3 && !launched; attempt++) {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
                launched = launchWrite(connection, packet, writeType);
            }
        }
        if (!launched) {
            log("error", CarProtocol.slotDisplayName(connection.slot) + "写入失败", label);
            return;
        }

        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
            try {
                CountDownLatch latch = connection.writeLatch;
                long timeoutMs = urgent ? 250 : 2000;
                if (latch != null && !latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    log("error", CarProtocol.slotDisplayName(connection.slot) + "写入超时", label);
                    return;
                }
                if (connection.writeStatus != BluetoothGatt.GATT_SUCCESS) {
                    log("error", CarProtocol.slotDisplayName(connection.slot) + "写入失败", "GATT " + connection.writeStatus);
                    return;
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                connection.writeLatch = null;
            }
        } else {
            try {
                Thread.sleep(24);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log("tx", CarProtocol.slotDisplayName(connection.slot) + " " + label, CarProtocol.toHex(packet));
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    private boolean launchWrite(Connection connection, byte[] packet, int writeType) {
        connection.writeStatus = BluetoothGatt.GATT_FAILURE;
        connection.writeLatch = writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ? new CountDownLatch(1) : null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int result = connection.gatt.writeCharacteristic(connection.writable, packet, writeType);
            return result == BluetoothStatusCodes.SUCCESS;
        }
        connection.writable.setWriteType(writeType);
        connection.writable.setValue(packet);
        return connection.gatt.writeCharacteristic(connection.writable);
    }

    public synchronized void disconnect(String slot, boolean clear) {
        Connection connection = connections.get(slot);
        if (connection == null) return;
        removeConnection(slot, clear);
        if (!clear) {
            connection.connected = false;
            connection.connecting = false;
            connection.writable = null;
        }
        emitSlot(slot);
    }

    @SuppressLint("MissingPermission")
    private void removeConnection(String slot, boolean clear) {
        Connection connection = connections.get(slot);
        if (connection == null) return;
        try {
            if (connection.gatt != null) {
                connection.gatt.disconnect();
                connection.gatt.close();
            }
        } catch (Exception ignored) {
        }
        connection.connected = false;
        connection.connecting = false;
        connection.writable = null;
        if (clear) {
            connections.remove(slot);
            connection.writeQueue.shutdownNow();
        }
    }

    public void safeStopAll() {
        for (String slot : connectedSlots()) {
            sendUrgent(slot, CarProtocol.HOLD_AND_STOP, "安全停止");
        }
    }

    public synchronized void closeAll() {
        stopScan();
        for (String slot : new ArrayList<>(connections.keySet())) {
            removeConnection(slot, true);
        }
    }

    private void emitSlot(String slot) {
        SlotSnapshot snapshot = snapshot(slot);
        mainHandler.post(() -> listener.onSlotChanged(slot, snapshot));
    }

    private void log(String level, String message, String details) {
        String safeDetails = details == null ? "" : details;
        mainHandler.post(() -> listener.onLog(level, message, safeDetails));
    }

    private static String tail(String address) {
        if (address == null) return "-----";
        return address.length() <= 5 ? address : address.substring(address.length() - 5);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    public static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
    }
}
