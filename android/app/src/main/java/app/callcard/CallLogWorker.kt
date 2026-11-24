package app.callcard

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** 예비 확인 작업: 통화 종료 직후 처리에서 놓친 통화가 있으면 처리합니다. */
class CallLogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        synchronized(CallChecker) { CallChecker.check(applicationContext) }
        return Result.success()
    }
}
