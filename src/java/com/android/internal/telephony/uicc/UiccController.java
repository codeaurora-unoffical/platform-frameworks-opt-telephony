/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.Rlog;

import android.telephony.ServiceState;

import com.android.internal.telephony.CommandsInterface;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * This class is responsible for keeping all knowledge about
 * Universal Integrated Circuit Card (UICC), also know as SIM's,
 * in the system. It is also used as API to get appropriate
 * applications to pass them to phone and service trackers.
 *
 * UiccController is created with the call to make() function.
 * UiccController is a singleton and make() must only be called once
 * and throws an exception if called multiple times.
 *
 * Once created UiccController registers with RIL for "on" and "unsol_sim_status_changed"
 * notifications. When such notification arrives UiccController will call
 * getIccCardStatus (GET_SIM_STATUS). Based on the response of GET_SIM_STATUS
 * request appropriate tree of uicc objects will be created.
 *
 * Following is class diagram for uicc classes:
 *
 *                       UiccController
 *                            #
 *                            |
 *                        UiccCard
 *                          #   #
 *                          |   ------------------
 *                    UiccCardApplication    CatService
 *                      #            #
 *                      |            |
 *                 IccRecords    IccFileHandler
 *                 ^ ^ ^           ^ ^ ^ ^ ^
 *    SIMRecords---- | |           | | | | ---SIMFileHandler
 *    RuimRecords----- |           | | | ----RuimFileHandler
 *    IsimUiccRecords---           | | -----UsimFileHandler
 *                                 | ------CsimFileHandler
 *                                 ----IsimFileHandler
 *
 * Legend: # stands for Composition
 *         ^ stands for Generalization
 *
 * See also {@link com.android.internal.telephony.IccCard}
 * and {@link com.android.internal.telephony.uicc.IccCardProxy}
 */
public class UiccController extends Handler {
    protected static final boolean DBG = true;
    protected static final String LOG_TAG = "UiccController";

    public static final int APP_FAM_UNKNOWN =  -1;
    public static final int APP_FAM_3GPP =  1;
    public static final int APP_FAM_3GPP2 = 2;
    public static final int APP_FAM_IMS   = 3;

    protected static final int EVENT_ICC_STATUS_CHANGED = 1;
    protected static final int EVENT_GET_ICC_STATUS_DONE = 2;
    protected static final int EVENT_RADIO_UNAVAILABLE = 3;
    protected static final int EVENT_REFRESH = 4;
    protected static final int EVENT_REFRESH_OEM = 5;

    protected static final Object mLock = new Object();
    protected static UiccController mInstance;

    protected Context mContext;
    private CommandsInterface mCi;
    protected UiccCard mUiccCard;

    protected RegistrantList mIccChangedRegistrants = new RegistrantList();

    protected boolean mOEMHookSimRefresh = false;

    public static UiccController make(Context c, CommandsInterface ci) {
        synchronized (mLock) {
            if (mInstance != null) {
                throw new RuntimeException("UiccController.make() should only be called once");
            }
            mInstance = new UiccController(c, ci);
            return mInstance;
        }
    }

    public static UiccController getInstance() {
        synchronized (mLock) {
            if (mInstance == null) {
                throw new RuntimeException(
                        "UiccController.getInstance can't be called before make()");
            }
            return mInstance;
        }
    }

    public static void destroy() {
        synchronized (mLock) {
            if (mInstance == null) {
                throw new RuntimeException(
                        "UiccController.destroy() should only be called after make()");
            }
            mInstance.mCi.unregisterForIccStatusChanged(mInstance);
            mInstance.mCi.unregisterForAvailable(mInstance);
            mInstance.mCi.unregisterForNotAvailable(mInstance);
            mInstance.mCi.unregisterForIccRefresh(mInstance);
            mInstance.mCi = null;
            mInstance.mContext = null;
            mInstance = null;
        }
    }

    public UiccCard getUiccCard() {
        synchronized (mLock) {
            return mUiccCard;
        }
    }

    // Easy to use API
    public UiccCardApplication getUiccCardApplication(int family) {
        synchronized (mLock) {
            if (mUiccCard != null) {
                return mUiccCard.getApplication(family);
            }
            return null;
        }
    }

    // Easy to use API
    public IccRecords getIccRecords(int family) {
        synchronized (mLock) {
            if (mUiccCard != null) {
                UiccCardApplication app = mUiccCard.getApplication(family);
                if (app != null) {
                    return app.getIccRecords();
                }
            }
            return null;
        }
    }

    // Easy to use API
    public IccFileHandler getIccFileHandler(int family) {
        synchronized (mLock) {
            if (mUiccCard != null) {
                UiccCardApplication app = mUiccCard.getApplication(family);
                if (app != null) {
                    return app.getIccFileHandler();
                }
            }
            return null;
        }
    }

    public static int getFamilyFromRadioTechnology(int radioTechnology) {
        if (ServiceState.isGsm(radioTechnology) ||
                radioTechnology == ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD) {
            return  UiccController.APP_FAM_3GPP;
        } else if (ServiceState.isCdma(radioTechnology)) {
            return  UiccController.APP_FAM_3GPP2;
        } else {
            // If it is UNKNOWN rat
            return UiccController.APP_FAM_UNKNOWN;
        }
    }

    //Notifies when card status changes
    public void registerForIccChanged(Handler h, int what, Object obj) {
        synchronized (mLock) {
            Registrant r = new Registrant (h, what, obj);
            mIccChangedRegistrants.add(r);
            //Notify registrant right after registering, so that it will get the latest ICC status,
            //otherwise which may not happen until there is an actual change in ICC status.
            r.notifyRegistrant();
        }
    }

    public void unregisterForIccChanged(Handler h) {
        synchronized (mLock) {
            mIccChangedRegistrants.remove(h);
        }
    }

    @Override
    public void handleMessage (Message msg) {
        synchronized (mLock) {
            switch (msg.what) {
                case EVENT_ICC_STATUS_CHANGED:
                    if (DBG) log("Received EVENT_ICC_STATUS_CHANGED, calling getIccCardStatus");
                    mCi.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
                    break;
                case EVENT_GET_ICC_STATUS_DONE:
                    if (DBG) log("Received EVENT_GET_ICC_STATUS_DONE");
                    AsyncResult ar = (AsyncResult)msg.obj;
                    onGetIccCardStatusDone(ar);
                    break;
                case EVENT_RADIO_UNAVAILABLE:
                    if (DBG) log("EVENT_RADIO_UNAVAILABLE ");
                    disposeCard(mUiccCard);
                    mUiccCard = null;
                    mIccChangedRegistrants.notifyRegistrants();
                    break;
                case EVENT_REFRESH:
                    if (DBG) log("Sim REFRESH received");
                    if (!mOEMHookSimRefresh) {
                        ar = (AsyncResult)msg.obj;
                        if (ar.exception == null) {
                            handleRefresh((IccRefreshResponse)ar.result);
                        } else {
                            log ("Exception on refresh " + ar.exception);
                        }
                    }
                    break;
                case EVENT_REFRESH_OEM:
                    if (DBG) log("Sim REFRESH OEM received");
                    if (mOEMHookSimRefresh) {
                        ar = (AsyncResult)msg.obj;
                        if (ar.exception == null) {
                            ByteBuffer payload = ByteBuffer.wrap((byte[])ar.result);
                            handleRefresh(parseOemSimRefresh(payload));
                        } else {
                            log ("Exception on refresh " + ar.exception);
                        }
                    }
                    break;
                default:
                    Rlog.e(LOG_TAG, " Unknown Event " + msg.what);
            }
        }
    }

    // Destroys the card object
    protected synchronized void disposeCard(UiccCard uiccCard) {
        if (DBG) log("Disposing card");
        if (uiccCard != null) {
            uiccCard.dispose();
        }
    }

    private void handleRefresh(IccRefreshResponse refreshResponse){
        if (refreshResponse == null) {
            log("handleRefresh received without input");
            return;
        }

        // Let the card know right away that a refresh has occurred
        if (mUiccCard != null) {
            mUiccCard.onRefresh(refreshResponse);
        }
        // The card status could have changed. Get the latest state
        mCi.getIccCardStatus(obtainMessage(EVENT_GET_ICC_STATUS_DONE));
    }

    public static IccRefreshResponse
    parseOemSimRefresh(ByteBuffer payload) {
        IccRefreshResponse response = new IccRefreshResponse();
        /* AID maximum size */
        final int QHOOK_MAX_AID_SIZE = 20*2+1+3;

        /* parse from payload */
        payload.order(ByteOrder.nativeOrder());

        response.refreshResult = payload.getInt();
        response.efId  = payload.getInt();
        int aidLen = payload.getInt();
        byte[] aid = new byte[QHOOK_MAX_AID_SIZE];
        payload.get(aid, 0, QHOOK_MAX_AID_SIZE);
        response.aid = (aidLen == 0) ? null : new String(aid);

        if (DBG){
            Rlog.d(LOG_TAG, "refresh SIM card " + ", refresh result:" + response.refreshResult
                    + ", ef Id:" + response.efId + ", aid:" + response.aid);
        }
        return response;
    }

    private UiccController(Context c, CommandsInterface ci) {
        if (DBG) log("Creating UiccController");
        mContext = c;
        mCi = ci;
        mCi.registerForIccStatusChanged(this, EVENT_ICC_STATUS_CHANGED, null);
        mCi.registerForAvailable(this, EVENT_ICC_STATUS_CHANGED, null);
        mCi.registerForNotAvailable(this, EVENT_RADIO_UNAVAILABLE, null);
        mOEMHookSimRefresh = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_sim_refresh_for_dual_mode_card);
        if (mOEMHookSimRefresh) {
            mCi.registerForSimRefreshEvent(this, EVENT_REFRESH_OEM, null);
        } else {
            mCi.registerForIccRefresh(this, EVENT_REFRESH, null);
        }
    }

    private synchronized void onGetIccCardStatusDone(AsyncResult ar) {
        if (ar.exception != null) {
            Rlog.e(LOG_TAG,"Error getting ICC status. "
                    + "RIL_REQUEST_GET_ICC_STATUS should "
                    + "never return an error", ar.exception);
            return;
        }

        IccCardStatus status = (IccCardStatus)ar.result;

        if (mUiccCard == null) {
            if (DBG) log("Creating a new card");
            mUiccCard = new UiccCard(mContext, mCi, status);
        } else {
            if (DBG) log("Update already existing card");
            mUiccCard.update(mContext, mCi , status);
        }

        if (DBG) log("Notifying IccChangedRegistrants");
        mIccChangedRegistrants.notifyRegistrants();
    }

    protected UiccController() {
    }

    private static void log(String string) {
        Rlog.d(LOG_TAG, string);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("UiccController: " + this);
        pw.println(" mContext=" + mContext);
        pw.println(" mInstance=" + mInstance);
        pw.println(" mCi=" + mCi);
        pw.println(" mUiccCard=" + mUiccCard);
        pw.println(" mIccChangedRegistrants: size=" + mIccChangedRegistrants.size());
        for (int i = 0; i < mIccChangedRegistrants.size(); i++) {
            pw.println("  mIccChangedRegistrants[" + i + "]="
                    + ((Registrant)mIccChangedRegistrants.get(i)).getHandler());
        }
        pw.println();
        pw.flush();
        if (mUiccCard != null) {
            mUiccCard.dump(fd, pw, args);
        }
    }
}
