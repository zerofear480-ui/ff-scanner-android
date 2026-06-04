package com.raj.ffscanner

import android.app.Activity
import android.os.Bundle
import android.widget.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : Activity() {

    private val client = OkHttpClient()
    private var apiUrl = "http://13.204.87.106:8000/api/ocr-scan"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 60, 40, 40)

        val title = TextView(this)
        title.text = "FF Scanner"
        title.textSize = 28f

        val status = TextView(this)
        status.text = "Status: Ready"
        status.textSize = 18f

        val apiInput = EditText(this)
        apiInput.setText(apiUrl)
        apiInput.hint = "AWS OCR API URL"

        val testButton = Button(this)
        testButton.text = "Send Test OCR Data"

        testButton.setOnClickListener {
            apiUrl = apiInput.text.toString()
            status.text = "Status: Sending test..."

            val json = """
                {
                  "players": [
                    {"slot":1,"name":"Raj","kills":15},
                    {"slot":2,"name":"Aman","kills":6},
                    {"slot":3,"name":"Rohit","kills":9}
                  ]
                }
            """.trimIndent()

            sendToAws(json, status)
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(apiInput)
        layout.addView(testButton)

        setContentView(layout)
    }

    private fun sendToAws(json: String, status: TextView) {
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                runOnUiThread {
                    status.text = "Status: Error - ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    status.text = "Status: Sent - ${response.code}"
                }
                response.close()
            }
        })
    }
}
