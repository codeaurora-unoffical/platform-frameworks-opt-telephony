/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.telephony.uicc;

import android.os.*;
import android.telephony.Rlog;

import com.android.internal.telephony.CommandsInterface;

/**
 * {@hide}
 */
public final class RuimFileHandler extends IccFileHandler {
    static final String LOG_TAG = "RuimFH";

    //***** Instance Variables

    //***** Constructor
    public RuimFileHandler(UiccCardApplication app, String aid, CommandsInterface ci) {
        super(app, aid, ci);
    }

    //***** Overridden from IccFileHandler

    @Override
    public void loadEFImgTransparent(int fileid, int highOffset, int lowOffset,
            int length, Message onLoaded) {
        Message response = obtainMessage(EVENT_READ_ICON_DONE, fileid, 0,
                onLoaded);

        //As per TS 31.102 and TS 11.11, all the image related EF_IIDF's
        //are located at MF_SIM + DF_TELECOM + DF_GRAPHICS, same path as EF_IMG.
        mCi.iccIOForApp(COMMAND_GET_RESPONSE, fileid, getEFPath(EF_IMG), 0, 0,
                GET_RESPONSE_EF_IMG_SIZE_BYTES, null, null,
                mAid, response);
    }

    @Override
    protected String getEFPath(int efid) {
        switch(efid) {
        case EF_SMS:
        case EF_CST:
        case EF_RUIM_SPN:
        case EF_RUIM_ID:
        case EF_CSIM_LI:
        case EF_CSIM_MDN:
        case EF_CSIM_IMSIM:
        case EF_CSIM_CDMAHOME:
        case EF_CSIM_EPRL:
        case EF_CSIM_MMSConfig:
        case EF_CSIM_SMSCAP:
        case EF_CSIM_MODEL:
        case EF_MMS_ICP:
        case EF_MMSConfig:
        case EF_LBS_V2_PDE_ADDR:
        case EF_LBS_V2_MPC_ADDR:
        case EF_SSFC:
        case EF_Model:
        case EF_SMSCAP:
        case EF_ECC:
            return MF_SIM + DF_CDMA;
        }
        return getCommonIccEFPath(efid);
    }

    @Override
    protected void logd(String msg) {
        Rlog.d(LOG_TAG, "[RuimFileHandler] " + msg);
    }

    @Override
    protected void loge(String msg) {
        Rlog.e(LOG_TAG, "[RuimFileHandler] " + msg);
    }

}
