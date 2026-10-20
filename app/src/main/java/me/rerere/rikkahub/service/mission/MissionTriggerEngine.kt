package me.rerere.rikkahub.service.mission

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.trigger.AppForegroundTriggerFamily
import me.rerere.rikkahub.workflow.trigger.BatteryTriggerFamily
import me.rerere.rikkahub.workflow.trigger.BluetoothTriggerFamily
import me.rerere.rikkahub.workflow.trigger.BootTriggerFamily
import me.rerere.rikkahub.workflow.trigger.GeofenceTriggerFamily
import me.rerere.rikkahub.workflow.trigger.GeofenceTriggerHelper
import me.rerere.rikkahub.workflow.trigger.HeadphoneTriggerFamily
import me.rerere.rikkahub.workflow.trigger.ManualTriggerFamily
import me.rerere.rikkahub.workflow.trigger.NotificationTriggerFamily
import me.rerere.rikkahub.workflow.trigger.PowerTriggerFamily
import me.rerere.rikkahub.workflow.trigger.ScreenTriggerFamily
import me.rerere.rikkahub.workflow.trigger.TimeCronTriggerFamily
import me.rerere.rikkahub.workflow.trigger.TriggerBinding
import me.rerere.rikkahub.workflow.trigger.TriggerFireCallback
import me.rerere.rikkahub.workflow.trigger.WifiTriggerFamily
import me.rerere.rikkahub.workflow.trigger.WorkflowBootDispatcher
import me.rerere.rikkahub.workflow.trigger.WorkflowTriggerFamily

/**
 * 批 B — the mission-owned instance of the (formerly workflow-owned) trigger families.
 *
 * Batch A retired the workflow *engine*; batch B reuses its trigger plumbing — the same
 * receivers, WorkManager time/cron backend and geofence registrations — but everything is
 * keyed by mission id and resolves through [resolver] straight to mission rows.
 *
 * Only one engine should exist per process: families are stateful (registered receivers,
 * WorkManager unique work names, last-known battery/Wi-Fi values). [MissionScheduler] owns
 * the single instance.
 */
internal class MissionTriggerEngine(
    context: Context,
    scope: CoroutineScope,
    /** mission id → binding, only for armed missions. Used by time/geofence cold starts. */
    private val resolver: suspend (String) -> TriggerBinding?,
    /** all armed bindings, for the boot cold-start path. */
    private val listBindings: suspend () -> List<TriggerBinding>,
    /** The scheduler's fire callback; available even before the mission flow emits. */
    private val callback: TriggerFireCallback,
) {

    @Volatile private var fireCallback: TriggerFireCallback? = callback

    private val wifi = WifiTriggerFamily(context, scope)
    private val bluetooth = BluetoothTriggerFamily(context, scope)
    private val headphones = HeadphoneTriggerFamily(context, scope)
    private val power = PowerTriggerFamily(context, scope)
    private val screen = ScreenTriggerFamily(context, scope)
    private val battery = BatteryTriggerFamily(context, scope)
    private val timeCron = TimeCronTriggerFamily(context, scope, resolver, callback)
    private val geofence = GeofenceTriggerFamily(context, scope)
    private val appForeground = AppForegroundTriggerFamily(scope)
    private val notification = NotificationTriggerFamily(scope)
    private val boot = BootTriggerFamily(
        scope,
        resolver = {
            val cb = fireCallback
            if (cb == null) null else cb to listBindings()
        },
    )
    private val manual = ManualTriggerFamily()

    private val families: List<WorkflowTriggerFamily> = listOf(
        wifi, bluetooth, headphones, power, screen, battery, timeCron, geofence,
        appForeground, notification, boot, manual,
    )

    init {
        // Geofence broadcasts can cold-start the process; let the helper resolve missions.
        runCatching {
            GeofenceTriggerHelper.missionResolver = { id ->
                resolver(id)?.let { binding ->
                    fireCallback?.let { cb -> binding.trigger to cb }
                }
            }
        }
        WorkflowBootDispatcher.bind(boot)
    }

    /** Reconcile every family with the current armed set. */
    suspend fun sync(bindings: List<TriggerBinding>) {
        fireCallback = callback
        for (family in families) {
            val matching = bindings.filter { family.handles(it.trigger) }
            runCatching { family.sync(matching, callback) }
        }
    }

    /** WorkManager woke us for a mission's time/cron schedule. */
    suspend fun onTimeCronFired(missionId: String) {
        timeCron.onWorkerFired(missionId)
    }

    suspend fun shutdown() {
        fireCallback = null
        for (family in families) runCatching { family.shutdown() }
    }
}