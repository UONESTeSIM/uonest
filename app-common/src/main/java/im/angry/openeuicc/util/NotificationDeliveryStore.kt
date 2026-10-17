package im.angry.openeuicc.util

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import im.angry.openeuicc.core.EuiccChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.typeblog.lpac_jni.LocalProfileNotification
import org.json.JSONArray
import java.security.MessageDigest

private val Context.notificationDeliveryDataStore by preferencesDataStore(name = "notification_delivery")

/** Last observed result, shared by automatic and manual sends. Missing means unconfirmed. */
class NotificationDeliveryStore(context: Context) {
    private val store = context.applicationContext.notificationDeliveryDataStore

    private fun key(eid: String, seId: EuiccChannel.SecureElementId, item: LocalProfileNotification) =
        booleanPreferencesKey(MessageDigest.getInstance("SHA-256").digest(
            JSONArray(listOf(eid, seId.id, item.seqNumber, item.iccid,
                item.profileManagementOperation.name, item.notificationAddress)).toString().toByteArray()
        ).encodeHex())

    suspend fun results(channel: EuiccChannel, items: List<LocalProfileNotification>): Map<Long, Boolean?> {
        val eid = channel.lpa.eID
        if (eid.isBlank()) return items.associate { it.seqNumber to null }
        val preferences = store.data.first()
        return items.associate { it.seqNumber to preferences[key(eid, channel.seId, it)] }
    }

    suspend fun send(channel: EuiccChannel, item: LocalProfileNotification): Boolean {
        val eid = channel.lpa.eID
        check(eid.isNotBlank()) { "Cannot identify notification chip" }
        val key = key(eid, channel.seId, item)
        // If the process stops mid-request, never leave an old success as the latest result.
        store.edit { it.remove(key) }
        val delivered = try {
            channel.lpa.handleNotification(item.seqNumber)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.w("NotificationDelivery", "Notification send failed", e)
            false
        }
        // Persist a completed request even when the initiating screen is being closed.
        withContext(NonCancellable) {
            store.edit { it[key] = delivered }
            if (delivered) removeDelivered(channel, listOf(item))
        }
        return delivered
    }

    /** Only confirmed successes may be removed; failed cleanup is retried on the next load. */
    suspend fun removeDelivered(channel: EuiccChannel, items: List<LocalProfileNotification>): List<LocalProfileNotification> {
        val results = results(channel, items)
        return items.filter { item ->
            if (results[item.seqNumber] != true) return@filter true
            val deleted = try {
                channel.lpa.deleteNotification(item.seqNumber)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.w("NotificationDelivery", "Delivered notification cleanup failed", e)
                false
            }
            if (deleted) remove(channel, item)
            !deleted
        }
    }

    suspend fun remove(channel: EuiccChannel, item: LocalProfileNotification) {
        val eid = channel.lpa.eID
        if (eid.isNotBlank()) store.edit { it.remove(key(eid, channel.seId, item)) }
    }
}
