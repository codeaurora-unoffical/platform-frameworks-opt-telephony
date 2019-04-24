/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.telephony;

import android.annotation.NonNull;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.IccRecords.CarrierNameDisplayConditionBitmask;
import com.android.internal.telephony.uicc.IccRecords.OperatorPlmnInfo;
import com.android.internal.telephony.uicc.IccRecords.PlmnNetworkName;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Use EF filed from various source according to the priority to resolve the service provider name
 * and PLMN network name.
 */
public class CarrierDisplayNameResolverImpl implements CarrierDisplayNameResolver {
    private static final boolean DBG = true;
    private static final String TAG = "CDNRImpl";

    /**
     * Only display SPN in home network, and PLMN network name in roaming network.
     */
    @CarrierNameDisplayConditionBitmask
    private static final int DEFAULT_CARRIER_NAME_DISPLAY_CONDITION_BITMASK = 0;

    private final SparseArray<String> mServiceProviderNames = new SparseArray<>();
    private final SparseArray<List<String>> mSpdi = new SparseArray<>();
    private final SparseArray<CarrierDisplayNameConditionRule> mCarrierNameDisplayConditionRules =
            new SparseArray<>();
    private final SparseArray<List<PlmnNetworkName>> mPlmnNetworkNames = new SparseArray<>();
    private final SparseArray<List<OperatorPlmnInfo>> mOperatorPlmns = new SparseArray<>();
    private final SparseArray<List<String>> mEhplmns = new SparseArray<>();

    private ServiceState mServiceState;

    /**
     * The priority of ef source. Lower index means higher priority.
     *
     * {@link CarrierDisplayNameResolver#EF_SOURCE_DEFAULT} should always be the lowest priority
     * source.
     */
    private static final List<Integer> EF_SOURCE_PRIORITY =
            Arrays.asList(
                    EF_SOURCE_CARRIER_API,
                    EF_SOURCE_CARRIER_CONFIG,
                    EF_SOURCE_USIM,
                    EF_SOURCE_SIM,
                    EF_SOURCE_CSIM,
                    EF_SOURCE_RUIM,
                    EF_SOURCE_VOICE_OPERATOR_SIGNALLING,
                    EF_SOURCE_DATA_OPERATOR_SIGNALLING,
                    EF_SOURCE_MODEM_CONFIG,
                    EF_SOURCE_DEFAULT);

    public CarrierDisplayNameResolverImpl() {
        int defaultSourcePriority = getSourcePriority(EF_SOURCE_DEFAULT);
        mServiceProviderNames.put(defaultSourcePriority, "");
        mSpdi.put(defaultSourcePriority, Collections.EMPTY_LIST);
        mCarrierNameDisplayConditionRules.put(defaultSourcePriority,
                new CarrierDisplayNameConditionRule(
                        DEFAULT_CARRIER_NAME_DISPLAY_CONDITION_BITMASK));
        mPlmnNetworkNames.put(defaultSourcePriority, Collections.EMPTY_LIST);
        mOperatorPlmns.put(defaultSourcePriority, Collections.EMPTY_LIST);
        mEhplmns.put(defaultSourcePriority, Collections.EMPTY_LIST);
    }

    @Override
    public void updateServiceProviderName(@EFSource int source, @NonNull String spn) {
        mServiceProviderNames.put(getSourcePriority(source), spn);
        resolveCarrierDisplayName();
    }

    @Override
    public void updateServiceProviderDisplayInformation(
            @EFSource int source, @NonNull List<String> spdi) {
        mSpdi.put(getSourcePriority(source), spdi);
        resolveCarrierDisplayName();
    }

    @Override
    public void updateServiceProviderNameDisplayCondition(
            @EFSource int source, int condition) {
        mCarrierNameDisplayConditionRules.put(getSourcePriority(source),
                new CarrierDisplayNameConditionRule(condition));
        resolveCarrierDisplayName();
    }

    @Override
    public void updatePlmnNetworkNameList(
            @EFSource int source, @NonNull List<PlmnNetworkName> pnnList) {
        mPlmnNetworkNames.put(getSourcePriority(source), pnnList);
        resolveCarrierDisplayName();
    }

    @Override
    public void updateEhplmnList(@EFSource int source, List<String> ehplmns) {
        mEhplmns.put(getSourcePriority(source), ehplmns);
    }

    @Override
    public void updateServiceState(ServiceState serviceState) {
        mServiceState = serviceState;
        resolveCarrierDisplayName();
    }

    @Override
    public void updateOperatorPlmnList(
            @EFSource int source, @NonNull List<OperatorPlmnInfo> opl) {
        mOperatorPlmns.put(getSourcePriority(source), opl);
        resolveCarrierDisplayName();
    }

    @Override
    public void updateHomePlmnNumeric(@NonNull String homePlmnNumeric) {
        mHomePlmn = homePlmnNumeric;
        resolveCarrierDisplayName();
    }

    @Override
    public boolean shouldShowPlmnNetworkName() {
        return mShouldShowPlmnNetworkName;
    }

    @Override
    public boolean shouldShowServiceProviderName() {
        return mShouldShowServiceProviderName;
    }

    @Override
    public String getPlmnNetworkName() {
        return mPlmnNetworkName;
    }

    @Override
    public String getServiceProviderName() {
        return mServiceProviderName;
    }

    private boolean mShouldShowServiceProviderName;
    private boolean mShouldShowPlmnNetworkName;
    private String mServiceProviderName;
    private String mPlmnNetworkName;
    private String mHomePlmn;

    private void resolveCarrierDisplayName() {
        if (mServiceState == null) return;

        CarrierDisplayNameConditionRule displayRule =
                mCarrierNameDisplayConditionRules.valueAt(0);

        String registeredPlmnNumeric = mServiceState.getOperatorNumeric();
        List<String> efSpdi = mSpdi.valueAt(0);

        // Currently use the roaming state from ServiceState.
        // EF_SPDI is only used when determine the service provider name and PLMN network name
        // display condition rule.
        boolean isRoaming = mServiceState.getRoaming() && !efSpdi.contains(registeredPlmnNumeric);
        mShouldShowServiceProviderName = displayRule.shouldShowSpn(isRoaming);
        mShouldShowPlmnNetworkName = displayRule.shouldShowPnn(isRoaming);

        mServiceProviderName = mServiceProviderNames.valueAt(0);

        // Resolve the PLMN network name
        mPlmnNetworkName = "";
        List<OperatorPlmnInfo> efOpl = mOperatorPlmns.valueAt(0);
        List<PlmnNetworkName> efPnn = mPlmnNetworkNames.valueAt(0);

        if (efOpl.isEmpty()) {
            // If the EF_OPL is not present, then the first record in EF_PNN is used for the
            // default network name when registered in the HPLMN or an EHPLMN(if the EHPLMN list
            // is present).
            mPlmnNetworkName = efPnn.isEmpty() ? "" : getPlmnNetworkName(efPnn.get(0));
        } else {
            // TODO: Check the TAC/LAC & registered PLMN numeric in OPL list to determine which
            // PLMN name should be used to override the current one.
        }

        // If no PLMN override is present, then the PLMN should be displayed numerically.
        if (mPlmnNetworkName.isEmpty()) {
            mPlmnNetworkName = registeredPlmnNumeric;
        }

        if (DBG) {
            Rlog.d(TAG, "spnDisplayCondition = " + displayRule
                    + " ,isRoaming = " + isRoaming
                    + " ,registeredPLMN = " + registeredPlmnNumeric
                    + " ,homePLMN = " + mHomePlmn
                    + " ,spnList = " + mServiceProviderNames
                    + " ,spnCondition " + mCarrierNameDisplayConditionRules
                    + " ,spdiList = " + mSpdi
                    + " ,pnnList = " + mPlmnNetworkNames
                    + " ,oplList = " + mOperatorPlmns
                    + " ,ehplmn = " + mEhplmns);
        }
    }

    /**
     * Get the PLMN network name from the {@link PlmnNetworkName} object.
     * @param name the {@link PlmnNetworkName} object may contain the full and short version of PLMN
     * network name.
     * @return full/short version PLMN network name if one of those is existed, otherwise return an
     * empty string.
     */
    private static String getPlmnNetworkName(PlmnNetworkName name) {
        if (name == null) return "";
        if (!TextUtils.isEmpty(name.fullName)) return name.fullName;
        if (!TextUtils.isEmpty(name.shortName)) return name.shortName;
        return "";
    }

    /**
     * Get the priority of the source of ef object. If {@code source} is not in the priority list,
     * return {@link Integer#MAX_VALUE}.
     * @param source source of ef object.
     * @return the priority of the source of ef object.
     */
    private static int getSourcePriority(@EFSource int source) {
        int priority = EF_SOURCE_PRIORITY.indexOf(source);
        if (priority == -1) priority = Integer.MAX_VALUE;
        return priority;
    }

    private static final class CarrierDisplayNameConditionRule {
        private int mDisplayConditionBitmask;

        CarrierDisplayNameConditionRule(int carrierDisplayConditionBitmask) {
            mDisplayConditionBitmask = carrierDisplayConditionBitmask;
        }

        boolean shouldShowSpn(boolean isRoaming) {
            return !isRoaming || ((mDisplayConditionBitmask
                    & IccRecords.CARRIER_NAME_DISPLAY_CONDITION_BITMASK_SPN)
                    == IccRecords.CARRIER_NAME_DISPLAY_CONDITION_BITMASK_SPN);
        }

        boolean shouldShowPnn(boolean isRoaming) {
            return isRoaming || ((mDisplayConditionBitmask
                    & IccRecords.CARRIER_NAME_DISPLAY_CONDITION_BITMASK_PLMN)
                    == IccRecords.CARRIER_NAME_DISPLAY_CONDITION_BITMASK_PLMN);
        }

        @Override
        public String toString() {
            return String.format("{ SPN_bit = %d, PLMN_bit = %d }",
                    mDisplayConditionBitmask
                            & IccRecords.CARRIER_NAME_DISPLAY_CONDITION_BITMASK_SPN,
                    mDisplayConditionBitmask
                            & IccRecords.CARRIER_NAME_DISPLAY_CONDITION_BITMASK_PLMN);
        }
    }
}
