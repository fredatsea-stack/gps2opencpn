package com.gps2opencpn;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.location.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.util.UUID;

public class GpsBluetoothService extends Service {

    // UUID standard SPP Bluetooth Serial Port Profile
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String CHANNEL_ID = "gps2opencpn_channel";
    private static final int NOTIF_ID = 1;

    private final IBinder binder = new LocalBinder();
    private StatusListener listener;

    private BluetoothSocket btSocket;
    private OutputStream btOutputStream;
    private LocationManager locationManager;
    private boolean running = false;
    private boolean reconnecting = false;
    private String deviceAddress;

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread connectThread;

    public interface StatusListener {
        void onStatusChanged(String status);
        void onNmeaSentence(String nmea);
        void onGpsInfo(String info);
        void onStopped();
    }

    public class LocalBinder extends Binder {
        GpsBluetoothService getService() { return GpsBluetoothService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public void setStatusListener(StatusListener l) { this.listener = l; }
    public boolean isRunning() { return running; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            deviceAddress = intent.getStringExtra("device_address");
        }
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("GPS2OpenCPN actif"));
        running = true;
        startGpsAndBluetooth();
        return START_STICKY;
    }

    private void startGpsAndBluetooth() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000, 0f, gpsListener);
            notifyStatus("GPS actif, connexion BT…");
        } catch (SecurityException e) {
            notifyStatus("Erreur permission GPS");
            return;
        }
        connectBluetooth();
    }

    private void connectBluetooth() {
        if (connectThread != null && connectThread.isAlive()) connectThread.interrupt();
        connectThread = new Thread(() -> {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
            int attempts = 0;
            while (running) {
                attempts++;
                notifyStatus("Connexion BT… (essai " + attempts + ")");
                try {
                    if (btSocket != null) { try { btSocket.close(); } catch (Exception ignored) {} }
                    btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    adapter.cancelDiscovery();
                    btSocket.connect();
                    btOutputStream = btSocket.getOutputStream();
                    reconnecting = false;
                    notifyStatus("✓ Connecté à " + device.getName());
                    return; // succès
                } catch (IOException e) {
                    notifyStatus("BT déconnecté, reconnexion dans 5s…");
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
                }
            }
        });
        connectThread.start();
    }

    private final LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            if (!running) return;

            double lat = loc.getLatitude();
            double lon = loc.getLongitude();
            double speed = loc.getSpeed() * 1.94384f; // m/s → noeuds
            double bearing = loc.getBearing();
            double alt = loc.getAltitude();
            float accuracy = loc.getAccuracy();
            long time = loc.getTime();

            // NMEA GPRMC
            String gprmc = buildGPRMC(lat, lon, speed, bearing, time);
            // NMEA GPGGA
            String gpgga = buildGPGGA(lat, lon, alt, accuracy, time);

            sendNmea(gprmc);
            sendNmea(gpgga);

            String info = String.format("Lat: %.5f°\nLon: %.5f°\nVitesse: %.1f nœuds\nCap: %.0f°\nAlt: %.0fm\nPrécision: %.0fm",
                    lat, lon, speed, bearing, alt, accuracy);
            if (listener != null) {
                mainHandler.post(() -> {
                    listener.onNmeaSentence(gprmc.trim());
                    listener.onGpsInfo(info);
                });
            }
        }
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) { notifyStatus("GPS désactivé !"); }
    };

    private void sendNmea(String sentence) {
        if (btOutputStream == null) return;
        try {
            btOutputStream.write((sentence + "\r\n").getBytes("ASCII"));
            btOutputStream.flush();
        } catch (IOException e) {
            // Reconnexion automatique
            if (!reconnecting) {
                reconnecting = true;
                notifyStatus("BT perdu, reconnexion…");
                connectBluetooth();
            }
        }
    }

    // --- Builders NMEA ---

    private String buildGPRMC(double lat, double lon, double speed, double bearing, long timeMs) {
        java.util.Date d = new java.util.Date(timeMs);
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTime(d);
        String time = String.format("%02d%02d%02d.00",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND));
        String date = String.format("%02d%02d%02d",
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.YEAR) % 100);
        String latStr = formatLatLon(Math.abs(lat), true);
        String latHem = lat >= 0 ? "N" : "S";
        String lonStr = formatLatLon(Math.abs(lon), false);
        String lonHem = lon >= 0 ? "E" : "W";
        String body = String.format("GPRMC,%s,A,%s,%s,%s,%s,%.1f,%.1f,%s,,",
                time, latStr, latHem, lonStr, lonHem, speed, bearing, date);
        return "$" + body + "*" + checksum(body);
    }

    private String buildGPGGA(double lat, double lon, double alt, float acc, long timeMs) {
        java.util.Date d = new java.util.Date(timeMs);
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTime(d);
        String time = String.format("%02d%02d%02d.00",
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND));
        String latStr = formatLatLon(Math.abs(lat), true);
        String latHem = lat >= 0 ? "N" : "S";
        String lonStr = formatLatLon(Math.abs(lon), false);
        String lonHem = lon >= 0 ? "E" : "W";
        float hdop = acc / 5f;
        String body = String.format("GPGGA,%s,%s,%s,%s,%s,1,08,%.1f,%.1f,M,0.0,M,,",
                time, latStr, latHem, lonStr, lonHem, hdop, alt);
        return "$" + body + "*" + checksum(body);
    }

    private String formatLatLon(double val, boolean isLat) {
        int deg = (int) val;
        double min = (val - deg) * 60.0;
        if (isLat)  return String.format("%02d%07.4f", deg, min);
        else        return String.format("%03d%07.4f", deg, min);
    }

    private String checksum(String s) {
        int cs = 0;
        for (char c : s.toCharArray()) cs ^= (int) c;
        return String.format("%02X", cs);
    }

    // --- Helpers ---

    private void notifyStatus(String msg) {
        if (listener != null) mainHandler.post(() -> listener.onStatusChanged(msg));
        updateNotification(msg);
    }

    public void stopService() {
        running = false;
        if (locationManager != null) {
            try { locationManager.removeUpdates(gpsListener); } catch (Exception ignored) {}
        }
        if (connectThread != null) connectThread.interrupt();
        try { if (btSocket != null) btSocket.close(); } catch (Exception ignored) {}
        if (listener != null) mainHandler.post(() -> listener.onStopped());
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "GPS2OpenCPN", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Transmission GPS vers OpenCPN");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent ni = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ni,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GPS2OpenCPN")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        Notification n = buildNotification(text);
        getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
    }
}
