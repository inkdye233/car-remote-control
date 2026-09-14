package com.embedded.dualcarcontroller;

import android.Manifest;
import android.annotation.SuppressLint;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity implements BleCarManager.Listener {
    private static final int REQUEST_PERMISSIONS = 2001;
    private static final int REQUEST_ENABLE_BLUETOOTH = 2002;
    private static final List<String> SLOT_IDS = Arrays.asList("A", "B");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, BleCarManager.SlotSnapshot> slots = new HashMap<>();
    private final Map<String, Integer> gears = new HashMap<>();
    private final Map<String, Boolean> moving = new HashMap<>();
    private final Deque<String> logLines = new ArrayDeque<>();

    private BleCarManager bleManager;
    private String target = "BOTH";
    private String activeScanSlot = "A";
    private boolean turning;
    private byte[] turnPacket;
    private String turnLabel;
    private boolean reversing;
    private final List<String> reverseTargets = new ArrayList<>();

    private TextView onlineCount;
    private TextView adapterState;
    private TextView slotAName;
    private TextView slotAStatus;
    private TextView slotAGear;
    private TextView slotAMotion;
    private Button slotAAction;
    private MaterialCardView slotACard;
    private TextView slotBName;
    private TextView slotBStatus;
    private TextView slotBGear;
    private TextView slotBMotion;
    private Button slotBAction;
    private MaterialCardView slotBCard;
    private TextView logText;
    private Button emergencyButton;
    private AlertDialog scanDialog;
    private DeviceAdapter deviceAdapter;
    private int previousOnlineCount = -1;
    private boolean emergencyPulseRunning;

    // 转向连发循环。必须由 stopTurn() 真正取消：早前用的是 bindTurnButton 里的匿名 Runnable，
    // 无法被取消，导致「快速松开再按下」时旧循环复活、与新的并存，转向指令速率翻倍。
    private final Runnable turnRunnable = new Runnable() {
        @Override
        public void run() {
            if (!turning) return;
            send(selectedReadySlots(false), turnPacket, turnLabel);
            handler.postDelayed(this, 150);
        }
    };

    private final Runnable holdRunnable = new Runnable() {
        @Override
        public void run() {
            List<String> active = new ArrayList<>();
            for (String slot : SLOT_IDS) {
                // 倒车持续指令由 reverseRunnable 单独维护，这里绝不能给它补发 HOLD_AND_STOP，
                // 否则「保持/停止」报文会和倒车互相打架。
                if (reversing && reverseTargets.contains(slot)) continue;
                if (Boolean.TRUE.equals(moving.get(slot)) && isReady(slot)) active.add(slot);
            }
            if (active.isEmpty()) return;
            send(active, CarProtocol.HOLD_AND_STOP, "保持");
            handler.postDelayed(this, 200);
        }
    };

    private final Runnable reverseRunnable = new Runnable() {
        @Override
        public void run() {
            if (!reversing) return;
            List<String> active = new ArrayList<>();
            for (String slot : reverseTargets) {
                if (isReady(slot)) active.add(slot);
            }
            if (active.isEmpty()) return;
            send(active, CarProtocol.REVERSE, "倒车");
            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(getColor(R.color.bg));
        getWindow().setNavigationBarColor(getColor(R.color.bg));
        int systemUiFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            systemUiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(systemUiFlags);

        for (String slot : SLOT_IDS) {
            gears.put(slot, 0);
            moving.put(slot, false);
        }

        bindViews();
        bleManager = new BleCarManager(this, this);
        bindActions();
        updateUi();
        runEntranceAnimation();
        ensureBleReady();
    }

    private void bindViews() {
        onlineCount = findViewById(R.id.online_count);
        adapterState = findViewById(R.id.adapter_state);
        slotAName = findViewById(R.id.slot_a_name);
        slotAStatus = findViewById(R.id.slot_a_status);
        slotAGear = findViewById(R.id.slot_a_gear);
        slotAMotion = findViewById(R.id.slot_a_motion);
        slotAAction = findViewById(R.id.slot_a_action);
        slotACard = findViewById(R.id.slot_a_card);
        slotBName = findViewById(R.id.slot_b_name);
        slotBStatus = findViewById(R.id.slot_b_status);
        slotBGear = findViewById(R.id.slot_b_gear);
        slotBMotion = findViewById(R.id.slot_b_motion);
        slotBAction = findViewById(R.id.slot_b_action);
        slotBCard = findViewById(R.id.slot_b_card);
        logText = findViewById(R.id.log_text);
        emergencyButton = findViewById(R.id.emergency_button);
    }

    private void bindActions() {
        findViewById(R.id.adapter_action).setOnClickListener(view -> ensureBleReady());
        slotAAction.setOnClickListener(view -> handleSlotAction("A"));
        slotBAction.setOnClickListener(view -> handleSlotAction("B"));

        MaterialButtonToggleGroup targetGroup = findViewById(R.id.target_group);
        targetGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.target_a) target = "A";
            else if (checkedId == R.id.target_b) target = "B";
            else target = "BOTH";
            group.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        });

        bindTurnButton(findViewById(R.id.turn_left), CarProtocol.TURN_LEFT, "左转");
        bindTurnButton(findViewById(R.id.turn_right), CarProtocol.TURN_RIGHT, "右转");
        bindReverseButton(findViewById(R.id.reverse));
        findViewById(R.id.accelerate).setOnClickListener(view -> accelerate());
        findViewById(R.id.stop_selected).setOnClickListener(view -> stopSelected());
        findViewById(R.id.push_mode).setOnClickListener(view -> pushMode());
        findViewById(R.id.clear_logs).setOnClickListener(view -> {
            logLines.clear();
            renderLogs();
        });
        emergencyButton.setOnClickListener(view -> emergencyStop());
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindTurnButton(Button button, byte[] packet, String label) {
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                List<String> selected = selectedReadySlots(true);
                if (selected.isEmpty()) return true;
                stopTurn();
                turning = true;
                turnPacket = packet;
                turnLabel = label;
                view.setPressed(true);
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                send(selected, packet, label);
                vibrate(30);
                handler.postDelayed(turnRunnable, 150);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.setPressed(false);
                stopTurn();
                return true;
            }
            return false;
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindReverseButton(Button button) {
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                List<String> selected = selectedReadySlots(true);
                if (selected.isEmpty()) return true;
                stopReverse(false);
                stopTurn();
                resetMotion(selected);
                reversing = true;
                reverseTargets.clear();
                reverseTargets.addAll(selected);
                view.setPressed(true);
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                send(selected, CarProtocol.REVERSE, "倒车");
                for (String slot : selected) {
                    gears.put(slot, -1);
                    moving.put(slot, true);
                }
                restartReverseTimer();
                updateUi();
                vibrate(30);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.setPressed(false);
                stopReverse(true);
                return true;
            }
            return false;
        });
    }

    private void stopReverse(boolean sendStop) {
        if (!reversing) return;
        reversing = false;
        handler.removeCallbacks(reverseRunnable);
        List<String> targets = new ArrayList<>(reverseTargets);
        reverseTargets.clear();
        if (sendStop && !targets.isEmpty()) {
            stopSlots(targets, "倒车停止");
        }
    }

    private void ensureBleReady() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            permissions = new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
        }

        List<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        enableBluetoothIfNeeded();
    }

    @SuppressLint("MissingPermission")
    private void enableBluetoothIfNeeded() {
        BluetoothAdapter adapter = bleManager.getAdapter();
        if (adapter == null) {
            adapterState.setText(R.string.bluetooth_unsupported);
            return;
        }
        if (!adapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
            return;
        }
        adapterState.setText(R.string.bluetooth_ready);
        adapterState.setTextColor(getColor(R.color.success));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        boolean granted = true;
        for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            enableBluetoothIfNeeded();
        } else {
            adapterState.setText(R.string.permission_required);
            new AlertDialog.Builder(this)
                    .setTitle("需要蓝牙权限")
                    .setMessage("扫描并连接小车需要附近设备权限。若已永久拒绝，请到应用设置中手动允许。")
                    .setPositiveButton("应用设置", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            enableBluetoothIfNeeded();
        }
    }

    private void handleSlotAction(String slot) {
        BleCarManager.SlotSnapshot snapshot = slots.get(slot);
        if (snapshot == null) {
            showScanner(slot);
            return;
        }
        if (!snapshot.connected && !snapshot.connecting) {
            if (!bleManager.reconnect(slot)) showScanner(slot);
            return;
        }
        if (snapshot.connecting) return;
        new AlertDialog.Builder(this)
                .setTitle("断开 " + CarProtocol.slotDisplayName(slot))
                .setMessage("断开前会先发送停止指令。")
                .setPositiveButton("断开", (dialog, which) -> {
                    moving.put(slot, false);
                    gears.put(slot, 0);
                    // 该车退出倒车连发，避免断开前的 80ms 窗口里又被补发 REVERSE。
                    reverseTargets.remove(slot);
                    if (reverseTargets.isEmpty()) {
                        stopReverse(false);
                    } else {
                        restartReverseTimer();
                    }
                    bleManager.sendUrgent(slot, CarProtocol.HOLD_AND_STOP, "断开前停止");
                    handler.postDelayed(() -> bleManager.disconnect(slot, true), 80);
                    updateUi();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showScanner(String slot) {
        ensureBleReady();
        BluetoothAdapter adapter = bleManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        activeScanSlot = slot;
        deviceAdapter = new DeviceAdapter();
        ListView list = new ListView(this);
        list.setDividerHeight(1);
        list.setAdapter(deviceAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            BleCarManager.DeviceItem device = deviceAdapter.items.get(position);
            if (bleManager.connect(activeScanSlot, device) && scanDialog != null) {
                scanDialog.dismiss();
            }
        });

        TextView hint = new TextView(this);
        hint.setText("仅显示广播中声明 FFF0 服务的 BLE 小车。\nAndroid 6–11 还需开启系统定位服务。") ;
        hint.setTextColor(getColor(R.color.text_muted));
        hint.setTextSize(13);
        hint.setPadding(dp(20), dp(8), dp(20), dp(12));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(hint);
        content.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));

        scanDialog = new AlertDialog.Builder(this)
                .setTitle("连接 " + CarProtocol.slotDisplayName(slot))
                .setView(content)
                .setNeutralButton("重新扫描", null)
                .setNegativeButton("关闭", (dialog, which) -> bleManager.stopScan())
                .create();
        scanDialog.setOnShowListener(dialog -> {
            scanDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                deviceAdapter.items.clear();
                deviceAdapter.notifyDataSetChanged();
                bleManager.stopScan();
                bleManager.startScan();
            });
        });
        scanDialog.setOnDismissListener(dialog -> bleManager.stopScan());
        scanDialog.show();
        bleManager.startScan();
    }

    private void accelerate() {
        List<String> selected = selectedReadySlots(true);
        if (selected.isEmpty()) return;
        send(selected, CarProtocol.ACCELERATE, "升档");
        for (String slot : selected) {
            gears.put(slot, Math.min(10, gears.get(slot) + 1));
            moving.put(slot, true);
        }
        restartHoldTimer();
        updateUi();
        vibrate(25);
    }

    private void stopSelected() {
        List<String> selected = selectedReadySlots(true);
        if (selected.isEmpty()) return;
        stopSlots(selected, "停止");
    }

    private void pushMode() {
        List<String> selected = selectedReadySlots(true);
        if (selected.isEmpty()) return;
        stopReverse(false);
        stopTurn();
        resetMotion(selected);
        send(selected, CarProtocol.PUSH, "推行模式");
        vibrate(30);
    }

    private void emergencyStop() {
        List<String> connected = bleManager.connectedSlots();
        if (connected.isEmpty()) return;
        stopSlots(connected, "全部急停");
        vibrate(180);
    }

    private void stopSlots(List<String> selected, String label) {
        // 任何停止路径都必须先掐掉倒车连发，否则急停之后倒车循环仍会继续下发 REVERSE。
        stopReverse(false);
        stopTurn();
        resetMotion(selected);
        sendUrgent(selected, CarProtocol.HOLD_AND_STOP, label);
        vibrate(60);
    }

    private void resetMotion(List<String> selected) {
        for (String slot : selected) {
            gears.put(slot, 0);
            moving.put(slot, false);
        }
        handler.removeCallbacks(holdRunnable);
        if (moving.values().contains(true)) handler.postDelayed(holdRunnable, 200);
        updateUi();
    }

    private void restartHoldTimer() {
        handler.removeCallbacks(holdRunnable);
        handler.postDelayed(holdRunnable, 200);
    }

    private void restartReverseTimer() {
        handler.removeCallbacks(reverseRunnable);
        handler.postDelayed(reverseRunnable, 200);
    }

    private void stopTurn() {
        turning = false;
        turnPacket = null;
        turnLabel = null;
        handler.removeCallbacks(turnRunnable);
    }

    private void send(List<String> selected, byte[] packet, String label) {
        for (String slot : selected) bleManager.send(slot, packet, label);
    }

    // 停止类指令走专用通道：清空积压的普通指令并抢占写队列，避免急停被排在队尾。
    private void sendUrgent(List<String> selected, byte[] packet, String label) {
        for (String slot : selected) bleManager.sendUrgent(slot, packet, label);
    }

    private List<String> selectedReadySlots(boolean warn) {
        List<String> candidates = "BOTH".equals(target) ? SLOT_IDS : Arrays.asList(target);
        List<String> ready = new ArrayList<>();
        for (String slot : candidates) if (isReady(slot)) ready.add(slot);
        if (ready.isEmpty() && warn) {
            Toast.makeText(this, "当前控制目标没有在线车轮", Toast.LENGTH_SHORT).show();
        }
        return ready;
    }

    private boolean isReady(String slot) {
        BleCarManager.SlotSnapshot snapshot = slots.get(slot);
        return snapshot != null && snapshot.connected;
    }

    @Override
    public void onDevicesChanged(List<BleCarManager.DeviceItem> devices) {
        if (deviceAdapter == null) return;
        deviceAdapter.items.clear();
        deviceAdapter.items.addAll(devices);
        deviceAdapter.notifyDataSetChanged();
    }

    @Override
    public void onSlotChanged(String slot, BleCarManager.SlotSnapshot snapshot) {
        if (snapshot == null) slots.remove(slot);
        else slots.put(slot, snapshot);
        if (snapshot == null || !snapshot.connected) {
            moving.put(slot, false);
            gears.put(slot, 0);
        }
        updateUi();
    }

    @Override
    public void onLog(String level, String message, String details) {
        String line = BleCarManager.now() + "  " + message;
        if (details != null && !details.isEmpty()) line += "\n    " + details;
        logLines.addFirst(line);
        while (logLines.size() > 6) logLines.removeLast();
        renderLogs();
    }

    private void renderLogs() {
        if (logLines.isEmpty()) {
            logText.setText(R.string.log_empty);
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (String line : logLines) {
            if (builder.length() > 0) builder.append("\n\n");
            builder.append(line);
        }
        logText.setText(builder);
    }

    private void updateUi() {
        renderSlot("A", slotAName, slotAStatus, slotAGear, slotAMotion, slotAAction, slotACard);
        renderSlot("B", slotBName, slotBStatus, slotBGear, slotBMotion, slotBAction, slotBCard);
        int count = (isReady("A") ? 1 : 0) + (isReady("B") ? 1 : 0);
        onlineCount.setText(count + "/2\n在线");
        onlineCount.setTextColor(count > 0 ? getColor(R.color.success) : getColor(R.color.text));
        if (previousOnlineCount >= 0 && previousOnlineCount != count) {
            onlineCount.setScaleX(0.84f);
            onlineCount.setScaleY(0.84f);
            onlineCount.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
        }
        previousOnlineCount = count;
        emergencyButton.setEnabled(count > 0);
        updateEmergencyPulse(count > 0);
    }

    private void renderSlot(String slot, TextView name, TextView status, TextView gear,
                            TextView motion, Button action, MaterialCardView card) {
        BleCarManager.SlotSnapshot snapshot = slots.get(slot);
        name.setText(snapshot == null ? CarProtocol.slotDisplayName(slot) : snapshot.name);
        gear.setText(String.valueOf(gears.get(slot)));
        motion.setText(Boolean.TRUE.equals(moving.get(slot)) ? "保持" : "静止");
        if (snapshot == null) {
            status.setText("●  未连接");
            animateTextColor(status, getColor(R.color.text_muted));
            action.setText("选择设备");
            applyCardState(card, false, false);
        } else if (snapshot.connecting) {
            status.setText("●  连接中");
            animateTextColor(status, getColor(R.color.accent));
            action.setText("处理中");
            applyCardState(card, false, true);
        } else if (snapshot.connected) {
            status.setText("●  已连接");
            animateTextColor(status, getColor(R.color.success));
            action.setText("断开");
            applyCardState(card, true, false);
        } else {
            status.setText("●  连接断开");
            animateTextColor(status, getColor(R.color.danger));
            action.setText("重新连接");
            applyCardState(card, false, false);
        }
    }

    private void applyCardState(MaterialCardView card, boolean connected, boolean connecting) {
        if (connected) {
            card.setStrokeColor(getColor(R.color.success));
            card.setStrokeWidth(dp(2));
            card.setCardBackgroundColor(getColor(R.color.success_soft));
        } else if (connecting) {
            card.setStrokeColor(getColor(R.color.accent));
            card.setStrokeWidth(dp(2));
            card.setCardBackgroundColor(getColor(R.color.surface_blue));
        } else {
            card.setStrokeColor(getColor(R.color.border));
            card.setStrokeWidth(dp(1));
            card.setCardBackgroundColor(getColor(R.color.surface));
        }
    }

    private void animateTextColor(TextView view, int targetColor) {
        Object previousTarget = view.getTag();
        if (previousTarget instanceof Integer && (Integer) previousTarget == targetColor) return;
        view.setTag(targetColor);
        ValueAnimator animator = ValueAnimator.ofObject(
                new ArgbEvaluator(), view.getCurrentTextColor(), targetColor);
        animator.setDuration(220);
        animator.addUpdateListener(value -> view.setTextColor((Integer) value.getAnimatedValue()));
        animator.start();
    }

    private void updateEmergencyPulse(boolean enabled) {
        if (!enabled) {
            emergencyButton.clearAnimation();
            emergencyButton.setAlpha(1f);
            emergencyPulseRunning = false;
            return;
        }
        if (emergencyPulseRunning) return;
        AlphaAnimation pulse = new AlphaAnimation(1f, 0.82f);
        pulse.setDuration(900);
        pulse.setRepeatMode(Animation.REVERSE);
        pulse.setRepeatCount(Animation.INFINITE);
        emergencyButton.startAnimation(pulse);
        emergencyPulseRunning = true;
    }

    private void runEntranceAnimation() {
        View root = findViewById(R.id.dashboard_root);
        root.setAlpha(0f);
        root.setTranslationY(dp(8));
        root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(360)
                .start();
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long milliseconds) {
        try {
            Vibrator vibrator = getSystemService(Vibrator.class);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(milliseconds);
            }
        } catch (RuntimeException ignored) {
            // Haptic feedback is optional and must never interrupt vehicle control.
        }
    }

    @Override
    protected void onStop() {
        stopReverse(false);
        stopTurn();
        handler.removeCallbacks(holdRunnable);
        bleManager.safeStopAll();
        for (String slot : SLOT_IDS) {
            moving.put(slot, false);
            gears.put(slot, 0);
        }
        updateUi();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (scanDialog != null) scanDialog.dismiss();
        bleManager.closeAll();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class DeviceAdapter extends BaseAdapter {
        final List<BleCarManager.DeviceItem> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = convertView instanceof LinearLayout
                    ? (LinearLayout) convertView : new LinearLayout(MainActivity.this);
            row.removeAllViews();
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(14), dp(20), dp(14));
            TextView identity = new TextView(MainActivity.this);
            BleCarManager.DeviceItem item = items.get(position);
            identity.setText(item.name + "\n" + item.address);
            identity.setTextColor(getColor(R.color.text));
            identity.setTextSize(15);
            identity.setMaxLines(2);
            row.addView(identity, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView signal = new TextView(MainActivity.this);
            signal.setText(signalLabel(item.rssi) + "\n" + item.rssi + " dBm");
            signal.setTextColor(getColor(R.color.text_muted));
            signal.setTextSize(12);
            signal.setGravity(Gravity.END);
            row.addView(signal);
            return row;
        }
    }

    private static String signalLabel(int rssi) {
        if (rssi >= -60) return "信号强";
        if (rssi >= -76) return "信号中";
        return "信号弱";
    }
}
