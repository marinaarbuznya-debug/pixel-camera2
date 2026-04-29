package com.pixel.camera;

import android.app.*;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class CameraStreamService extends Service {

    private static final String TAG = "CameraStream";
    private static final String CHANNEL_ID = "camera_channel";
    private static final int PORT = 8080;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private ServerSocket serverSocket;
    private final List<OutputStream> clients = new CopyOnWriteArrayList<>();
    private volatile byte[] lastFrame;
    private String cameraId;
    private boolean torchOn = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(2, buildNotification());

        startBackgroundThread();
        startHttpServer();
        openCamera();
        listenForCommands();
        updateServerUrl();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void openCamera() {
        try {
            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            cameraId = cameraManager.getCameraIdList()[0];

            StreamConfigurationMap map = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            Size selectedSize = sizes[sizes.length - 1]; // Найменший розмір для швидкості
            for (Size s : sizes) {
                if (s.getWidth() <= 640 && s.getHeight() <= 480) {
                    selectedSize = s;
                    break;
                }
            }

            imageReader = ImageReader.newInstance(
                selectedSize.getWidth(), selectedSize.getHeight(),
                ImageFormat.JPEG, 2);

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    try {
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        lastFrame = bytes;
                        broadcastFrame(bytes);
                    } finally {
                        image.close();
                    }
                }
            }, backgroundHandler);

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) { camera.close(); }
            }, backgroundHandler);

        } catch (Exception e) {
            Log.e(TAG, "Camera error: " + e.getMessage());
        }
    }

    private void startPreview() {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            cameraDevice.createCaptureSession(
                Collections.singletonList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            FirebaseDatabase.getInstance().getReference("status").setValue("online");
                        } catch (Exception e) {
                            Log.e(TAG, "Preview error: " + e.getMessage());
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) {}
                }, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "Preview error: " + e.getMessage());
        }
    }

    private void startHttpServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d(TAG, "Server started on port " + PORT);
                while (!serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        handleClient(client);
                    } catch (Exception e) {
                        if (!serverSocket.isClosed()) Log.e(TAG, "Client error: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            }
        }).start();
    }

    private void handleClient(Socket client) {
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {}

                OutputStream out = client.getOutputStream();
                out.write("HTTP/1.0 200 OK\r\n".getBytes());
                out.write("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n".getBytes());
                out.write("\r\n".getBytes());
                out.flush();

                clients.add(out);

                // Надіслати останній кадр одразу
                if (lastFrame != null) {
                    sendFrame(out, lastFrame);
                }

            } catch (Exception e) {
                Log.e(TAG, "Handle client error: " + e.getMessage());
            }
        }).start();
    }

    private void broadcastFrame(byte[] frameData) {
        List<OutputStream> deadClients = new ArrayList<>();
        for (OutputStream out : clients) {
            try {
                sendFrame(out, frameData);
            } catch (Exception e) {
                deadClients.add(out);
            }
        }
        clients.removeAll(deadClients);
    }

    private void sendFrame(OutputStream out, byte[] frameData) throws IOException {
        out.write("--frame\r\n".getBytes());
        out.write("Content-Type: image/jpeg\r\n".getBytes());
        out.write(("Content-Length: " + frameData.length + "\r\n").getBytes());
        out.write("\r\n".getBytes());
        out.write(frameData);
        out.write("\r\n".getBytes());
        out.flush();
    }

    private void updateServerUrl() {
        new Thread(() -> {
            try {
                // Отримати зовнішній IP через сервіс
                URL url = new URL("https://api.ipify.org");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String ip = br.readLine().trim();
                br.close();
                String streamUrl = "http://" + ip + ":" + PORT;
                FirebaseDatabase.getInstance().getReference("stream_url").setValue(streamUrl);
                Log.d(TAG, "Stream URL: " + streamUrl);
            } catch (Exception e) {
                Log.e(TAG, "IP error: " + e.getMessage());
                // Fallback до локального IP
                try {
                    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                    while (interfaces.hasMoreElements()) {
                        NetworkInterface iface = interfaces.nextElement();
                        Enumeration<InetAddress> addrs = iface.getInetAddresses();
                        while (addrs.hasMoreElements()) {
                            InetAddress addr = addrs.nextElement();
                            if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                                String streamUrl = "http://" + addr.getHostAddress() + ":" + PORT;
                                FirebaseDatabase.getInstance().getReference("stream_url").setValue(streamUrl);
                            }
                        }
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Local IP error: " + ex.getMessage());
                }
            }
        }).start();
    }

    private void listenForCommands() {
        FirebaseDatabase.getInstance().getReference("command")
            .addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    String command = snapshot.getValue(String.class);
                    if (command == null) return;
                    try {
                        if ("torch_on".equals(command)) {
                            cameraManager.setTorchMode(cameraId, true);
                            torchOn = true;
                        } else if ("torch_off".equals(command)) {
                            cameraManager.setTorchMode(cameraId, false);
                            torchOn = false;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Torch error: " + e.getMessage());
                    }
                    FirebaseDatabase.getInstance().getReference("command").setValue(null);
                }
                @Override public void onCancelled(DatabaseError error) {}
            });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (captureSession != null) captureSession.close();
            if (cameraDevice != null) cameraDevice.close();
            if (imageReader != null) imageReader.close();
            if (serverSocket != null) serverSocket.close();
            if (backgroundThread != null) backgroundThread.quitSafely();
            FirebaseDatabase.getInstance().getReference("status").setValue("offline");
        } catch (Exception e) {
            e.printStackTrace();
        }
        startForegroundService(new Intent(this, CameraStreamService.class));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "Camera Service", NotificationManager.IMPORTANCE_MIN);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Service")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
