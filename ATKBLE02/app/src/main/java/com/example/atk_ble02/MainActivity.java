package com.example.atk_ble02;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSION_CODE = 1;
    private static final String TARGET_DEVICE_NAME = "ATK-BLE02";

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private TextView statusTextView;
    private TextView receivedDataTextView;
    private Button scanButton, connectButton, sendButton;
    private EditText atCommandEditText;
    private Button sendAtCommandButton;

    private Handler handler = new Handler(Looper.getMainLooper());
    private static final long SCAN_PERIOD = 10000;
    private ScanCallback leScanCallback;
    private boolean isScanning = false;
    private BluetoothDevice connectedDevice;
    private volatile boolean isSafeToSend = false; // Ensure thread safety for this flag
    private boolean mHasAttemptedInitialCommand = false;

    private static final UUID SERVICE_UUID = UUID.fromString("9ECADC24-0EE5-A9E0-93F3-A3B50100406E");
    private static final UUID CHARACTERISTIC_APP_WRITES_TO_MODULE = UUID.fromString("9ECADC24-0EE5-A9E0-93F3-A3B50200406E");
    private static final UUID CHARACTERISTIC_APP_RECEIVES_FROM_MODULE_NOTIFY = UUID.fromString("9ECADC24-0EE5-A9E0-93F3-A3B50300406E");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTextView = findViewById(R.id.statusTextView);
        receivedDataTextView = findViewById(R.id.receivedDataTextView);
        scanButton = findViewById(R.id.scanButton);
        connectButton = findViewById(R.id.connectButton);
        sendButton = findViewById(R.id.sendButton);
        atCommandEditText = findViewById(R.id.atCommandEditText);
        sendAtCommandButton = findViewById(R.id.sendAtCommandButton);

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "设备不支持BLE", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "此设备不支持蓝牙", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        requestBlePermissions();

        scanButton.setOnClickListener(v -> {
            if (!isScanning) {
                startBleScan();
            } else {
                stopBleScan();
            }
        });
        connectButton.setOnClickListener(v -> {
            if (!isScanning) {
                startBleScan();
            } else {
                Toast.makeText(MainActivity.this, "请先停止扫描", Toast.LENGTH_SHORT).show();
            }
        });

        sendButton.setOnClickListener(v -> {
            if (isSafeToSend) {
                Log.d(TAG, "发送测试用户数据...");
                sendDataToModule("Test Data " + System.currentTimeMillis() % 1000);
            } else {
                Toast.makeText(MainActivity.this, "设备尚未准备好发送数据 (isSafeToSend=false)", Toast.LENGTH_SHORT).show();
                Log.w(TAG, "Attempted to send test data but not safe to send yet.");
            }
        });

        sendAtCommandButton.setOnClickListener(v -> {
            if (isSafeToSend) {
                String atCommand = atCommandEditText.getText().toString();
                if (atCommand.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请输入AT指令", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!atCommand.endsWith("\r\n")) {
                    atCommand += "\r\n";
                }
                Log.d(TAG, "尝试发送自定义AT指令: " + atCommand.replace("\r\n", "\\r\\n"));
                sendDataToModule(atCommand);
            } else {
                Toast.makeText(MainActivity.this, "设备尚未准备好发送数据 (isSafeToSend=false)", Toast.LENGTH_SHORT).show();
                Log.w(TAG, "Attempted to send AT command but not safe to send yet.");
            }
        });

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bondStateReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bondStateReceiver, filter);
        }
    }

    private void requestBlePermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN);
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQUEST_PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_CODE) {
            boolean allGranted = true;
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.w(TAG, "权限被拒绝: " + permissions[i]);
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(this, "所有必须权限已授予", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "所有必须权限已授予");
            } else {
                Toast.makeText(this, "部分权限被拒绝，功能可能受限", Toast.LENGTH_LONG).show();
                Log.e(TAG, "部分或所有权限被拒绝");
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startBleScan() {
        if (!hasRequiredPermissions()) { Toast.makeText(this, "缺少必要的蓝牙权限", Toast.LENGTH_SHORT).show(); requestBlePermissions(); return; }
        if (!bluetoothAdapter.isEnabled()) { Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show(); return; }
        if (isScanning) { Log.d(TAG, "已经在扫描了"); return; }

        isSafeToSend = false;
        mHasAttemptedInitialCommand = false;

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) { Toast.makeText(this, "无法获取BLE扫描器", Toast.LENGTH_SHORT).show(); return; }

        leScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                BluetoothDevice device = result.getDevice();
                @SuppressLint("MissingPermission") String deviceName = device.getName();
                if (deviceName != null && deviceName.equalsIgnoreCase(TARGET_DEVICE_NAME)) {
                    statusTextView.setText("发现设备: " + deviceName + " (" + device.getAddress() + ")");
                    Log.d(TAG, "发现目标设备: " + deviceName + " (" + device.getAddress() + ")");
                    stopBleScan();
                    connectToDevice(device);
                }
            }
            @Override
            public void onScanFailed(int errorCode) {
                super.onScanFailed(errorCode);
                statusTextView.setText("扫描失败: " + errorCode);
                Log.e(TAG, "扫描失败，错误码: " + errorCode);
                isScanning = false;
                scanButton.setText("扫描/停止扫描");
            }
        };
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        Log.d(TAG, "BLE扫描开始 (API Level " + Build.VERSION.SDK_INT + ")");
        bluetoothLeScanner.startScan(null, settings, leScanCallback);
        isScanning = true;
        scanButton.setText("停止扫描");
        statusTextView.setText("扫描中...");
        handler.postDelayed(() -> {
            if(isScanning) {
                Log.d(TAG, "扫描超时，停止扫描");
                stopBleScan();
            }
        }, SCAN_PERIOD);
    }

    @SuppressLint("MissingPermission")
    private void stopBleScan() {
        if (!hasRequiredPermissionsForScanStop()) { Log.w(TAG, "停止扫描权限不足(stopBleScan)"); return; }

        if (isScanning && bluetoothLeScanner != null && leScanCallback != null) {
            try {
                bluetoothLeScanner.stopScan(leScanCallback);
                Log.d(TAG, "BLE扫描已请求停止");
            } catch (IllegalStateException e) {
                Log.e(TAG, "停止扫描时出错: " + e.getMessage());
            }
        }
        leScanCallback = null;
        isScanning = false;
        scanButton.setText("扫描/停止扫描");
        handler.removeCallbacksAndMessages(null);
    }

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        if (!hasRequiredPermissionForConnect()) { Toast.makeText(this, "缺少蓝牙连接权限", Toast.LENGTH_SHORT).show(); requestBlePermissions(); return; }
        if (device == null) { Log.e(TAG, "设备为空，无法连接"); return; }

        isSafeToSend = false;
        mHasAttemptedInitialCommand = false;
        connectedDevice = device;

        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            Log.d(TAG, "设备未绑定，尝试创建绑定: " + device.getAddress());
            if (!device.createBond()) Log.e(TAG, "启动绑定过程失败 (createBond返回false)");
        } else {
            Log.d(TAG, "设备已绑定或正在绑定中，状态: " + bondStateToString(device.getBondState()));
        }

        if (bluetoothGatt != null) {
            Log.d(TAG, "关闭之前的GATT连接 (connectToDevice)...");
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        statusTextView.setText("连接中... " + device.getAddress());
        Log.d(TAG, "尝试连接GATT到: " + device.getAddress());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            bluetoothGatt = device.connectGatt(this, false, gattCallback);
        }
        if (bluetoothGatt == null) {
            Log.e(TAG, "device.connectGatt 返回 null!");
            statusTextView.setText("连接尝试失败 (GATT为null)");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String deviceAddress;
            if (gatt != null && gatt.getDevice() != null) {
                deviceAddress = gatt.getDevice().getAddress();
            } else {
                deviceAddress = "";
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "已连接到GATT服务器: " + deviceAddress + ". 绑定状态: " + bondStateToString(gatt.getDevice().getBondState()));
                    runOnUiThread(() -> statusTextView.setText("已连接: " + deviceAddress));
                    Log.d(TAG, "尝试请求MTU (23)...");
                    if (!gatt.requestMtu(23)) {
                        Log.e(TAG, "requestMtu(23) 调用失败，直接发现服务");
                        discoverServicesAfterMtu(gatt);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "已从GATT服务器断开: " + deviceAddress + " (newState: DISCONNECTED)");
                    runOnUiThread(() -> statusTextView.setText("已断开连接"));
                    closeGatt();
                }
            } else {
                Log.e(TAG, "GATT连接状态改变错误，Status: " + status + " (Device: " + deviceAddress + ", newState: " +newState + ")");
                runOnUiThread(() -> statusTextView.setText("连接失败，错误: " + status));
                closeGatt();
            }
        }

        @SuppressLint("MissingPermission")
        private void discoverServicesAfterMtu(BluetoothGatt gatt){
            if (gatt == null) { Log.e(TAG, "discoverServicesAfterMtu: GATT is null"); return; }
            Log.d(TAG, "开始发现服务...");
            if (!gatt.discoverServices()) {
                Log.e(TAG, "启动服务发现调用失败 (gatt.discoverServices() returned false)");
                runOnUiThread(() -> statusTextView.setText("服务发现启动失败"));
                closeGatt();
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) Log.d(TAG, "MTU成功更改为: " + mtu);
            else Log.e(TAG, "MTU更改失败，状态: " + status + ", Actual MTU: " + mtu);
            discoverServicesAfterMtu(gatt);
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (gatt == null) { Log.e(TAG, "onServicesDiscovered: GATT is null"); return; }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "服务发现成功!");
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service == null) { Log.e(TAG, "目标服务 " + SERVICE_UUID + " 未找到!"); runOnUiThread(() -> statusTextView.setText("目标服务未找到")); setSafeToSendAfterDelay("目标服务未找到"); return; }

                BluetoothGattCharacteristic charToNotify = service.getCharacteristic(CHARACTERISTIC_APP_RECEIVES_FROM_MODULE_NOTIFY);
                if (charToNotify != null) {
                    Log.i(TAG, "用于通知的特征 (" + CHARACTERISTIC_APP_RECEIVES_FROM_MODULE_NOTIFY + ") Properties: " + charToNotify.getProperties());
                    if ((charToNotify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        Log.d(TAG, "为特征 " + CHARACTERISTIC_APP_RECEIVES_FROM_MODULE_NOTIFY + " 启用通知");
                        enableNotifications(gatt, charToNotify);
                    } else {
                        Log.e(TAG, "特征 " + CHARACTERISTIC_APP_RECEIVES_FROM_MODULE_NOTIFY + " 不支持通知。");
                        setSafeToSendAfterDelay("目标通知特征不支持Notify");
                    }
                } else {
                    Log.e(TAG, "用于通知的特征 " + CHARACTERISTIC_APP_RECEIVES_FROM_MODULE_NOTIFY + " 未找到!");
                    setSafeToSendAfterDelay("目标通知特征未找到");
                }
                runOnUiThread(() -> statusTextView.append("\n服务准备就绪"));
            } else {
                Log.e(TAG, "服务发现失败，状态: " + status);
                runOnUiThread(() -> statusTextView.setText("服务发现失败: " + status));
                setSafeToSendAfterDelay("服务发现失败 (status: " + status + ")");
            }
        }

        @SuppressLint("MissingPermission")
        private void enableNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (gatt == null) { Log.e(TAG, "enableNotifications: GATT is null"); setSafeToSendAfterDelay("GATT null (enableNotifications)"); return;}
            if (!hasRequiredPermissionForConnect()) { setSafeToSendAfterDelay("权限不足启用通知"); return; }

            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                Log.e(TAG, "为特征 " + characteristic.getUuid() + " setCharacteristicNotification失败");
                setSafeToSendAfterDelay("setCharacteristicNotification失败");
                return;
            }
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
            if (descriptor == null) {
                Log.e(TAG, "CCCD for " + characteristic.getUuid() + " 未找到!");
                setSafeToSendAfterDelay("CCCD未找到 (enableNotifications)");
                return;
            }
            Log.d(TAG, "准备写入CCCD for " + characteristic.getUuid());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int result = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                Log.d(TAG, "gatt.writeDescriptor(value) API 33+ returned code: " + result);
            } else {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!gatt.writeDescriptor(descriptor)) {
                    Log.e(TAG, "写入CCCD描述符失败 for " + characteristic.getUuid() + " (排队失败)");
                    setSafeToSendAfterDelay("writeDescriptor排队失败 (enableNotifications)");
                    return;
                }
            }
            Log.d(TAG, "CCCD写入已排队 for " + characteristic.getUuid());
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (CCCD_UUID.equals(descriptor.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "CCCD写入成功 for " + descriptor.getCharacteristic().getUuid() + ". 通知已启用.");
                    runOnUiThread(() -> receivedDataTextView.append("\n通知已为 " + descriptor.getCharacteristic().getUuid().toString().substring(4,8) + " 开启"));
                } else {
                    Log.e(TAG, "CCCD写入失败 for " + descriptor.getCharacteristic().getUuid() + ". 状态: " + status);
                }
                setSafeToSendAfterDelay("CCCD写入回调 (状态 "+status+")");
            }
        }

        private void setSafeToSendAfterDelay(String reason) {
            handler.postDelayed(() -> {
                Log.i(TAG, reason + ", 延迟后设置 isSafeToSend = true");
                isSafeToSend = true;
            }, 300);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            String writtenValue = "";
            if (characteristic.getValue() != null) {
                writtenValue = new String(characteristic.getValue(), StandardCharsets.UTF_8);
            }

            if (CHARACTERISTIC_APP_WRITES_TO_MODULE.equals(characteristic.getUuid())) {
                if (characteristic.getWriteType() == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "数据(无应答)写入已成功排队/发送: " + writtenValue.replace("\r\n", "\\r\\n"));
                        // UI updated in sendDataToModule for WRITE_NO_RESPONSE upon successful queueing.
                    } else {
                        Log.e(TAG, "数据(无应答)写入失败(回调), 错误码: " + status + ", Value: " + writtenValue.replace("\r\n", "\\r\\n"));
                        runOnUiThread(() -> statusTextView.setText("发送(无应答)失败: " + status));
                    }
                } else {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "数据(带应答)写入成功: " + writtenValue.replace("\r\n", "\\r\\n"));
                        if (!writtenValue.startsWith("AT+")) {
                            runOnUiThread(() -> statusTextView.setText("数据发送成功"));
                        } else {
                            Log.i(TAG, "AT命令 '" + writtenValue.trim() + "' 发送成功 (GATT_SUCCESS)");
                        }
                    } else {
                        Log.e(TAG, "数据(带应答)写入失败，错误码: " + status + ", Value was: " + writtenValue.replace("\r\n", "\\r\\n"));
                        if (!writtenValue.startsWith("AT+")) {
                            runOnUiThread(() -> statusTextView.setText("数据发送失败，错误码: " + status));
                        } else {
                            Log.e(TAG, "AT命令 '" + writtenValue.trim() + "' 发送失败，错误码: " + status);
                        }
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null) {
                Log.w(TAG, "onCharacteristicChanged: null data received for " + characteristic.getUuid());
                return;
            }
            String receivedString = new String(data, StandardCharsets.UTF_8);
            UUID charUuid = characteristic.getUuid();
            Log.d(TAG, "特征 " + charUuid + " 数据改变: " + receivedString);

            if (CHARACTERISTIC_APP_RECEIVES_FROM_MODULE_NOTIFY.equals(charUuid)) {
                runOnUiThread(() -> receivedDataTextView.setText("接收到 (" + charUuid.toString().substring(4,8) +"): " + receivedString));
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void sendDataToModule(String data) {
        if (bluetoothGatt == null) { Log.e(TAG, "GATT未连接"); Toast.makeText(this, "设备未连接", Toast.LENGTH_SHORT).show(); return; }
        if (!hasRequiredPermissionForConnect()) { Toast.makeText(this, "缺少连接权限", Toast.LENGTH_SHORT).show(); return; }

        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service == null) { Log.e(TAG, "服务未找到"); Toast.makeText(this, "服务未找到", Toast.LENGTH_SHORT).show(); return; }
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHARACTERISTIC_APP_WRITES_TO_MODULE);
        if (characteristic == null) { Log.e(TAG, "写入特征 ("+CHARACTERISTIC_APP_WRITES_TO_MODULE+") 未找到"); Toast.makeText(this, "写入特征未找到", Toast.LENGTH_SHORT).show(); return; }

        int properties = characteristic.getProperties();
        Log.d(TAG, "发送数据: 写入特征 (" + CHARACTERISTIC_APP_WRITES_TO_MODULE + ") Properties: " + properties);

        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            Log.d(TAG, "尝试使用 WRITE_TYPE_NO_RESPONSE for " + CHARACTERISTIC_APP_WRITES_TO_MODULE);
        } else if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            Log.d(TAG, "特征不支持NO_RESPONSE, 尝试使用 WRITE_TYPE_DEFAULT for " + CHARACTERISTIC_APP_WRITES_TO_MODULE);
        } else {
            Log.e(TAG, "特征既不支持WRITE也不支持WRITE_NO_RESPONSE. Props: " + properties); Toast.makeText(this, "特征写入类型不支持", Toast.LENGTH_SHORT).show(); return;
        }

        byte[] value = data.getBytes(StandardCharsets.UTF_8);

        boolean queuedSuccessfully = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int result = bluetoothGatt.writeCharacteristic(characteristic, value, characteristic.getWriteType());
            Log.d(TAG, "准备发送数据(API33+): " + data.replace("\r\n", "\\r\\n") + ", WriteType: "+characteristic.getWriteType()+", Result code: " + result);
            if (result == BluetoothGatt.GATT_SUCCESS) {
                queuedSuccessfully = true;
            } else {
                Log.e(TAG, "writeCharacteristic (API33+) 调用失败, 返回码: " + result); Toast.makeText(this, "发送排队失败 (API33+)", Toast.LENGTH_SHORT).show();
            }
        } else {
            characteristic.setValue(value);
            Log.d(TAG, "准备发送数据(OldAPI): " + data.replace("\r\n", "\\r\\n") + " (Bytes: " + bytesToHex(value) + ")");
            if (bluetoothGatt.writeCharacteristic(characteristic)) {
                queuedSuccessfully = true;
            } else {
                Log.e(TAG, "writeCharacteristic (OldAPI) 调用失败 for: " + data.replace("\r\n", "\\r\\n")); Toast.makeText(this, "发送排队失败", Toast.LENGTH_SHORT).show();
            }
        }

        if (queuedSuccessfully) {
            Log.d(TAG, "数据写入已排队... for: " + data.replace("\r\n", "\\r\\n"));
            if (characteristic.getWriteType() == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                if (!data.startsWith("AT+")) { // Only update UI for user data, not AT commands for NO_RESPONSE
                    runOnUiThread(() -> statusTextView.setText("数据(无应答)已发送"));
                } else {
                    Log.i(TAG, "AT命令 '" + data.trim() + "' (无应答) 已发送");
                }
            }
        }
    }


    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (bluetoothGatt != null) {
            isSafeToSend = false;
            mHasAttemptedInitialCommand = false;
            Log.d(TAG, "正在关闭GATT连接...");
            String deviceAddress = "";
            if (bluetoothGatt.getDevice() != null) deviceAddress = bluetoothGatt.getDevice().getAddress();

            if (hasRequiredPermissionForConnect()) {
                bluetoothGatt.disconnect();
                Log.d(TAG, "GATT disconnect() called for " + deviceAddress);
            }
            handler.postDelayed(() -> {
                if (bluetoothGatt != null) {
                    Log.d(TAG, "执行GATT close() for " + (bluetoothGatt.getDevice()!=null? bluetoothGatt.getDevice().getAddress() : "null device"));
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                    Log.d(TAG, "GATT已关闭 (closeGatt delayed)");
                }
            }, 200); // Increased delay for disconnect to propagate

            connectedDevice = null;
            runOnUiThread(()-> {
                if(!isScanning) statusTextView.setText("状态: 未连接");
            });
        } else {
            Log.d(TAG, "closeGatt: gatt is already null");
        }
    }

    private final BroadcastReceiver bondStateReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                final int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                final int previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);

                if (device == null) return;
                Log.d(TAG, "绑定状态改变 for " + device.getAddress() +
                        ": " + bondStateToString(previousBondState) +
                        " -> " + bondStateToString(bondState));
                if (connectedDevice != null && device.getAddress().equals(connectedDevice.getAddress())) {
                    if (bondState == BluetoothDevice.BOND_BONDED) {
                        Log.i(TAG, "目标设备已成功绑定。");
                    } else if (bondState == BluetoothDevice.BOND_NONE && previousBondState == BluetoothDevice.BOND_BONDING) {
                        Log.e(TAG, "目标设备绑定失败。");
                    }
                }
            }
        }
    };

    private String bondStateToString(int bondState) {
        switch (bondState) {
            case BluetoothDevice.BOND_NONE: return "BOND_NONE";
            case BluetoothDevice.BOND_BONDING: return "BOND_BONDING";
            case BluetoothDevice.BOND_BONDED: return "BOND_BONDED";
            default: return "UNKNOWN (" + bondState + ")";
        }
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }
    private boolean hasRequiredPermissionForConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
    private boolean hasRequiredPermissionsForScanStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(bondStateReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Receiver not registered or already unregistered: " + e.getMessage());
        }
        stopBleScan();
        closeGatt();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
}