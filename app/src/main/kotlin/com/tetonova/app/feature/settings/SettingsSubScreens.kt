package com.tetonova.app.feature.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.app.data.ReportApi
import com.tetonova.app.data.TnData
import com.tetonova.app.ui.PageScroll
import com.tetonova.app.ui.TnPrimaryButton
import com.tetonova.core.designsystem.TnCard
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import kotlinx.coroutines.launch

/** Shared "Kembali" header for the Settings sub-screens (Report / Catatan rilis / Pusat bantuan). */
@Composable
private fun SubScreenHeader(title: String, subtitle: String, icon: String, onBack: () -> Unit) {
    val c = TnTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(TnRadii.pill))
            .clickable { onBack() }.padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TnIcon("chevR", size = 16.dp, tint = c.ink2, modifier = Modifier.graphicsLayer(rotationZ = 180f))
        Text("Kembali", color = c.ink2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.clip(RoundedCornerShape(TnRadii.sm)).background(c.roseTint).padding(8.dp), contentAlignment = Alignment.Center) {
            TnIcon(icon, size = 20.dp, tint = c.rose)
        }
        Column {
            Text(title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
            Text(subtitle, color = c.muted, fontSize = 13.sp)
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** In-app "Lapor konten bermasalah" — collects a free-text description + optional contact. */
@Composable
fun ReportScreen(onBack: () -> Unit) {
    val c = TnTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val canSend = message.isNotBlank() && !sending

    PageScroll(topInset = true) {
        SubScreenHeader(
            title = "Lapor konten",
            subtitle = "Lapor judul / source yang error langsung dari sini.",
            icon = "flag",
            onBack = onBack,
        )
        TnCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Jelaskan masalahnya", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    placeholder = { Text("Mis. One Piece episode 1090 di AnimeSail gak bisa diputar.", color = c.muted) },
                    colors = tnFieldColors(),
                )
                Spacer(Modifier.height(16.dp))
                Text("Kontak (opsional)", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Email / username biar bisa dibalas", color = c.muted) },
                    colors = tnFieldColors(),
                )
                Spacer(Modifier.height(18.dp))
                TnPrimaryButton(
                    text = if (sending) "Mengirim…" else "Kirim laporan",
                    icon = "send",
                    modifier = Modifier.fillMaxWidth().then(if (canSend) Modifier else Modifier.alpha(0.5f)),
                    onClick = {
                        if (canSend) {
                            sending = true
                            scope.launch {
                                val res = ReportApi.submit(message.trim(), contact.trim())
                                sending = false
                                if (res.isSuccess) {
                                    Toast.makeText(ctx, "Laporan terkirim — makasih!", Toast.LENGTH_SHORT).show()
                                    onBack()
                                } else {
                                    Toast.makeText(ctx, "Gagal kirim, coba lagi nanti.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** Read-only changelog published by the panel (Settings → Catatan rilis). */
@Composable
fun ReleaseNotesScreen(onBack: () -> Unit) {
    val c = TnTheme.colors
    val notes = TnData.releaseNotes
    val body = if (notes?.enabled == true) notes.body.trim() else ""

    PageScroll(topInset = true) {
        SubScreenHeader(
            title = "Catatan rilis",
            subtitle = "Apa yang baru di TetoNova.",
            icon = "info",
            onBack = onBack,
        )
        TnCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (body.isBlank()) {
                    Text("Belum ada catatan rilis.", color = c.muted, fontSize = 14.sp)
                } else {
                    Text(body, color = c.ink2, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** Read-only help center published by the panel (Settings → Pusat bantuan). */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val c = TnTheme.colors
    val help = TnData.helpCenter
    val enabled = help?.enabled == true
    val faq = if (enabled) help!!.faq.trim() else ""
    val guide = if (enabled) help!!.sourceGuide.trim() else ""
    val contact = if (enabled) help!!.contact.trim() else ""
    val hasAny = faq.isNotBlank() || guide.isNotBlank() || contact.isNotBlank()

    PageScroll(topInset = true) {
        SubScreenHeader(
            title = "Pusat bantuan",
            subtitle = "FAQ, panduan source, dan kontak.",
            icon = "help",
            onBack = onBack,
        )
        if (!hasAny) {
            TnCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Butuh bantuan? Pakai \"Lapor konten bermasalah\" di Settings untuk lapor judul atau source yang error.",
                        color = c.muted, fontSize = 14.sp,
                    )
                }
            }
        } else {
            HelpSection("FAQ", faq)
            HelpSection("Panduan source", guide)
            HelpSection("Kontak", contact)
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun HelpSection(title: String, body: String) {
    if (body.isBlank()) return
    val c = TnTheme.colors
    Column(Modifier.padding(top = 14.dp)) {
        Text(title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        TnCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(body, color = c.ink2, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun tnFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TnTheme.colors.ink,
    unfocusedTextColor = TnTheme.colors.ink,
    cursorColor = TnTheme.colors.rose,
    focusedBorderColor = TnTheme.colors.rose,
    unfocusedBorderColor = TnTheme.colors.line,
    focusedContainerColor = TnTheme.colors.surface,
    unfocusedContainerColor = TnTheme.colors.surface,
)
