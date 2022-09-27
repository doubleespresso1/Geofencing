package com.example.geofencing

import android.content.Context
import android.widget.Toast

public class Toastr {

        public fun startGps(context: Context?, message: String?) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }

}