package com.ghancaballes.blacksheep

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
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
                        // Create a user doc to namespace data
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
                        // Ensure the user doc exists
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
    }

    private fun navigateToPlayerManagement() {
        val intent = Intent(this, PlayerManagementActivity::class.java)
        startActivity(intent)
        finish()
    }
}