package com.jdkshen.aijspro.ui.user

import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.jdkshen.aijspro.R
import com.jdkshen.aijspro.network.NodeBB
import com.jdkshen.aijspro.network.UserService
import com.jdkshen.aijspro.theme.AijsMiuixTheme
import com.jdkshen.aijspro.theme.MiuixBackButton
import com.jdkshen.aijspro.theme.isAijsDarkTheme
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Miuix register page (pilot). Reuses UserService/NodeBB exactly like RegisterActivity.
 */
class MiuixRegisterActivity : ComponentActivity() {

    private var email by mutableStateOf(TextFieldValue(""))
    private var username by mutableStateOf(TextFieldValue(""))
    private var password by mutableStateOf(TextFieldValue(""))
    private var errorText by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val dark = isAijsDarkTheme()
                AijsMiuixTheme {
                    val background = MiuixTheme.colorScheme.background
                    SideEffect {
                        window.statusBarColor = background.toArgb()
                        window.navigationBarColor = background.toArgb()
                        WindowInsetsControllerCompat(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    RegisterPage()
                }
            }
        })
    }

    private fun doRegister() {
        val mail = email.text.trim()
        val name = username.text.trim()
        val pwd = password.text
        if (mail.isEmpty()) {
            errorText = getString(R.string.text_email_cannot_be_empty)
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(mail).matches()) {
            errorText = getString(R.string.text_email_format_error)
            return
        }
        if (name.isEmpty()) {
            errorText = getString(R.string.text_username_cannot_be_empty)
            return
        }
        if (pwd.isEmpty()) {
            errorText = getString(R.string.text_password_cannot_be_empty)
            return
        }
        if (pwd.length < 6) {
            errorText = getString(R.string.nodebb_error_change_password_error_length)
            return
        }
        errorText = null
        loading = true
        val dialog = MaterialDialog.Builder(this)
            .progress(true, 0)
            .content(R.string.text_registering)
            .cancelable(false)
            .show()
        UserService.getInstance().register(mail, name, pwd.toString())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ response ->
                dialog.dismiss()
                loading = false
                Toast.makeText(this, R.string.text_register_succeed, Toast.LENGTH_SHORT).show()
                finish()
            }, { error ->
                dialog.dismiss()
                loading = false
                errorText = NodeBB.getErrorMessage(error, this, R.string.text_register_fail)?.toString()
            })
    }

    @Composable
    private fun RegisterPage() {
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            SmallTopAppBar(title = getString(R.string.text_register), defaultWindowInsetsPadding = false,
                navigationIcon = { MiuixBackButton(onClick = { finish() }) })
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = getString(R.string.text_email),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                TextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = getString(R.string.text_username),
                    singleLine = true
                )
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = getString(R.string.text_password),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                errorText?.let {
                    Text(it, fontSize = 13.sp, color = Color(0xFFD32F2F),
                        modifier = Modifier.padding(start = 4.dp))
                }
                Button(onClick = { if (!loading) doRegister() }, modifier = Modifier.fillMaxWidth(),
                    enabled = !loading, colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (loading) getString(R.string.text_registering) else getString(R.string.text_register))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
