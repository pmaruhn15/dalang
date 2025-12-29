package de.dalang.nav

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Activity die bei einem Crash angezeigt wird
 * Zeigt den Fehler an damit der User ihn kopieren kann
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val errorMessage = intent.getStringExtra("error_message") ?: "Unbekannt"
        val errorClass = intent.getStringExtra("error_class") ?: "Exception"
        val stackTrace = intent.getStringExtra("stack_trace") ?: ""

        setContent {
            CrashScreen(
                errorClass = errorClass,
                errorMessage = errorMessage,
                stackTrace = stackTrace,
                onRestart = {
                    // App neu starten
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(intent)
                    finish()
                },
                onClose = {
                    finishAffinity()
                }
            )
        }
    }
}

@Composable
fun CrashScreen(
    errorClass: String,
    errorMessage: String,
    stackTrace: String,
    onRestart: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val fullError = "[$errorClass] $errorMessage\n\n$stackTrace"

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF1A1A1A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "CRASH",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF6B6B)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "App ist abgestürzt",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = errorClass,
                fontSize = 16.sp,
                color = Color(0xFFFF6B6B),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = errorMessage,
                fontSize = 14.sp,
                color = Color(0xFFCCCCCC)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Stack Trace Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = stackTrace.take(3000), // Limit für Performance
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color(0xFFAAAAAA),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    lineHeight = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Kopieren Button
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Crash Log", fullError))
                    Toast.makeText(context, "Kopiert!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White
                )
            ) {
                Text("Fehler kopieren")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Neu starten Button
            Button(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1976D2)
                )
            ) {
                Text("App neu starten")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Schließen Button
            TextButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Schließen",
                    color = Color(0xFF888888)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
