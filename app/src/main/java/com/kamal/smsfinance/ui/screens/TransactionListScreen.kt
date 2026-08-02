package com.kamal.smsfinance.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kamal.smsfinance.data.Category
import com.kamal.smsfinance.data.Transaction
import com.kamal.smsfinance.data.TransactionType
import com.kamal.smsfinance.ui.theme.GreenIncome
import com.kamal.smsfinance.ui.theme.RedExpense
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    transactions: List<Transaction>,
    categories: List<Category>,
    recurringIds: Set<Long>,
    isLoading: Boolean,
    onScanInbox: () -> Unit,
    onDelete: (Transaction) -> Unit,
    onAddManual: () -> Unit,
    onAssignCategory: (Transaction, Long?) -> Unit,
    onCreateRule: (pattern: String, categoryId: Long?) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var categoryPickerFor by remember { mutableStateOf<Transaction?>(null) }
    var ruleSuggestionFor by remember { mutableStateOf<Pair<Transaction, Long>?>(null) }

    val filtered = remember(transactions, query) {
        if (query.isBlank()) transactions
        else transactions.filter {
            it.description.contains(query, true) || it.bankName.contains(query, true)
        }
    }
    val categoryById = remember(categories) { categories.associateBy { it.id } }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddManual) {
                Icon(Icons.Filled.Add, contentDescription = "افزودن دستی")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("جستجو در تراکنش‌ها...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )

            OutlinedButton(
                onClick = onScanInbox,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("در حال اسکن...")
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("اسکن مجدد پیامک‌های بانکی")
                }
            }

            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { txn ->
                        TransactionCard(
                            txn = txn,
                            categoryName = categoryById[txn.categoryId]?.name,
                            isRecurring = txn.id in recurringIds,
                            onDelete = { onDelete(txn) },
                            onCategoryClick = { categoryPickerFor = txn }
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }

    categoryPickerFor?.let { txn ->
        CategoryPickerDialog(
            categories = categories,
            onDismiss = { categoryPickerFor = null },
            onSelect = { categoryId ->
                onAssignCategory(txn, categoryId)
                categoryPickerFor = null
                if (categoryId != null) ruleSuggestionFor = txn to categoryId
            }
        )
    }

    ruleSuggestionFor?.let { (txn, categoryId) ->
        val categoryName = categories.firstOrNull { it.id == categoryId }?.name.orEmpty()
        RuleSuggestionDialog(
            defaultPattern = txn.description.ifBlank { txn.bankName },
            categoryName = categoryName,
            onSkip = { ruleSuggestionFor = null },
            onSave = { pattern ->
                onCreateRule(pattern, categoryId)
                ruleSuggestionFor = null
            }
        )
    }
}

@Composable
private fun RuleSuggestionDialog(
    defaultPattern: String,
    categoryName: String,
    onSkip: () -> Unit,
    onSave: (String) -> Unit
) {
    var pattern by remember { mutableStateOf(defaultPattern) }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("تبدیل به قانون؟") },
        text = {
            Column {
                Text(
                    "پیامک‌های مشابه بعدی که شامل این عبارت باشند، خودکار در دسته «$categoryName» قرار می‌گیرند.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("عبارت تشخیص") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pattern.isNotBlank()) onSave(pattern) }, enabled = pattern.isNotBlank()) {
                Text("ذخیره قانون")
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("فقط همین یکی") }
        }
    )
}

@Composable
private fun CategoryPickerDialog(categories: List<Category>, onDismiss: () -> Unit, onSelect: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("انتخاب دسته‌بندی") },
        text = {
            Column {
                TextButton(onClick = { onSelect(null) }) { Text("بدون دسته") }
                categories.forEach { cat ->
                    TextButton(onClick = { onSelect(cat.id) }) { Text(cat.name) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        }
    )
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.ReceiptLong,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "هنوز تراکنشی ثبت نشده است.\nروی «اسکن مجدد پیامک‌های بانکی» بزنید یا یک تراکنش دستی اضافه کنید.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TransactionCard(
    txn: Transaction,
    categoryName: String?,
    isRecurring: Boolean,
    onDelete: () -> Unit,
    onCategoryClick: () -> Unit
) {
    val isIncome = txn.type == TransactionType.INCOME
    val amountColor = if (isIncome) GreenIncome else RedExpense
    val dateStr = remember(txn.date) {
        SimpleDateFormat("yyyy/MM/dd - HH:mm", Locale.US).format(Date(txn.date))
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isIncome) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                contentDescription = null,
                tint = amountColor,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(txn.bankName, style = MaterialTheme.typography.titleMedium)
                Text(
                    txn.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dateStr, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
                    if (isRecurring) {
                        Spacer(Modifier.width(6.dp))
                        AssistChip(onClick = {}, label = { Text("تکراری", style = MaterialTheme.typography.labelLarge) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                AssistChip(
                    onClick = onCategoryClick,
                    label = { Text(categoryName ?: "بدون دسته", style = MaterialTheme.typography.labelLarge) }
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${"%,d".format(txn.amountToman)} ت",
                    color = amountColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "حذف", tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
