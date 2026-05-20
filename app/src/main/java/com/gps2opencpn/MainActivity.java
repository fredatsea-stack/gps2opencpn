package com.gps2opencpn;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BT = 2;

    private BluetoothAdapter bluetoothAdapter;
    private GpsBluetoothService gpsService;
    private boolean serviceBound = false;

    private Spinner spinnerDevices;
    private Button btnStartStop;
    private TextView tvStatus;
    private TextView tvNmea;
    private TextView tvGpsInfo;

    private List<BluetoothDevice> pairedDevices = new ArrayList<>();
    private List<String> deviceNames = new ArrayList<>();

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            GpsBluetoothService.LocalBinder b = (GpsBluetoothService.LocalBinder) binder;
            gpsService = b.getService();
            serviceBound = true;
            gpsService.setStatusListener(new GpsBluetoothService.StatusListener() {
                @Override
                public void onStatusChanged(String status) {
                    runOnUiThread(() -> tvStatus.setText(status));
                }
                @Override
                public void onNmeaSentence(String nmea) {
                    runOnUiThread(() -> tvNmea.setText(nmea));
                }
                @Override
                public void onGpsInfo(String info) {
                    runOnUiThread(() -> tvGpsInfo.setText(info));
                }
                @Override
                public void onStopped() {
                    runOnUiThread(() -> {
                        btnStartStop.setText("▶  DÉMARRER");
                        btnStartStop.setBackgroundColor(0xFF2E7D32);
                        tvStatus.setText("Arrêté");
                    });
                }
            });
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                if (state == BluetoothAdapter.STATE_ON) populateDeviceList();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spinnerDevices = findViewById(R.id.spinnerDevices);
        btnStartStop   = findViewById(R.id.btnStartStop);
        tvStatus       = findViewById(R.id.tvStatus);
        tvNmea         = findViewById(R.id.tvNmea);
        tvGpsInfo      = findViewById(R.id.tvGpsInfo);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        registerReceiver(btReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

        btnStartStop.setOnClickListener(v -> {
            if (serviceBound && gpsService.isRunning()) {
                stopGpsService();
            } else {
                startGpsService();
            }
        });

        checkPermissions();
    }

    private void checkPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        List<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                toRequest.add(p);
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), REQUEST_PERMISSIONS);
        } else {
            initBluetooth();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_PERMISSIONS) {
            initBluetooth();
        }
    }

    private void initBluetooth() {
        if (bluetoothAdapter == null) {
            tvStatus.setText("Bluetooth non disponible");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        } else {
            populateDeviceList();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            populateDeviceList();
        }
    }

    private void populateDeviceList() {
        pairedDevices.clear();
        deviceNames.clear();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            tvStatus.setText("Permission Bluetooth manquante");
            return;
        }

        Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                pairedDevices.add(d);
                deviceNames.add(d.getName() + "\n" + d.getAddress());
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, deviceNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(adapter);

        if (deviceNames.isEmpty()) {
            tvStatus.setText("Aucun appareil BT appairé.\nAppaire ton PC d'abord.");
        } else {
            tvStatus.setText("Sélectionne ton PC et appuie sur DÉMARRER");
        }
    }

    private void startGpsService() {
        int idx = spinnerDevices.getSelectedItemPosition();
        if (idx < 0 || idx >= pairedDevices.size()) {
            Toast.makeText(this, "Sélectionne un appareil BT", Toast.LENGTH_SHORT).show();
            return;
        }
        BluetoothDevice device = pairedDevices.get(idx);

        Intent intent = new Intent(this, GpsBluetoothService.class);
        intent.putExtra("device_address", device.getAddress());
        ContextCompat.startForegroundService(this, intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        btnStartStop.setText("⏹  ARRÊTER");
        btnStartStop.setBackgroundColor(0xFFC62828);
        tvStatus.setText("Connexion en cours…");
    }

    private void stopGpsService() {
        if (serviceBound) {
            gpsService.stopService();
            unbindService(serviceConnection);
            serviceBound = false;
        }
        stopService(new Intent(this, GpsBluetoothService.class));
        btnStartStop.setText("▶  DÉMARRER");
        btnStartStop.setBackgroundColor(0xFF2E7D32);
        tvStatus.setText("Arrêté");
        tvNmea.setText("—");
        tvGpsInfo.setText("—");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(btReceiver);
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }
}
