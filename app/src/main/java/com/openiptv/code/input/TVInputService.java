package com.openiptv.code.input;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.PlaybackParams;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.SparseArray;
import android.view.Surface;
import android.view.View;
import android.view.accessibility.CaptioningManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.openiptv.code.DatabaseActions;
import com.openiptv.code.PreferenceUtils;
import com.openiptv.code.R;
import com.openiptv.code.epg.Channel;
import com.openiptv.code.epg.EPGService;
import com.openiptv.code.epg.Program;
import com.openiptv.code.epg.RecordedProgram;
import com.openiptv.code.htsp.BaseConnection;
import com.openiptv.code.htsp.ConnectionInfo;
import com.openiptv.code.htsp.HTSPException;
import com.openiptv.code.htsp.HTSPMessage;
import com.openiptv.code.player.TVPlayer;

import java.util.List;

import static com.openiptv.code.Constants.PREFERENCE_SETUP_COMPLETE;
import static com.openiptv.code.Constants.RESTART_SERVICES;


public class TVInputService extends TvInputService {
    private static final String TAG = TVInputService.class.getSimpleName();
    private BaseConnection connection;

    @Override
    public void onCreate() {
        super.onCreate();
        PreferenceUtils preferenceUtils = new PreferenceUtils(this);

        if (preferenceUtils.getBoolean(PREFERENCE_SETUP_COMPLETE)) {
            DatabaseActions databaseActions = new DatabaseActions(getApplicationContext());
            databaseActions.syncActiveAccount();
            databaseActions.close();

            getApplicationContext().startService(new Intent(getApplicationContext(), EPGService.class));

            createConnection();
        }
    }

    @Override
    public final Session onCreateSession(String inputId) {
        if (connection == null) {
            createConnection();
        }
        TVSession session = new TVSession(this, inputId, connection);
        session.setOverlayViewEnabled(true);
        return session;
    }

    @Override
    public TvInputService.RecordingSession onCreateRecordingSession(String inputId) {
        RecordingSession session = new RecordingSession(this, connection);
        return session;
    }

    class RecordingSession extends TvInputService.RecordingSession {
        private BaseConnection connection;
        private Context context;
        private Uri program;
        private Uri channel;

        /**
         * Creates a new RecordingSession.
         *
         * @param context The context of the application
         */
        public RecordingSession(Context context, BaseConnection connection) {
            super(context);
            this.context = context;
            this.connection = connection;
        }

        @Override
        public void onTune(Uri channelUri) {
            notifyTuned(channelUri);
            Log.d(TAG, "Recording added to server.");
            this.channel = channelUri;
        }

        @Override
        public void onStartRecording(@Nullable Uri programUri) {
            int eventID = Program.getProgramIdFromProgramUri(context, programUri);
            int channelID = Channel.getChannelIdFromChannelUri(context, channel);
            Log.d(TAG, "eventID " + eventID);
            this.program = programUri;

            HTSPMessage message = new HTSPMessage();
            message.put("method", "addDvrEntry");
            message.put("eventId", eventID);
            message.put("start", (Program.getProgramStartFromProgramUri(context, programUri) / 1000));
            message.put("stop", (Program.getProgramEndFromProgramUri(context, programUri) / 1000));

            try {
                connection.getHTSPMessageDispatcher().sendMessage(message);
            } catch (HTSPException ignored) {

            }
        }

        @Override
        public void onStopRecording() {
            notifyRecordingStopped(program);
        }

        @Override
        public void onRelease() {

        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        connection.stop();

        Log.d(TAG, "TVINPUTSERVICE KILLED");

        if (RESTART_SERVICES)
            startService(new Intent(this, TvInputService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startMyOwnForeground();
        else
            startForeground(1, new Notification());
        return START_STICKY; // Makes sure the service restarts after being destroyed.
    }

    public void createConnection() {
        String username = DatabaseActions.activeAccount.getString("username");
        String password = DatabaseActions.activeAccount.getString("password");
        String hostname = DatabaseActions.activeAccount.getString("hostname");
        String port = DatabaseActions.activeAccount.getString("port");
        String clientName = DatabaseActions.activeAccount.getString("clientName");

        connection = new BaseConnection(new ConnectionInfo(hostname, Integer.parseInt(port), username, password, clientName + "_Subscription", String.valueOf(Build.VERSION.SDK_INT)));
        connection.start();
    }

    class TVSession extends TvInputService.Session implements TVPlayer.Listener {
        private TVPlayer player;
        private String inputId;
        private Context context;
        private BaseConnection connection;
        private CaptioningManager captioningManager;

        TVSession(Context context, String inputId, BaseConnection connection) {
            super(context);

            setOverlayViewEnabled(true);
            captioningManager = (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);

            this.context = context;
            this.inputId = inputId;
            this.connection = connection;

            this.player = new TVPlayer(context, connection);
            this.player.addListener(this);
        }

        @Override
        public void onRelease() {
            Log.d(TAG, "TVSession Released");
            player.stop();
        }

        @Override
        public boolean onSetSurface(@Nullable Surface surface) {
            Log.d(TAG, "Setting Surface");
            return player.setSurface(surface);
        }

        //change the stream volume, this method just simply call method in the TVPlayer class
        @Override
        public void onSetStreamVolume(float volume) {
            player.changeVolume(volume);
        }

        // mute the stream, using the same method with onSetStreamVolume
        public void onSetStreamMute() {
            player.changeVolume(0.0f);
        }

        @Override
        public boolean onTune(Uri channelUri) {
            notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_AVAILABLE);
            player.prepare(channelUri, false);
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
            Log.d(TAG, "Android has request to tune to channel: " + Channel.getChannelIdFromChannelUri(context, channelUri));

            notifyContentAllowed();
            notifyVideoAvailable();

            player.start();
            return true;
        }

        @Override
        public long onTimeShiftGetStartPosition() {
            return player.getTimeshiftStartPosition();
        }

        @Override
        public long onTimeShiftGetCurrentPosition() {
            return player.getTimeshiftCurrentPosition();
        }

        @Override
        public void onTimeShiftSetPlaybackParams(PlaybackParams params) {
            super.onTimeShiftSetPlaybackParams(params);
            Log.d(TAG, "SET PLAYBACK PARAMS" + params.getSpeed());
            player.setPlaybackParams(params);
        }

        @Override
        public void onTimeShiftSeekTo(long timeMs) {
            Log.d(TAG, "Wanting to seek " + (timeMs - System.currentTimeMillis()) + "ms");
            player.seek(timeMs);
        }

        @Override
        public void onTimeShiftPause() {
            Log.d(TAG, "PAUSE");
            player.pause();
        }

        @Override
        public void onTimeShiftResume() {
            Log.d(TAG, "RESUME");
            player.resume();
        }

        @Override
        public void onTimeShiftPlay(Uri recordedProgramUri) {
            notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_AVAILABLE);
            //Log.d(TAG, "recorded program: " + recordedProgramUri.getPathSegments().get(1));
            //Log.d(TAG, "recorded program TVH ID: " + RecordedProgram.getRecordingIdFromRecordingUri(context, recordedProgramUri));

            player.prepare(recordedProgramUri, true);
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
            player.start();

            notifyContentAllowed();
            notifyVideoAvailable();
        }

        @Override
        public void onSetCaptionEnabled(boolean enabled) {
            // Stub
        }

        @Override
        public View onCreateOverlayView() {
            return player.getOverlayView(captioningManager.getUserStyle());
        }

        @Override
        public void onTracks(List<TvTrackInfo> tracks, SparseArray<String> selectedTracks) {
            notifyTracksChanged(tracks);

            for (int i = 0; i < selectedTracks.size(); i++) {
                final int selectedTrackType = selectedTracks.keyAt(i);
                final String selectedTrackId = selectedTracks.get(selectedTrackType);

                notifyTrackSelected(selectedTrackType, selectedTrackId);
            }
        }

        // TODO: Implement
        @Override
        public boolean onSelectTrack(int type, String trackId) {
            Log.d(TAG, "Session selectTrack: " + type + " / " + trackId);
            notifyTrackSelected(type, trackId);
            return player.selectTrack(type,trackId);
        }
    }

    // TODO: Not use some shady code from the interwebs
    private void startMyOwnForeground() {
        String NOTIFICATION_CHANNEL_ID = "com.openiptv.code";
        String channelName = "My Foreground Service";
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        manager.createNotificationChannel(chan);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        Notification notification = notificationBuilder.setOngoing(true)
                .setSmallIcon(R.drawable.standard)
                .setContentTitle("App is running in background")
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(2, notification);
    }
}