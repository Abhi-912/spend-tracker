package com.spendtracker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.spendtracker.data.AppDatabase
import com.spendtracker.data.Transaction
import com.spendtracker.notifications.PaymentNotificationService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var permsState by mutableStateOf(0) // bump to recompose after grant

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permsState++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    TrackerScreen(
                        permsBump = permsState,
                        onRequestSms = ::requestSmsPermissions,
                        onOpenNotifAccess = ::openNotificationAccess
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permsState++ // refresh notification-access status
    }

    private fun requestSmsPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())
    }

    private fun openNotificationAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

fun hasSmsPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

fun hasNotificationAccess(ctx: Context): Boolean {
    val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
    val me = ComponentName(ctx, PaymentNotificationService::class.java).flattenToString()
    return flat.contains(me, ignoreCase = true)
}

fun monthStartMillis(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

@Composable
fun TrackerScreen(permsBump: Int, onRequestSms: () -> Unit, onOpenNotifAccess: () -> Unit) {
    val ctx = LocalContext.current
    remember(permsBump) { 0 } // subscribe to refreshes
    val smsOk = remember(permsBump) { hasSmsPermission(ctx) }
    val notifOk = remember(permsBump) { hasNotificationAccess(ctx) }

    val dao = remember { AppDatabase.get(ctx).transactionDao() }
    val txns by dao.observeAll().collectAsState(initial = emptyList())
    val monthStart = remember { monthStartMillis() }
    val monthSpend by dao.observeSpendSince(monthStart).collectAsState(initial = 0.0)
    val monthOwed by dao.observeOwedSince(monthStart).collectAsState(initial = 0.0)
    val monthLent by dao.observeLentSince(monthStart).collectAsState(initial = 0.0)
    val categories by dao.observeCategorySpendSince(monthStart).collectAsState(initial = emptyList())

    var showClearAll by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var editName by remember { mutableStateOf("") }
    val ioScope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("SpendTracker — on-device", style = MaterialTheme.typography.headlineSmall)
        Text("SMS + app notifications + Splitwise. No internet, no server.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Spent this month (actual payments)", style = MaterialTheme.typography.labelMedium)
                Text("₹ %.2f".format(monthSpend), style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Splitwise: you owe ₹ %.2f · owed to you ₹ %.2f".format(monthOwed, monthLent),
                    style = MaterialTheme.typography.bodySmall
                )
                Text("${txns.size} transactions stored", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (categories.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This month by category", style = MaterialTheme.typography.titleMedium)
                    categories.forEach { c -> CategoryRow(c.category, c.total, monthSpend) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (!smsOk || !notifOk) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Setup needed", style = MaterialTheme.typography.titleMedium)
                    if (!smsOk) Text("• SMS permission: to read bank/UPI debit alerts")
                    if (!notifOk) Text("• Notification access: to read GPay/PhonePe/Paytm + Splitwise")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!smsOk) Button(onClick = onRequestSms) { Text("Grant SMS") }
                        if (!notifOk) OutlinedButton(onClick = onOpenNotifAccess) { Text("Enable access") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Transactions", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showClearAll = true }, enabled = txns.isNotEmpty()) {
                Text("Clear all")
            }
        }
        if (showClearAll) {
            AlertDialog(
                onDismissRequest = { showClearAll = false },
                title = { Text("Delete all transactions?") },
                text = { Text("This removes all ${txns.size} stored transactions. Cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        showClearAll = false
                        ioScope.launch(Dispatchers.IO) { dao.clearAll() }
                    }) { Text("Delete all") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAll = false }) { Text("Cancel") }
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(txns) { txn ->
                TxnRow(
                    txn,
                    onDelete = { ioScope.launch(Dispatchers.IO) { dao.delete(it) } },
                    onEdit = { editing = it; editName = it.merchant }
                )
            }
        }
        editing?.let { e ->
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("Correct name") },
                text = {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Merchant name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = editName.trim().take(40)
                        if (name.isNotEmpty()) {
                            ioScope.launch(Dispatchers.IO) { dao.update(e.copy(merchant = name)) }
                        }
                        editing = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
fun CategoryRow(category: String, total: Double, monthSpend: Double) {
    val frac = if (monthSpend > 0) (total / monthSpend).toFloat().coerceIn(0f, 1f) else 0f
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(category, style = MaterialTheme.typography.bodyMedium)
            Text(
                "₹ %.2f · %d%%".format(total, (frac * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun TxnRow(t: Transaction, onDelete: (Transaction) -> Unit, onEdit: (Transaction) -> Unit) {
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    val amountText = when (t.type) {
        "DEBIT" -> "- ₹%.2f".format(t.amount)
        "CREDIT" -> "+ ₹%.2f".format(t.amount)
        "OWED" -> "owe ₹%.2f".format(t.amount)
        "LENT" -> "lent ₹%.2f".format(t.amount)
        else -> "₹%.2f".format(t.amount)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    t.merchant,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(amountText, style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { onEdit(t) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Correct name",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { onDelete(t) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete transaction",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${t.category} · ${t.source} · ${t.type}", style = MaterialTheme.typography.bodySmall)
                Text(fmt.format(Date(t.timestamp)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
