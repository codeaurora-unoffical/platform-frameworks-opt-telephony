/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.telephony.cat;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Class used to pass CAT messages from telephony to application. Application
 * should call getXXX() to get commands's specific values.
 *
 */
public class CatCmdMessage implements Parcelable {
    // members
    CommandDetails mCmdDet;
    private TextMessage mTextMsg;
    private Menu mMenu;
    private Input mInput;
    private BrowserSettings mBrowserSettings = null;
    private ToneSettings mToneSettings = null;
    private CallSettings mCallSettings = null;
    private boolean mLoadIconFailed = false;
    private SetupEventListSettings mSetupEventListSettings = null;

    // Command Qualifier values for refresh command
    static final int REFRESH_NAA_INIT_AND_FULL_FILE_CHANGE  = 0x00;
    static final int REFRESH_NAA_INIT_AND_FILE_CHANGE       = 0x02;
    static final int REFRESH_NAA_INIT                       = 0x03;
    static final int REFRESH_UICC_RESET                     = 0x04;
    /*
     * Container for Launch Browser command settings.
     */
    public class BrowserSettings {
        public String url;
        public LaunchBrowserMode mode;
    }

    /*
     * Container for Call Setup command settings.
     */
    public class CallSettings {
        public TextMessage confirmMsg;
        public TextMessage callMsg;
    }

    public class SetupEventListSettings {
        public int[] eventList;
    }

    public final class SetupEventListConstants {
        // Event values in SETUP_EVENT_LIST Proactive Command as per ETSI 102.223
        public static final int USER_ACTIVITY_EVENT          = 0x04;
        public static final int IDLE_SCREEN_AVAILABLE_EVENT  = 0x05;
        public static final int LANGUAGE_SELECTION_EVENT     = 0x07;
        public static final int BROWSER_TERMINATION_EVENT    = 0x08;
        public static final int BROWSING_STATUS_EVENT        = 0x0F;
    }

    public final class BrowserTerminationCauses {
        public static final int USER_TERMINATION             = 0x00;
        public static final int ERROR_TERMINATION            = 0x01;
    }

    CatCmdMessage(CommandParams cmdParams) {
        mCmdDet = cmdParams.cmdDet;
        mLoadIconFailed =  cmdParams.loadIconFailed;
        switch(getCmdType()) {
        case SET_UP_MENU:
        case SELECT_ITEM:
            mMenu = ((SelectItemParams) cmdParams).menu;
            break;
        case DISPLAY_TEXT:
        case SET_UP_IDLE_MODE_TEXT:
        case SEND_DTMF:
        case SEND_SMS:
        case REFRESH:
        case SEND_SS:
        case SEND_USSD:
            mTextMsg = ((DisplayTextParams) cmdParams).textMsg;
            break;
        case GET_INPUT:
        case GET_INKEY:
            mInput = ((GetInputParams) cmdParams).input;
            break;
        case LAUNCH_BROWSER:
            mTextMsg = ((LaunchBrowserParams) cmdParams).confirmMsg;
            mBrowserSettings = new BrowserSettings();
            mBrowserSettings.url = ((LaunchBrowserParams) cmdParams).url;
            mBrowserSettings.mode = ((LaunchBrowserParams) cmdParams).mode;
            break;
        case PLAY_TONE:
            PlayToneParams params = (PlayToneParams) cmdParams;
            mToneSettings = params.settings;
            mTextMsg = params.textMsg;
            break;
        case GET_CHANNEL_STATUS:
            mTextMsg = ((CallSetupParams) cmdParams).confirmMsg;
            break;
        case SET_UP_CALL:
            mCallSettings = new CallSettings();
            mCallSettings.confirmMsg = ((CallSetupParams) cmdParams).confirmMsg;
            mCallSettings.callMsg = ((CallSetupParams) cmdParams).callMsg;
            break;
        case OPEN_CHANNEL:
        case CLOSE_CHANNEL:
        case RECEIVE_DATA:
        case SEND_DATA:
            BIPClientParams param = (BIPClientParams) cmdParams;
            mTextMsg = param.textMsg;
            break;
        case SET_UP_EVENT_LIST:
            mSetupEventListSettings = new SetupEventListSettings();
            mSetupEventListSettings.eventList = ((SetEventListParams) cmdParams).eventInfo;
            break;
        }
    }

    public CatCmdMessage(Parcel in) {
        mCmdDet = in.readParcelable(null);
        mTextMsg = in.readParcelable(null);
        mMenu = in.readParcelable(null);
        mInput = in.readParcelable(null);
        mLoadIconFailed = (Boolean)in.readValue(null);
        switch (getCmdType()) {
        case LAUNCH_BROWSER:
            mBrowserSettings = new BrowserSettings();
            mBrowserSettings.url = in.readString();
            mBrowserSettings.mode = LaunchBrowserMode.values()[in.readInt()];
            break;
        case PLAY_TONE:
            mToneSettings = in.readParcelable(null);
            break;
        case SET_UP_CALL:
            mCallSettings = new CallSettings();
            mCallSettings.confirmMsg = in.readParcelable(null);
            mCallSettings.callMsg = in.readParcelable(null);
            break;
        case SET_UP_EVENT_LIST:
            mSetupEventListSettings = new SetupEventListSettings();
            int length = in.readInt();
            mSetupEventListSettings.eventList = new int[length];
            for (int i = 0; i < length; i++) {
                mSetupEventListSettings.eventList[i] = in.readInt();
            }
            break;
        }
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mCmdDet, 0);
        dest.writeParcelable(mTextMsg, 0);
        dest.writeParcelable(mMenu, 0);
        dest.writeParcelable(mInput, 0);
        dest.writeValue(mLoadIconFailed);
        switch(getCmdType()) {
        case LAUNCH_BROWSER:
            dest.writeString(mBrowserSettings.url);
            dest.writeInt(mBrowserSettings.mode.ordinal());
            break;
        case PLAY_TONE:
            dest.writeParcelable(mToneSettings, 0);
            break;
        case SET_UP_CALL:
            dest.writeParcelable(mCallSettings.confirmMsg, 0);
            dest.writeParcelable(mCallSettings.callMsg, 0);
            break;
        case SET_UP_EVENT_LIST:
            dest.writeIntArray(mSetupEventListSettings.eventList);
            break;
        }
    }

    public static final Parcelable.Creator<CatCmdMessage> CREATOR = new Parcelable.Creator<CatCmdMessage>() {
        public CatCmdMessage createFromParcel(Parcel in) {
            return new CatCmdMessage(in);
        }

        public CatCmdMessage[] newArray(int size) {
            return new CatCmdMessage[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    /* external API to be used by application */
    public AppInterface.CommandType getCmdType() {
        return AppInterface.CommandType.fromInt(mCmdDet.typeOfCommand);
    }

    public Menu getMenu() {
        return mMenu;
    }

    public Input geInput() {
        return mInput;
    }

    public TextMessage geTextMessage() {
        return mTextMsg;
    }

    public BrowserSettings getBrowserSettings() {
        return mBrowserSettings;
    }

    public ToneSettings getToneSettings() {
        return mToneSettings;
    }

    public CallSettings getCallSettings() {
        return mCallSettings;
    }

    public boolean isRefreshResetOrInit() {
        if ((mCmdDet.commandQualifier == REFRESH_NAA_INIT_AND_FULL_FILE_CHANGE)
         || (mCmdDet.commandQualifier == REFRESH_NAA_INIT_AND_FILE_CHANGE )
         || (mCmdDet.commandQualifier == REFRESH_NAA_INIT)
         || (mCmdDet.commandQualifier == REFRESH_UICC_RESET)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * API to be used by application to check if loading optional icon
     * has failed
     */
    public boolean hasIconLoadFailed() {
        return mLoadIconFailed;
    }

    public SetupEventListSettings getSetEventList() {
        return mSetupEventListSettings;
    }
}
