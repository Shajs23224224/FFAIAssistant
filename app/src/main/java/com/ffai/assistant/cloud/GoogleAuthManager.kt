package com.ffai.assistant.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.ffai.assistant.utils.Logger
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task

/**
 * Gestor de autenticación de Google para Drive API.
 * Maneja Sign-In/Sign-Out, tokens OAuth y persistencia de sesión.
 */
class GoogleAuthManager(private val context: Context) {

    companion object {
        const val RC_SIGN_IN = 9001
        const val PREFS_NAME = "google_auth_prefs"
        const val KEY_ACCOUNT_NAME = "account_name"
        const val KEY_ACCOUNT_ID = "account_id"
        
        // Scope para acceso a Drive (solo archivos creados por la app)
        val DRIVE_FILE_SCOPE = Scope("https://www.googleapis.com/auth/drive.file")
        // Scope para acceso completo a Drive (opcional, para leer archivos existentes)
        val DRIVE_SCOPE = Scope("https://www.googleapis.com/auth/drive")
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var googleSignInClient: GoogleSignInClient
    private var currentAccount: GoogleSignInAccount? = null
    private var authCallback: ((Result<GoogleAccount>) -> Unit)? = null

    init {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(DRIVE_FILE_SCOPE, DRIVE_SCOPE)
            .requestProfile()
            .build()

        googleSignInClient = GoogleSignIn.getClient(context, gso)
        
        // Verificar si ya hay sesión activa
        currentAccount = GoogleSignIn.getLastSignedInAccount(context)
        Logger.i("GoogleAuthManager: Cuenta actual = ${currentAccount?.email ?: "Ninguna"}")
    }

    /**
     * Inicia el flujo de Sign-In de Google.
     * @param activity Activity desde donde se inicia
     * @param callback Callback con el resultado (éxito o error)
     */
    fun signIn(activity: Activity, callback: (Result<GoogleAccount>) -> Unit) {
        authCallback = callback
        val signInIntent = googleSignInClient.signInIntent
        activity.startActivityForResult(signInIntent, RC_SIGN_IN)
        Logger.i("GoogleAuthManager: Iniciando Sign-In")
    }

    /**
     * Maneja el resultado de Sign-In desde onActivityResult.
     * Debe llamarse desde el Activity que inició el Sign-In.
     */
    fun handleSignInResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            handleSignInTask(task)
        }
    }

    private fun handleSignInTask(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            currentAccount = account
            
            // Guardar en preferencias
            prefs.edit().apply {
                putString(KEY_ACCOUNT_NAME, account?.email)
                putString(KEY_ACCOUNT_ID, account?.id)
                apply()
            }
            
            val googleAccount = GoogleAccount(
                id = account?.id ?: "",
                email = account?.email ?: "",
                displayName = account?.displayName ?: "",
                photoUrl = account?.photoUrl?.toString()
            )
            
            Logger.i("GoogleAuthManager: Sign-in exitoso - ${googleAccount.email}")
            authCallback?.invoke(Result.success(googleAccount))
            
        } catch (e: ApiException) {
            Logger.e("GoogleAuthManager: Sign-in falló código=${e.statusCode}", e)
            authCallback?.invoke(Result.failure(e))
        }
    }

    /**
     * Cierra la sesión de Google.
     */
    fun signOut(callback: ((Boolean) -> Unit)? = null) {
        googleSignInClient.signOut().addOnCompleteListener {
            currentAccount = null
            prefs.edit().clear().apply()
            Logger.i("GoogleAuthManager: Sign-out completado")
            callback?.invoke(it.isSuccessful)
        }
    }

    /**
     * Revoca el acceso de la app a la cuenta de Google.
     * El usuario deberá volver a conceder permisos.
     */
    fun revokeAccess(callback: ((Boolean) -> Unit)? = null) {
        googleSignInClient.revokeAccess().addOnCompleteListener {
            currentAccount = null
            prefs.edit().clear().apply()
            Logger.i("GoogleAuthManager: Acceso revocado")
            callback?.invoke(it.isSuccessful)
        }
    }

    /**
     * Verifica si hay una sesión activa.
     */
    fun isSignedIn(): Boolean {
        return currentAccount != null && currentAccount?.isExpired == false
    }

    /**
     * Obtiene la cuenta actual si existe.
     */
    fun getCurrentAccount(): GoogleAccount? {
        return currentAccount?.let {
            GoogleAccount(
                id = it.id ?: "",
                email = it.email ?: "",
                displayName = it.displayName ?: "",
                photoUrl = it.photoUrl?.toString()
            )
        }
    }

    /**
     * Obtiene el token ID para autenticación backend.
     */
    fun getIdToken(): String? {
        return currentAccount?.idToken
    }

    /**
     * Obtiene el servidor auth code para intercambio por tokens OAuth2.
     * Útil para acceso a Drive API REST.
     */
    fun getServerAuthCode(): String? {
        return currentAccount?.serverAuthCode
    }

    /**
     * Intenta renovar el token silenciosamente (sin interacción del usuario).
     * Útil para background operations.
     */
    fun silentSignIn(callback: (Result<GoogleAccount>) -> Unit) {
        googleSignInClient.silentSignIn().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                currentAccount = task.result
                val account = GoogleAccount(
                    id = task.result?.id ?: "",
                    email = task.result?.email ?: "",
                    displayName = task.result?.displayName ?: "",
                    photoUrl = task.result?.photoUrl?.toString()
                )
                Logger.i("GoogleAuthManager: Silent sign-in exitoso")
                callback(Result.success(account))
            } else {
                Logger.w("GoogleAuthManager: Silent sign-in falló")
                callback(Result.failure(task.exception ?: Exception("Silent sign-in failed")))
            }
        }
    }

    /**
     * Refresca el token OAuth2 para Drive API.
     * Nota: Este es un método placeholder - en implementación real
     * requiere intercambio del serverAuthCode por tokens OAuth2.
     */
    suspend fun refreshAccessToken(): String? {
        // En implementación real, usar GoogleAuthorizationCodeFlow
        // para intercambiar serverAuthCode por access_token y refresh_token
        return getServerAuthCode()
    }
}

/**
 * Datos de cuenta de Google del usuario.
 */
data class GoogleAccount(
    val id: String,
    val email: String,
    val displayName: String,
    val photoUrl: String? = null
)
