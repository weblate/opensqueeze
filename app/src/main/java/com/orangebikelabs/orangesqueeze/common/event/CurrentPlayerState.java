/*
 * Copyright (c) 2020-2022 The OpenSqueeze Authors. All Rights Reserved.
 * Use of this source code is governed by the GPLv3 license that can be found in the LICENSE file.
 */

package com.orangebikelabs.orangesqueeze.common.event;

import androidx.annotation.Keep;

import com.google.common.base.MoreObjects;
import com.orangebikelabs.orangesqueeze.common.PlayerStatus;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @author tsandee
 */
@Keep
public class CurrentPlayerState {
    @Nullable
    final private PlayerStatus mNewStatus;

    @Nullable
    final private PlayerStatus mOldStatus;

    public CurrentPlayerState(@Nullable PlayerStatus status, @Nullable PlayerStatus oldStatus) {
        mNewStatus = status;
        mOldStatus = oldStatus;
    }

    @Nullable
    public PlayerStatus getPlayerStatus() {
        return mNewStatus;
    }

    @Nullable
    public PlayerStatus getPreviousPlayerStatus() {
        return mOldStatus;
    }

    @Override
    @Nonnull
    public String toString() {
        return MoreObjects.toStringHelper(this).add("playerStatus", mNewStatus).toString();
    }
}
