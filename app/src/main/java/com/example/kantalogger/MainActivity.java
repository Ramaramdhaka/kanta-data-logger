package com.example.kantalogger;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Diagnostic logger. Does not pair secretly, sniff other connections, or send data automatically. */
public class MainActivity extends Activity {
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int PERMISSION_REQUEST = 25;
    private static final int EXPORT_REQUEST = 26;
    private static final int MAX_LOG_CHARS = 600000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object logLock = new Object();
    private final StringBuilder captured = new StringBuilder();
    private boolean redrawQueued = false;
    private volatile BluetoothSocket classicSocket;
    private volatile BluetoothServerSocket classicServer;
    private volatile boolean readingClassic;
    private BluetoothGatt bleGatt;
    private BluetoothLeScanner bleScanner;
    private boolean bleScanning;
    private final ArrayDeque<BluetoothGattDescriptor> cccdQueue = new ArrayDeque<>();
    private boolean cccdWriting;
    private final ArrayList<BluetoothDevice> pairedDevices = new ArrayList<>();
    private final ArrayList<BluetoothDevice> bleDevices = new ArrayList<>();
    private final Map<String, BluetoothDevice> bleFound = new HashMap<>();
    private final ArrayList<BluetoothGattCharacteristic> gattCharacteristics = new ArrayList<>();

    private BluetoothAdapter bt;
    private Spinner classicPicker, blePicker, charPicker;
    private TextView status, counts, preview;
    private EditText command;
    private String currentDevice = "not selected";
    private long lastPacketNanos;
    private long packetCount;
    private long byteCount;

    private boolean bluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bt = manager == null ? null : manager.getAdapter();
        buildUi();
        event("Kanta Data Logger 1.0 | Bluetooth Classic SPP + BLE scan/GATT");
        event("Weight test: 0 kg, 5 kg, 20 kg; compare ASCII and HEX logs");
        if (bt == null) { setStatus("Bluetooth hardware not available"); return; }
        if (!bluetoothPermission()) {
            if (Build.VERSION.SDK_INT >= 31) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, PERMISSION_REQUEST);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST);
            }
        } else refreshPaired();
    }

    private TextView label(String value, int size) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(size);
        v.setPadding(0, dp(7), 0, dp(5));
        return v;
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private Button button(String text, Runnable callback) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false);
        b.setOnClickListener(v -> callback.run()); return b;
    }
    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r;
    }
    private void addHalf(LinearLayout row, Button b) {
        row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }
    private ArrayAdapter<String> adapterOf(ArrayList<String> items) {
        return new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(24));
        scroll.addView(root); setContentView(scroll);
        TextView heading = label("Kanta Data Logger", 24); root.addView(heading);
        root.addView(label("Connect your real scale. Record raw bytes, ASCII, HEX, timing. This is a receiver, not the sender app.", 13));

        root.addView(label("1. Bluetooth Classic (SPP) — pair scale in phone Settings first", 16));
        classicPicker = new Spinner(this); root.addView(classicPicker);
        LinearLayout classics = row();
        addHalf(classics, button("Refresh paired", this::refreshPaired));
        addHalf(classics, button("Connect SPP", this::connectClassic));
        root.addView(classics);
        root.addView(button("Wait for incoming SPP connection (advanced)", this::listenClassic));

        root.addView(label("2. Bluetooth Low Energy (BLE)", 16));
        LinearLayout bleButtons = row();
        addHalf(bleButtons, button("Scan BLE (12 sec)", this::scanBle));
        addHalf(bleButtons, button("Connect selected BLE", this::connectBle));
        root.addView(bleButtons);
        blePicker = new Spinner(this); root.addView(blePicker);
        root.addView(label("BLE characteristic (read/write if supported)", 13));
        charPicker = new Spinner(this); root.addView(charPicker);
        root.addView(button("Read selected BLE characteristic", this::readCharacteristic));

        root.addView(label("3. Optional command (only if scale requires it)", 16));
        command = new EditText(this); command.setSingleLine(true);
        command.setInputType(InputType.TYPE_CLASS_TEXT);
        command.setHint("ASCII e.g. P\\r\\n (nothing sent automatically)"); root.addView(command);
        root.addView(button("Send typed ASCII command to connected device", this::sendCommand));

        LinearLayout tools = row();
        addHalf(tools, button("Disconnect", this::disconnect));
        addHalf(tools, button("Clear log", this::clearLog));
        addHalf(tools, button("Export TXT", this::exportLog));
        root.addView(tools);

        status = label("Not connected", 15); root.addView(status);
        counts = label("RX chunks: 0 | Bytes: 0", 14); root.addView(counts);
        root.addView(label("4. Received data (timestamp | Δms | ASCII | HEX)", 16));
        preview = label("Waiting for data…", 12);
        preview.setTextIsSelectable(true);
        preview.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(preview);
        root.addView(label("Notes: HEX is exact received bytes. One RX chunk may contain part of a packet or multiple packets. A connected BLE device may need a proprietary command before it sends weight.", 12));
        setPicker(classicPicker, "No paired devices — use Settings to pair");
        setPicker(blePicker, "Tap Scan BLE");
        setPicker(charPicker, "Connect BLE to discover services");
    }
    private void setPicker(Spinner spinner, String text) {
        ArrayList<String> single = new ArrayList<>(); single.add(text);
        spinner.setAdapter(adapterOf(single));
    }
    private void setStatus(String text) {
        ui.post(() -> { if (status != null) status.setText(text); });
        event("STATUS: " + text);
    }
    private void event(String detail) { append("INFO | " + detail + "\n"); }
    private void append(String text) {
        synchronized (logLock) {
            captured.append(timestamp()).append(" | ").append(text);
            if (captured.length() > MAX_LOG_CHARS) {
                int cut = captured.indexOf("\n", 140000);
                if (cut < 0) cut = 140000;
                captured.delete(0, cut + 1);
                captured.insert(0, "[Oldest entries removed to limit memory. Export often.]\n");
            }
            if (!redrawQueued) {
                redrawQueued = true;
                ui.postDelayed(() -> {
                    String recent;
                    long pc, bc;
                    synchronized (logLock) {
                        int from = Math.max(0, captured.length() - 24000);
                        recent = captured.substring(from);
                        pc = packetCount; bc = byteCount;
                        redrawQueued = false;
                    }
                    preview.setText(recent);
                    counts.setText("RX chunks: " + pc + " | Bytes: " + bc);
                }, 160);
            }
        }
    }
    private String timestamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }
    private static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.US, "%02X ", b & 0xff));
        return out.toString().trim();
    }
    private static String ascii(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            int v = b & 255;
            if (v == 13) out.append("\\r");
            else if (v == 10) out.append("\\n");
            else if (v == 9) out.append("\\t");
            else if (v >= 32 && v <= 126) out.append((char)v);
            else out.append(String.format(Locale.US, "\\x%02X", v));
        }
        return out.toString();
    }
    private void received(String channel, byte[] data) {
        if (data == null) return;
        long now = SystemClock.elapsedRealtimeNanos();
        String delta;
        synchronized (logLock) {
            delta = lastPacketNanos == 0 ? "—" : String.format(Locale.US, "%.3f", (now-lastPacketNanos)/1000000.0);
            lastPacketNanos = now;
            packetCount++;
            byteCount += data.length;
        }
        append("RX " + channel + " | dt=" + delta + "ms | " + data.length
            + " bytes | ASCII=" + ascii(data) + " | HEX=" + hex(data) + "\n");
    }
    private void refreshPaired() {
        if (!bluetoothPermission()) { setStatus("Allow Nearby devices permission first"); return; }
        if (bt == null || !bt.isEnabled()) { setStatus("Turn Bluetooth ON first"); return; }
        Set<BluetoothDevice> bonded;
        try { bonded = bt.getBondedDevices(); }
        catch (SecurityException e) { setStatus("Missing Bluetooth permission"); return; }
        pairedDevices.clear();
        ArrayList<String> names = new ArrayList<>();
        for (BluetoothDevice d : bonded) {
            pairedDevices.add(d); names.add(safeName(d) + " (" + d.getAddress() + ")");
        }
        if (names.isEmpty()) names.add("No paired devices — pair scale in Settings");
        classicPicker.setAdapter(adapterOf(names));
        event("Paired Classic devices: " + pairedDevices.size());
    }
    private String safeName(BluetoothDevice device) {
        try { String n = device.getName(); return n == null ? "Unknown" : n; }
        catch (SecurityException e) { return "Unknown"; }
    }
    private void connectClassic() {
        if (!bluetoothPermission()) { setStatus("Allow Bluetooth permission"); return; }
        int pos = classicPicker.getSelectedItemPosition();
        if (pos < 0 || pos >= pairedDevices.size()) { setStatus("Pair the scale first"); return; }
        BluetoothDevice device = pairedDevices.get(pos);
        disconnect();
        currentDevice = safeName(device) + " " + device.getAddress();
        setStatus("Connecting Classic SPP to " + currentDevice + " ...");
        new Thread(() -> {
            BluetoothSocket attempt = null;
            try {
                attempt = device.createRfcommSocketToServiceRecord(SPP_UUID);
                attempt.connect();
                classicSocket = attempt;
                setStatus("Connected Classic SPP: " + currentDevice);
                readClassic(attempt);
            } catch (Exception e) {
                setStatus("Classic SPP failed: " + e.getClass().getSimpleName() + " " + e.getMessage()
                    + " — scale may use BLE or a different service");
            } finally {
                if (classicSocket == attempt) classicSocket = null;
                try { if (attempt != null) attempt.close(); } catch (Exception ignored) {}
            }
        }, "kanta-classic-client").start();
    }
    private void readClassic(BluetoothSocket connected) throws Exception {
        readingClassic = true;
        InputStream in = connected.getInputStream();
        byte[] buffer = new byte[4096];
        while (readingClassic) {
            int n = in.read(buffer);
            if (n < 0) break;
            if (n == 0) continue;
            byte[] exact = new byte[n]; System.arraycopy(buffer, 0, exact, 0, n);
            received("CLASSIC/SPP", exact);
        }
        readingClassic = false;
        event("Classic stream closed");
    }
    private void listenClassic() {
        if (!bluetoothPermission()) { setStatus("Allow Bluetooth permission"); return; }
        disconnect();
        setStatus("Waiting for incoming Classic SPP... device must initiate connection");
        new Thread(() -> {
            try {
                BluetoothServerSocket server = bt.listenUsingRfcommWithServiceRecord("Kanta Data Logger", SPP_UUID);
                classicServer = server;
                BluetoothSocket connected = server.accept();
                classicSocket = connected;
                currentDevice = safeName(connected.getRemoteDevice());
                setStatus("Incoming Classic SPP: " + currentDevice);
                readClassic(connected);
            } catch (Exception e) { event("SPP listener stopped: " + e.getMessage()); }
            finally {
                try { if (classicSocket != null) classicSocket.close(); } catch (Exception ignored) {}
                try { if (classicServer != null) classicServer.close(); } catch (Exception ignored) {}
                classicSocket = null; classicServer = null;
            }
        }, "kanta-classic-server").start();
    }
    private final ScanCallback scannerCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            BluetoothDevice device = result.getDevice();
            String id = device.getAddress();
            byte[] adv = result.getScanRecord() == null ? null : result.getScanRecord().getBytes();
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null || name.isEmpty()) name = safeName(device);
            final String shownName = name;
            ui.post(() -> {
                if (!bleFound.containsKey(id)) {
                    bleFound.put(id, device); bleDevices.add(device);
                    int chosen = blePicker.getSelectedItemPosition();
                    ArrayList<String> names = new ArrayList<>();
                    for (BluetoothDevice d : bleDevices) {
                        String nm = safeName(d);
                        names.add(nm + " (" + d.getAddress() + ")");
                    }
                    blePicker.setAdapter(adapterOf(names));
                    if (chosen >= 0 && chosen < names.size()) blePicker.setSelection(chosen);
                }
            });
            if (adv != null) received("BLE/ADV " + shownName + " " + id + " RSSI=" + result.getRssi(), adv);
        }
        @Override public void onScanFailed(int errorCode) { setStatus("BLE scan failed code " + errorCode); }
    };
    private void scanBle() {
        if (!bluetoothPermission()) { setStatus("Allow Nearby devices permission"); return; }
        if (bt == null || !bt.isEnabled()) { setStatus("Turn Bluetooth ON"); return; }
        stopBleScan();
        bleFound.clear(); bleDevices.clear(); setPicker(blePicker, "Scanning...");
        bleScanner = bt.getBluetoothLeScanner();
        if (bleScanner == null) { setStatus("BLE scanner unavailable"); return; }
        try {
            ScanSettings options = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            bleScanner.startScan(null, options, scannerCallback);
            bleScanning = true;
            setStatus("Scanning BLE for 12 seconds (advertisement bytes logged)");
            ui.postDelayed(this::stopBleScan, 12000);
        } catch (Exception e) { setStatus("BLE scan error: " + e.getMessage()); }
    }
    private void stopBleScan() {
        if (bleScanning && bleScanner != null) {
            try { bleScanner.stopScan(scannerCallback); } catch (Exception ignored) {}
        }
        bleScanning = false;
    }
    private void connectBle() {
        if (!bluetoothPermission()) { setStatus("Allow Bluetooth permission"); return; }
        int pos = blePicker.getSelectedItemPosition();
        if (pos < 0 || pos >= bleDevices.size()) { setStatus("Scan and select BLE scale first"); return; }
        BluetoothDevice selected = bleDevices.get(pos);
        disconnect(); currentDevice = safeName(selected) + " " + selected.getAddress();
        setStatus("Connecting BLE GATT: " + currentDevice);
        try { bleGatt = selected.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE); }
        catch (Exception e) { setStatus("BLE connect error: " + e.getMessage()); }
    }
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                setStatus("BLE connected; discovering services");
                try { g.discoverServices(); } catch (SecurityException e) { event("Service discovery denied"); }
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                setStatus("BLE disconnected (status=" + statusCode + ")");
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) { setStatus("BLE services error=" + statusCode); return; }
            ArrayList<String> names = new ArrayList<>();
            gattCharacteristics.clear();
            synchronized (cccdQueue) { cccdQueue.clear(); cccdWriting = false; }
            for (BluetoothGattService service : g.getServices()) {
                event("BLE SERVICE " + service.getUuid());
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    gattCharacteristics.add(c);
                    int p = c.getProperties();
                    String flags = flags(p);
                    names.add(c.getUuid() + " [" + flags + "]");
                    event("BLE CHAR " + c.getUuid() + " properties=" + flags);
                    boolean notify = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                    boolean indicate = (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                    if (notify || indicate) {
                        BluetoothGattDescriptor cccd = c.getDescriptor(CCCD_UUID);
                        if (cccd != null) {
                            synchronized (cccdQueue) { cccdQueue.add(cccd); }
                        }
                    }
                }
            }
            ui.post(() -> {
                if (names.isEmpty()) setPicker(charPicker, "No characteristics found");
                else charPicker.setAdapter(adapterOf(names));
            });
            event("Auto-subscribing to supported BLE notifications/indications");
            pumpCccd(g);
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int statusCode) {
            event("BLE CCCD " + descriptor.getCharacteristic().getUuid() + " status=" + statusCode);
            synchronized (cccdQueue) { cccdWriting = false; }
            pumpCccd(g);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            received("BLE/NOTIFY " + c.getUuid(), c.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            received("BLE/NOTIFY " + c.getUuid(), value);
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int code) {
            if (code == BluetoothGatt.GATT_SUCCESS) received("BLE/READ " + c.getUuid(), c.getValue());
            else event("BLE read failed status=" + code);
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] bytes, int code) {
            if (code == BluetoothGatt.GATT_SUCCESS) received("BLE/READ " + c.getUuid(), bytes);
            else event("BLE read failed status=" + code);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int code) {
            event("BLE write " + c.getUuid() + " status=" + code);
        }
    };
    private static String flags(int props) {
        ArrayList<String> words = new ArrayList<>();
        if ((props & BluetoothGattCharacteristic.PROPERTY_READ) != 0) words.add("READ");
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) words.add("WRITE");
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) words.add("WRITE_NR");
        if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) words.add("NOTIFY");
        if ((props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) words.add("INDICATE");
        return android.text.TextUtils.join(",", words);
    }
    private void pumpCccd(BluetoothGatt g) {
        synchronized (cccdQueue) {
            if (cccdWriting) return;
            while (!cccdQueue.isEmpty()) {
                BluetoothGattDescriptor desc = cccdQueue.poll();
                BluetoothGattCharacteristic c = desc.getCharacteristic();
                boolean isNotify = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                try {
                    if (!g.setCharacteristicNotification(c, true)) continue;
                    desc.setValue(isNotify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    if (g.writeDescriptor(desc)) { cccdWriting = true; return; }
                } catch (Exception e) { event("Cannot enable " + c.getUuid() + ": " + e.getMessage()); }
            }
        }
    }
    private BluetoothGattCharacteristic chosenChar() {
        int pos = charPicker.getSelectedItemPosition();
        return pos >= 0 && pos < gattCharacteristics.size() ? gattCharacteristics.get(pos) : null;
    }
    private void readCharacteristic() {
        if (bleGatt == null) { setStatus("Connect BLE first"); return; }
        BluetoothGattCharacteristic c = chosenChar();
        if (c == null || (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            setStatus("Select a BLE characteristic with READ"); return;
        }
        try { event("BLE read request " + c.getUuid()); bleGatt.readCharacteristic(c); }
        catch (Exception e) { setStatus("BLE read error " + e.getMessage()); }
    }
    private void sendCommand() {
        String entered = command.getText().toString();
        if (entered.isEmpty()) { Toast.makeText(this, "Enter a command first", Toast.LENGTH_SHORT).show(); return; }
        // Convert literal escape sequences to control bytes when requested by the user.
        String decoded = entered.replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t");
        byte[] bytes = decoded.getBytes(StandardCharsets.UTF_8);
        BluetoothSocket current = classicSocket;
        if (current != null && current.isConnected()) {
            new Thread(() -> {
                try {
                    OutputStream out = current.getOutputStream(); out.write(bytes); out.flush();
                    event("TX CLASSIC | ASCII=" + ascii(bytes) + " | HEX=" + hex(bytes));
                } catch (Exception e) { setStatus("SPP TX failed " + e.getMessage()); }
            }).start();
            return;
        }
        BluetoothGattCharacteristic c = chosenChar();
        if (bleGatt != null && c != null &&
            (c.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
            try {
                int type = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                c.setWriteType(type); c.setValue(bytes);
                if (bleGatt.writeCharacteristic(c)) event("TX BLE " + c.getUuid() + " | HEX=" + hex(bytes));
                else setStatus("BLE write request rejected");
            } catch (Exception e) { setStatus("BLE TX failed " + e.getMessage()); }
        } else setStatus("Connect SPP, or choose a writable BLE characteristic");
    }
    private void disconnect() {
        stopBleScan(); readingClassic = false;
        try { if (classicServer != null) classicServer.close(); } catch (Exception ignored) {}
        try { if (classicSocket != null) classicSocket.close(); } catch (Exception ignored) {}
        try { if (bleGatt != null) { bleGatt.disconnect(); bleGatt.close(); } } catch (Exception ignored) {}
        classicSocket = null; classicServer = null; bleGatt = null;
        synchronized (cccdQueue) { cccdQueue.clear(); cccdWriting = false; }
        setStatus("Disconnected");
    }
    private void clearLog() {
        synchronized (logLock) {
            captured.setLength(0); packetCount = 0; byteCount = 0; lastPacketNanos = 0;
        }
        event("New capture started | device=" + currentDevice);
    }
    private void exportLog() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/plain"); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "kanta_capture_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt");
        startActivityForResult(intent, EXPORT_REQUEST);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            String dump;
            synchronized (logLock) { dump = captured.toString(); }
            String text = "Kanta Data Logger 1.0\nDevice: " + currentDevice + "\nSaved: "
                + new Date() + "\n--- Captured events ---\n" + dump;
            new Thread(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new Exception("Cannot open destination");
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    ui.post(() -> Toast.makeText(this, "Capture saved. Share this TXT for analysis.", Toast.LENGTH_LONG).show());
                } catch (Exception e) { setStatus("Export failed: " + e.getMessage()); }
            }, "kanta-save-log").start();
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_REQUEST) {
            if (bluetoothPermission()) refreshPaired();
            else setStatus("Please allow Nearby devices (and Location on older Android)");
        }
    }
    @Override protected void onDestroy() { disconnect(); super.onDestroy(); }
}
