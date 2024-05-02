/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.voip;

import android.telecom.CallAttributes;
import android.telecom.Log;
import android.telecom.VideoProfile;

import com.android.server.telecom.AnomalyReporterAdapter;
import com.android.server.telecom.AnomalyReporterAdapterImpl;

import java.util.UUID;

/**
 * This remapping class is needed because {@link VideoProfile} has more fine grain levels of video
 * states as apposed to Transactional video states (defined in  {@link CallAttributes.CallType}.
 * To be more specific, there are 3 video states (rx, tx, and bi-directional).
 * {@link CallAttributes.CallType} only has 2 states (audio and video).
 *
 * The reason why Transactional calls have fewer states is due to the fact that the framework is
 * only used by VoIP apps and Telecom only cares to know if the call is audio or video.
 *
 * Calls that are backed by a {@link android.telecom.ConnectionService} have the ability to be
 * managed calls (non-VoIP) and Dialer needs more fine grain video states to update the UI. Thus,
 * {@link VideoProfile} is used for {@link android.telecom.ConnectionService} backed calls.
 */
public class VideoStateTranslation {
    private static final String TAG = VideoStateTranslation.class.getSimpleName();

    /**
     * Client --> Telecom
     * This should be used when the client application is signaling they are changing the video
     * state.
     */
    public static int TransactionalVideoStateToVideoProfileState(int callType) {
        if (callType == CallAttributes.AUDIO_CALL) {
            Log.i(TAG, "CallAttributes.AUDIO_CALL --> VideoProfile.STATE_AUDIO_ONLY");
            return VideoProfile.STATE_AUDIO_ONLY;
        } else if (callType == CallAttributes.VIDEO_CALL) {
            Log.i(TAG, "CallAttributes.VIDEO_CALL--> VideoProfile.STATE_BIDIRECTIONAL");
            return VideoProfile.STATE_BIDIRECTIONAL;
        } else {
            Log.w(TAG, "CallType=[%d] does not have a VideoProfile mapping", callType);
            return VideoProfile.STATE_AUDIO_ONLY;
        }
    }

    /**
     * Telecom --> Client
     * This should be used when Telecom is informing the client of a video state change.
     */
    public static int VideoProfileStateToTransactionalVideoState(int videoProfileState) {
        switch (videoProfileState) {
            case VideoProfile.STATE_AUDIO_ONLY -> {
                Log.i(TAG, "%s --> CallAttributes.AUDIO_CALL",
                        VideoProfileStateToString(videoProfileState));
                return CallAttributes.AUDIO_CALL;
            }
            case VideoProfile.STATE_BIDIRECTIONAL, VideoProfile.STATE_TX_ENABLED,
                    VideoProfile.STATE_RX_ENABLED -> {
                Log.i(TAG, "%s --> CallAttributes.VIDEO_CALL",
                        VideoProfileStateToString(videoProfileState));
                return CallAttributes.VIDEO_CALL;
            }
            default -> {
                Log.w(TAG, "VideoProfile=[%d] does not have a CallType mapping", videoProfileState);
                return CallAttributes.AUDIO_CALL;
            }
        }
    }

    public static String TransactionalVideoStateToString(int transactionalVideoState) {
        if (transactionalVideoState == CallAttributes.AUDIO_CALL) {
            return "CallAttributes.AUDIO_CALL";
        } else if (transactionalVideoState == CallAttributes.VIDEO_CALL) {
            return "CallAttributes.VIDEO_CALL";
        } else {
            return "CallAttributes.UNKNOWN";
        }
    }

    private static String VideoProfileStateToString(int videoProfileState) {
        switch (videoProfileState) {
            case VideoProfile.STATE_BIDIRECTIONAL -> {
                return "VideoProfile.STATE_BIDIRECTIONAL";
            }
            case VideoProfile.STATE_RX_ENABLED -> {
                return "VideoProfile.STATE_RX_ENABLED";
            }
            case VideoProfile.STATE_TX_ENABLED -> {
                return "VideoProfile.STATE_TX_ENABLED";
            }
            case VideoProfile.STATE_AUDIO_ONLY -> {
                return "VideoProfile.STATE_AUDIO_ONLY";
            }
            default -> {
                return "VideoProfile.UNKNOWN";
            }
        }
    }
}
