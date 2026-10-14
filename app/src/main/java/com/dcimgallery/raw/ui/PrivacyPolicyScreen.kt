package com.dcimgallery.raw.ui

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dcimgallery.raw.BuildConfig

val LocalOpenPrivacyPolicy = staticCompositionLocalOf<() -> Unit> { {} }

/** Finalized policies gate AppRoot/ViewModel creation; draft builds keep testing usable. */
@Composable
fun PrivacyGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("privacy-policy", Context.MODE_PRIVATE) }
    var accepted by remember { mutableStateOf(
        !BuildConfig.PRIVACY_POLICY_READY || preferences.getString("accepted-version", null) == BuildConfig.PRIVACY_POLICY_VERSION
    ) }
    var opened by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (accepted) {
            CompositionLocalProvider(LocalOpenPrivacyPolicy provides { opened = true }) { content() }
        }
        if (!accepted || opened) {
            PrivacyPolicyScreen(
                requestingConsent = !accepted,
                onClose = { opened = false },
                onAccept = {
                    preferences.edit().putString("accepted-version", BuildConfig.PRIVACY_POLICY_VERSION).apply()
                    accepted = true
                },
                onReject = { (context as? Activity)?.finish() },
            )
        }
    }
}

@Composable
private fun PrivacyPolicyScreen(requestingConsent: Boolean, onClose: () -> Unit, onAccept: () -> Unit, onReject: () -> Unit) {
    val context = LocalContext.current
    val text = remember(context) { context.assets.open("privacy-policy.txt").bufferedReader().use { it.readText() } }
    BackHandler { if (requestingConsent) onReject() else onClose() }
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!requestingConsent) StudioIconButton(StudioSymbol.ArrowLeft, "返回", onClose)
            Text("隐私政策", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = StudioColors.Ink)
            Text("若图", style = MaterialTheme.typography.labelMedium, color = StudioColors.Muted)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            if (!BuildConfig.PRIVACY_POLICY_READY) Text(
                "政策尚未定稿，仅供开发者检查，不能用于商店送审。",
                color = StudioColors.Pine, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text(text, color = StudioColors.Ink, style = MaterialTheme.typography.bodyMedium)
        }
        if (requestingConsent) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PolicyAction("不同意并退出", onReject, Modifier.weight(1f), false)
                PolicyAction("同意并继续", onAccept, Modifier.weight(1f), true)
            }
        }
    }
}

@Composable
private fun PolicyAction(label: String, onClick: () -> Unit, modifier: Modifier, primary: Boolean) {
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .background(if (primary) StudioColors.Pine else StudioColors.Tint).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) Color.White else StudioColors.Ink, style = MaterialTheme.typography.labelLarge)
    }
}
