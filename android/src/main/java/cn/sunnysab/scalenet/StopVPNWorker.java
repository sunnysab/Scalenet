// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package cn.sunnysab.scalenet;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * A worker that exists to support IPNReceiver.
 */
public final class StopVPNWorker extends Worker {

    public StopVPNWorker(
            Context appContext,
            WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        UninitializedApp.get().stopVPN();
        return Result.success();
    }
}
