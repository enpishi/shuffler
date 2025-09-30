package com.ghancaballes.blacksheep

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.util.Log            // <-- Added import
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onStart() {
        super.onStart()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            navigateToPlayerManagement()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = Firebase.auth

        val emailEditText = findViewById<EditText>(R.id.editTextEmail)
        val passwordEditText = findViewById<EditText>(R.id.editTextPassword)
        val loginButton = findViewById<Button>(R.id.buttonLogin)
        val registerButton = findViewById<Button>(R.id.buttonRegister)
        val forgotPasswordButton = findViewById<Button?>(R.id.buttonForgotPassword)

        registerButton.setOnClickListener {
            val email = emailEditText.text.toString().trim()
            val password = passwordEditText.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val uid = auth.currentUser?.uid
                        if (uid == null) {
                            Toast.makeText(baseContext, "No user ID after registration.", Toast.LENGTH_LONG).show()
                            return@addOnCompleteListener
                        }
                        val userData = mapOf(
                            "email" to email,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                        Firebase.firestore.collection("users").document(uid).set(userData)
                            .addOnSuccessListener {
                                Toast.makeText(baseContext, "Registration successful.", Toast.LENGTH_SHORT).show()
                                navigateToPlayerManagement()
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(baseContext, "Failed to create user record: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                    } else {
                        val errorMessage = task.exception?.message ?: "Registration failed."
                        Toast.makeText(baseContext, errorMessage, Toast.LENGTH_LONG).show()
                    }
                }
        }

        loginButton.setOnClickListener {
            val email = emailEditText.text.toString().trim()
            val password = passwordEditText.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val uid = auth.currentUser?.uid
                        if (uid == null) {
                            Toast.makeText(baseContext, "No user ID after login.", Toast.LENGTH_LONG).show()
                            return@addOnCompleteListener
                        }
                        val usersRef = Firebase.firestore.collection("users").document(uid)
                        usersRef.get()
                            .addOnSuccessListener { snap ->
                                if (!snap.exists()) {
                                    val userData = mapOf(
                                        "email" to email,
                                        "createdAt" to FieldValue.serverTimestamp()
                                    )
                                    usersRef.set(userData)
                                        .addOnSuccessListener { navigateToPlayerManagement() }
                                        .addOnFailureListener { e ->
                                            Toast.makeText(baseContext, "Failed to create user record: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                } else {
                                    Toast.makeText(baseContext, "Login successful.", Toast.LENGTH_SHORT).show()
                                    navigateToPlayerManagement()
                                }
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(baseContext, "Login ok but user record check failed: ${e.message}", Toast.LENGTH_LONG).show()
                                navigateToPlayerManagement()
                            }
                    } else {
                        val errorMessage = task.exception?.message ?: "Login failed."
                        Toast.makeText(baseContext, errorMessage, Toast.LENGTH_LONG).show()
                    }
                }
        }

        forgotPasswordButton?.setOnClickListener {
            val existingEmail = emailEditText.text.toString().trim()
            if (existingEmail.isNotEmpty()) {
                sendPasswordReset(existingEmail)
            } else {
                promptForEmailAndReset()
            }
        }
    }

    private fun promptForEmailAndReset() {
        val input = EditText(this).apply {
            hint = "Email address"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        AlertDialog.Builder(this)
            .setTitle("Reset Password")
            .setMessage("Enter your account email to receive a reset link.")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isEmpty()) {
                    Toast.makeText(this, "Email cannot be empty.", Toast.LENGTH_SHORT).show()
                } else {
                    sendPasswordReset(email)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendPasswordReset(email: String) {
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "Invalid email format.", Toast.LENGTH_SHORT).show()
            return
        }

        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(
                        this,
                        "If an account exists for that email, a reset link has been sent.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val ex = task.exception
                    val msg = ex?.message ?: "Could not send reset email."
                    // Still show generic message to avoid account enumeration
                    Toast.makeText(
                        this,
                        "If an account exists for that email, a reset link has been sent.",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.w("ForgotPassword", "sendPasswordResetEmail error: $msg", ex)
                }
            }
    }

    private fun navigateToPlayerManagement() {
        val intent = Intent(this, PlayerManagementActivity::class.java)
        startActivity(intent)
        finish()
    }
}